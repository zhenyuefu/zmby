package app.zmby.emby

import kotlinx.serialization.Serializable
import okhttp3.Interceptor
import okhttp3.Response
import java.io.EOFException
import java.io.IOException
import java.net.SocketException

/**
 * Which client a server is spoken to as (服务器页 › 这台服务器 › User-Agent). Some servers (mostly
 * public ones) only let official Emby clients in and check the User-Agent and the client name.
 */
@Serializable
enum class UaMode(val label: String) {
    /** zmby first; when the server turns zmby away, the official identity, remembered per server. */
    Auto("自动"),
    Zmby("zmby"),
    Official("官方 Emby"),
    Custom("自定义"),
}

/** One server's choice plus what [UaMode.Auto] found out about it. */
@Serializable
data class UaSetting(
    val mode: UaMode = UaMode.Auto,
    /** The identity that worked last under [UaMode.Auto] ([UaMode.Zmby] or [UaMode.Official]); null = not needed yet. */
    val detected: UaMode? = null,
    /** The User-Agent for [UaMode.Custom]. */
    val custom: String? = null,
) {
    /** The identity requests use now; a custom choice without a string stays zmby. */
    val effective: UaMode
        get() = when (mode) {
            UaMode.Auto -> detected ?: UaMode.Zmby
            UaMode.Custom -> if (custom.isNullOrBlank()) UaMode.Zmby else UaMode.Custom
            else -> mode
        }

    /** Only Auto switches identity by itself; a fixed choice is the user's. */
    val canFallBack: Boolean get() = mode == UaMode.Auto


}

/** What goes into User-Agent and the X-Emby-Authorization client fields. */
data class ClientIdentity(val userAgent: String, val client: String, val version: String)

/**
 * The official identity is Emby for Android TV, the official client for this kind of box:
 * - client name `AndroidTv`, version `2.1.54g`: Emby for Android TV 2.1.54g on Android 11 as an Emby
 *   proxy logged it (github.com/ArizeSky/Emby-In-One issue 21, 2026-10-19: "AndroidTv 2.1.54g, UA Dalvik/2.1.0");
 * - User-Agent: Android's own HTTP agent, `Dalvik/2.1.0 (Linux; U; Android <release>; <model> Build/<id>)`
 *   (`System.getProperty("http.agent")`), which is what the app sends (same issue; Emby server logs in
 *   emby.media/community topic 40474 show `Dalvik/2.1.0 (Linux; U; Android 6.0.1; Leelbox Build/MHC19J)`).
 * Zidoo's own Emby client (com.zidoo.music, uf/l.java, of/c.java) falls back the same way, to a desktop
 * Chrome User-Agent when a server answers non-200, "403 " or HTML; that string is a sensible 自定义 value.
 */
object OfficialEmby {
    const val CLIENT = "AndroidTv"
    const val VERSION = "2.1.54g"

    fun dalvikAgent(release: String, model: String, buildId: String?): String =
        "Dalvik/2.1.0 (Linux; U; Android $release; $model${buildId?.let { " Build/$it" }.orEmpty()})"
}

/**
 * Whether a failed request looks like the server turning this client away (so another identity may
 * pass), rather than the server being down or slow: 401/403 and friends, an HTML page where JSON was
 * expected, or the connection dropped without an answer (nginx `return 444`, reset).
 */
object Rejection {
    private val CODES = setOf(400, 401, 403, 406, 412, 418, 444)

    fun isRejection(e: Throwable): Boolean = when {
        e is NotEmbyException -> true
        e is EmbyException -> e.code in CODES
        e is EOFException || e.cause is EOFException -> true
        e is SocketException && e.message.orEmpty().contains("reset", ignoreCase = true) -> true
        e is IOException && e.message.orEmpty().contains("unexpected end of stream", ignoreCase = true) -> true
        else -> false
    }
}

/** A 2xx answer that is not Emby: a login page, a block page, a CDN challenge. */
class NotEmbyException(message: String) : IOException(message)

/**
 * Puts the right User-Agent on requests that do not set one themselves (images through Coil,
 * subtitle downloads, stream probes): the identity of the server whose address the URL starts
 * with, else [default].
 */
class UserAgentInterceptor(private val default: String, private val lookup: (String) -> String?) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.header("User-Agent") != null) return chain.proceed(request)
        val agent = runCatching { lookup(request.url.toString()) }.getOrNull() ?: default
        return chain.proceed(request.newBuilder().header("User-Agent", agent).build())
    }
}
