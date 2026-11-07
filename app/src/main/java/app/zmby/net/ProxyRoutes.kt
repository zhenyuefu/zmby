package app.zmby.net

import app.zmby.log.ZLog
import java.io.IOException
import java.net.Authenticator
import java.net.InetSocketAddress
import java.net.PasswordAuthentication
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI

/** 代理类型 (设置 › 网络). */
enum class ProxyKind(val label: String) { Off("关闭"), Http("HTTP"), Socks("SOCKS5") }

/**
 * The outside APIs that can be sent through the proxy, each with the domains it talks to (a domain
 * also covers its subdomains). [Danmu] has none of its own: it is the 弹幕服务器 the user typed.
 * Requests reach the proxy decision only as scheme, host and port, so a shared host (the npm CDNs
 * behind bangumi-data) goes through the proxy for everything zmby asks it.
 */
enum class ProxyApi(val label: String, val sub: String, internal val domains: List<String>) {
    Tmdb("TMDB", "资料、海报与剧照，含自定义 API 地址", listOf("tmdb.org", "themoviedb.org")),
    Bangumi("Bangumi", "番剧资料与 bangumi-data", listOf("bgm.tv", "bangumi.tv", "cdn.jsdelivr.net", "unpkg.com")),
    Danmu("弹幕服务器", "弹幕设置里填写的地址", emptyList()),
    Imdb("IMDb", "预告片与评分", listOf("imdb.com", "media-imdb.com", "media-amazon.com", "ssl-images-amazon.com")),
    RottenTomatoes("烂番茄", "评分、预告片与 Wikidata", listOf("rottentomatoes.com", "wikidata.org", "theplatform.com")),
    Segments("片段标记", "TheIntroDB · IntroDB · PublicMetaDB", listOf("theintrodb.org", "introdb.app", "publicmetadb.com")),
    ;

    companion object {
        /** The stored set (comma-separated names); unknown names are dropped. */
        fun parse(raw: String): Set<ProxyApi> = raw.split(',').mapNotNull { n -> entries.firstOrNull { it.name == n.trim() } }.toSet()
        fun store(apis: Set<ProxyApi>): String = entries.filter { it in apis }.joinToString(",") { it.name }
    }
}

/** The proxy's `host:port`, as typed in 设置 › 网络 › 地址. */
data class ProxyAddress(val host: String, val port: Int) {
    override fun toString() = if (':' in host) "[$host]:$port" else "$host:$port"

    companion object {
        private val SCHEME = Regex("(?i)^(https?|socks5h?|socks)://")
        private val HOST = Regex("[A-Za-z0-9.-]+")
        private val IPV6 = Regex("[0-9A-Fa-f:.]+")

        /** `host:port` or `[v6]:port`; a leading `http://` or `socks5://` and a trailing slash are dropped. */
        fun parse(raw: String): ProxyAddress? {
            val text = raw.trim().replace(SCHEME, "").trimEnd('/')
            if ('@' in text || '/' in text) return null
            val (host, port) = if (text.startsWith('[')) {
                val end = text.indexOf(']')
                if (end < 0 || text.getOrNull(end + 1) != ':') return null
                text.substring(1, end).takeIf { IPV6.matches(it) } to text.substring(end + 2)
            } else {
                if (text.count { it == ':' } != 1) return null
                text.substringBefore(':').takeIf { HOST.matches(it) && !it.startsWith('.') && !it.endsWith('.') } to text.substringAfter(':')
            }
            val number = port.takeIf { p -> p.isNotEmpty() && p.all { it.isDigit() } }?.toIntOrNull()?.takeIf { it in 1..65535 }
            return if (host.isNullOrEmpty() || number == null) null else ProxyAddress(host.lowercase(), number)
        }

        /** Why [raw] cannot be used, or null. */
        fun check(raw: String): String? = when {
            '@' in raw -> "用户名和密码请分别填写"
            parse(raw) == null -> "请填写 主机:端口，例如 192.168.1.2:7890"
            else -> null
        }
    }
}

data class ProxyCredentials(val user: String, val password: String)

/**
 * What goes through the proxy right now: the proxy itself, the origins (`scheme://host:port`) of the
 * server lines and typed API addresses that use it, and the domains of the chosen outside APIs.
 * Built again from the settings and the registry whenever one of them changes.
 */
