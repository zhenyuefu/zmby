package app.zmby.data

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Offline recognition of copied Emby account messages; incomplete credentials stay editable. */
internal object ConnectionText {
    private val field = Regex(
        "(?:^|[\\s,，;；|｜])(?:[🔑👤🔒]+\\s*)?(用户名|用户名称|用户|账号|帐号|账户|username|user|account|login|用户密码|密码|口令|password|passwd|pass|pwd|pw|服务器名称|服务名称|名称|服名|server\\s*name|服务器地址|服务器|server|地址|url|当前线路|(?:[a-zA-Z]{1,12})?线路[一二三四五六七八九十0-9]*|主线路|备用线路|直连线路|内网线路|外网线路|直连|备用|内网|外网)\\s*[:：=|｜]\\s*",
        RegexOption.IGNORE_CASE,
    )
    private val address = Regex(
        "https?://[^\\s<>\\\"'`，。；、）)\\]】]+|(?<![\\w@/])(?:[a-zA-Z0-9-]+\\.)+[a-zA-Z0-9-]+(?::[0-9]{1,5})?(?:/[^\\s<>\\\"'`，。；、）)\\]】]*)?|(?<![\\w@/])[a-zA-Z0-9-]+:[0-9]{1,5}(?:/[^\\s<>\\\"'`，。；、）)\\]】]*)?",
        RegexOption.IGNORE_CASE,
    )
    private val userKeys = setOf("用户名", "用户名称", "用户", "账号", "帐号", "账户", "username", "user", "account", "login")
    private val passKeys = setOf("用户密码", "密码", "口令", "password", "passwd", "pass", "pwd", "pw")
    private val nameKeys = setOf("服务器名称", "服务名称", "名称", "服名", "server name", "servername", "服务器", "server")
    private val metadata = Regex("^(?:验证码|校验码|安全密码|有效期|到期时间|过期时间|有效时间|verification\\s*code|expires?|expiration)\\s*[:：=|｜]", RegexOption.IGNORE_CASE)
    private val markdownLink = Regex("\\[[^\\]]*]\\((https?://[^\\s)]+)\\)", RegexOption.IGNORE_CASE)

    fun normalizeUrl(raw: String): String? {
        val value = raw.trim()
        val url = (if (value.contains("://")) value else "http://$value").toHttpUrlOrNull() ?: return null
        if (url.username.isNotEmpty() || url.password.isNotEmpty()) return null
        // Keep reverse proxy paths (and their case), rather than reducing addresses to their host.
        return url.toString().removeSuffix("/")
    }

    fun parse(text: String): List<ImportEntry> {
        val result = mutableListOf<ImportEntry>()
        var user: String? = null
        var password = ""
        var name: String? = null
        val lines = linkedMapOf<String, String?>()
        var pendingLabel: String? = null
        fun flush() {
            lines.forEach { (url, label) -> result += ImportEntry(url, user.orEmpty(), password, name, label) }
            lines.clear(); user = null; password = ""; name = null; pendingLabel = null
        }
        for (raw in text.lines()) {
            val line = raw.trim().trimStart('•', '>', '·').trim()
            if (line.isBlank() || line.startsWith("#") || metadata.containsMatchIn(line)) continue
            // Legacy rows remain useful for a whole list of unrelated servers.
            val columns = line.split(Regex("\\s+"), limit = 4)
            if (columns.size >= 2 && address.matchEntire(columns[0]) != null && field.find(line) == null) {
                // A second address belongs to the same account message, not to its username.
                if (normalizeUrl(columns[0]) != null && address.matchEntire(columns[1]) == null) {
                    flush()
                    result += ImportEntry(normalizeUrl(columns[0])!!, columns[1], columns.getOrElse(2) { "" }, columns.getOrNull(3))
                    continue
                }
            }
            val fields = field.findAll(line).toList()
            if (fields.isNotEmpty()) {
                // URLs before credentials on the same line are still part of this message.
                val prefix = line.substring(0, fields.first().range.first)
                collectAddresses(prefix, pendingLabel, lines)
                fields.forEachIndexed { i, match ->
                    val key = match.groupValues[1].lowercase()
                    val next = fields.getOrNull(i + 1)
                    val rawValue = line.substring(match.range.last + 1, next?.range?.first ?: line.length).trim()
                    val value = (if (next == null) rawValue else rawValue.trimEnd(',', '，', ';', '；', '|', ' '))
                        .removeSurrounding("\"").removeSurrounding("'").removeSurrounding("`")
                    when (key) {
                        in userKeys -> {
                            if (user != null && lines.isNotEmpty()) flush()
                            user = value
                        }
                        in passKeys -> password = value
                        in nameKeys -> {
                            if (key !in setOf("服务器", "server") || !collectAddresses(value, null, lines)) {
                                if (lines.isNotEmpty() && (name != null || user != null)) flush()
                                name = value.removeSurrounding("**").removeSurrounding("__")
                            }
                        }
                        else -> {
                            val label = match.groupValues[1].takeUnless { key in setOf("地址", "服务器地址", "url", "线路", "当前线路") }
                            collectAddresses(value, label, lines)
                        }
                    }
                }
                continue
            }
            if (!collectAddresses(line, pendingLabel, lines)) {
                // A bare title may be a server or a line heading; do not guess prose as credentials.
                if (line.length <= 50 && !line.startsWith("正在") && !line.startsWith("您已")) {
                    val title = line.trim().trimEnd(':', '：').removeSurrounding("【", "】")
                    if (title.contains("线路") || title in listOf("直连", "内网", "外网", "备用", "主线")) pendingLabel = title
                    else {
                        if (user != null && lines.isNotEmpty()) flush()
                        if (name == null && lines.isEmpty()) name = title
                    }
                }
            } else pendingLabel = null
        }
        flush()
        return result.distinctBy { Triple(it.url, it.user, it.password) }
    }

    private fun collectAddresses(text: String, pending: String?, lines: MutableMap<String, String?>): Boolean {
        var found = false
        var previousEnd = 0
        // Use Markdown destinations once; the display text may repeat the URL or say “直连”.
        val source = markdownLink.replace(text) { it.groupValues[1] }
        address.findAll(source).forEach { match ->
            val url = normalizeUrl(match.value.trimEnd('.', ',', ';', '!', '?')) ?: return@forEach
            val prefix = source.substring(previousEnd, match.range.first).trim().trimEnd(':', '：', '=', '-', ' ', '[', '【')
                .trimStart('(', '（', '[', '【', ',', '，', ';', '；', '|').trimEnd(')', '）', ']', '】').trim()
            val label = prefix.takeIf { it.isNotBlank() && it.length <= 30 && it !in listOf("地址", "服务器", "服务器地址", "url", "URL") } ?: pending
            lines.putIfAbsent(url, label)
            previousEnd = match.range.last + 1
            found = true
        }
        return found
    }
}