data class ProxyPlan(
    val proxy: Proxy?,
    val address: ProxyAddress?,
    val credentials: ProxyCredentials?,
    private val origins: Set<String>,
    private val domains: Set<String>,
) {
    /** Whether a request to [uri] goes through [proxy]; never for this box itself (the relay, the player's API). */
    fun proxies(uri: URI): Boolean {
        if (proxy == null) return false
        val host = uri.host?.removePrefix("[")?.removeSuffix("]")?.lowercase()?.trimEnd('.') ?: return false
        if (isLoopback(host)) return false
        val scheme = uri.scheme?.lowercase() ?: return false
        if (originOf(scheme, host, uri.port) in origins) return true
        return domains.any { host == it || host.endsWith(".$it") }
    }

    fun proxies(url: String): Boolean = runCatching { URI(url.trim()) }.getOrNull()?.let(::proxies) ?: false

    /** Whether any server line or API uses the proxy (the settings summary). */
    val active: Boolean get() = proxy != null && (origins.isNotEmpty() || domains.isNotEmpty())

    companion object {
        val Off = ProxyPlan(null, null, null, emptySet(), emptySet())

        /**
         * [lines] are the URLs of the server lines switched to the proxy; [tmdbBase] and [danmuBase] the
         * typed API addresses, used when their API is in [apis]. Off when [kind] is off or the address
         * does not parse.
         */
        fun build(
            kind: ProxyKind, address: String, user: String, password: String,
            lines: List<String>, apis: Set<ProxyApi>, tmdbBase: String, danmuBase: String,
        ): ProxyPlan {
            val at = ProxyAddress.parse(address)
            if (kind == ProxyKind.Off || at == null) return Off
            val type = if (kind == ProxyKind.Socks) Proxy.Type.SOCKS else Proxy.Type.HTTP
            // Unresolved: the proxy's name is looked up when a connection is made, never here.
            val proxy = Proxy(type, InetSocketAddress.createUnresolved(at.host, at.port))
            val typed = listOfNotNull(tmdbBase.takeIf { ProxyApi.Tmdb in apis }, danmuBase.takeIf { ProxyApi.Danmu in apis })
            val origins = (lines + typed).mapNotNull(::originOf).toSet()
            val domains = apis.flatMap { it.domains }.toSet()
            val credentials = if (user.isNotEmpty()) ProxyCredentials(user, password) else null
            return ProxyPlan(proxy, at, credentials, origins, domains)
        }

        internal fun originOf(url: String): String? {
            val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return null
            val host = uri.host?.removePrefix("[")?.removeSuffix("]")?.lowercase()?.trimEnd('.') ?: return null
            return originOf(uri.scheme?.lowercase() ?: return null, host, uri.port)
        }

        private fun originOf(scheme: String, host: String, port: Int): String {
            val p = if (port >= 0) port else when (scheme) { "https" -> 443; "http" -> 80; else -> -1 }
            return "$scheme://$host:$p"
        }

        private fun isLoopback(host: String) =
            host == "localhost" || host.endsWith(".localhost") || host.startsWith("127.") || host == "::1" || host == "0:0:0:0:0:0:0:1"
    }
}

/**
 * The app's proxy decision (设置 › 网络): every OkHttp client and, as the process default, the
 * platform's HttpURLConnection (the trailers' media3 player) ask it per connection. What is not
 * chosen for the proxy keeps what the system would have done ([fallback]).
 *
 * A pooled connection made before a change stays as it was until it is evicted; the app evicts its
 * pools when [update] reports a change.
 */
class ProxyRoutes(private val fallback: ProxySelector?) : ProxySelector() {
    @Volatile
    var plan: ProxyPlan = ProxyPlan.Off
        private set

    /** Returns whether anything changed. */
    fun update(next: ProxyPlan): Boolean {
        if (next == plan) return false
        plan = next
        ZLog.i(TAG, if (next.proxy == null) "proxy off" else "proxy ${next.proxy.type()} in use; credentials ${if (next.credentials != null) "set" else "none"}")
        return true
    }

    fun proxies(url: String): Boolean = plan.proxies(url)

    override fun select(uri: URI?): List<Proxy> {
        requireNotNull(uri) { "uri" }
        val p = plan
        if (p.proxy != null && p.proxies(uri)) return listOf(p.proxy)
        return fallback?.select(uri) ?: listOf(Proxy.NO_PROXY)
    }

    override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) {
        val proxy = plan.proxy
        if (proxy != null && sa == proxy.address()) {
            // Neither the proxy's address nor the target goes into the log.
            ZLog.w(TAG, "proxy connection failed: ${ioe?.javaClass?.simpleName}")
        } else if (uri != null && sa != null && ioe != null) {
            fallback?.connectFailed(uri, sa, ioe)
        }
    }

    /** OkHttp's answer to an HTTP proxy's 407 (and the preemptive one for HTTPS tunnels). */
    val okHttpAuthenticator = okhttp3.Authenticator { route, response ->
        val p = plan
        val c = p.credentials
        when {
            c == null || route?.proxy != p.proxy -> null
            // Already sent and refused: asking again would loop.
            response.request.header("Proxy-Authorization") != null -> null
            else -> response.request.newBuilder().header("Proxy-Authorization", okhttp3.Credentials.basic(c.user, c.password)).build()
        }
    }

    /**
     * The platform's (HttpURLConnection, SOCKS sockets) prompt for credentials: answered only for the
     * proxy's own host and port, never for a server or a site.
     */
    val platformAuthenticator: Authenticator = object : Authenticator() {
        override fun getPasswordAuthentication(): PasswordAuthentication? {
            val p = plan
            val c = p.credentials ?: return null
            val at = p.address ?: return null
            val forProxy = requestorType == RequestorType.PROXY || requestingProtocol?.startsWith("SOCKS", ignoreCase = true) == true
            if (!forProxy || requestingPort != at.port || requestingHost?.removePrefix("[")?.removeSuffix("]")?.equals(at.host, ignoreCase = true) != true) return null
            return PasswordAuthentication(c.user, c.password.toCharArray())
        }
    }

    private companion object { const val TAG = "Proxy" }
}
