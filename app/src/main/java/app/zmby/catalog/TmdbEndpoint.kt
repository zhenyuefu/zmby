package app.zmby.catalog

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** A base URL without credentials or request parameters; root domains use TMDB API v3. */
object TmdbEndpoint {
    const val DEFAULT = "https://api.tmdb.org/3/"

    fun normalize(raw: String): String? {
        val value = raw.trim()
        if (value.isEmpty()) return DEFAULT
        if (value.any { it.isWhitespace() || it == '\\' }) return null
        val url = value.toHttpUrlOrNull() ?: return null
        if (url.username.isNotEmpty() || url.password.isNotEmpty() || url.query != null || url.fragment != null) return null
        val path = url.encodedPath.trimEnd('/').ifEmpty { "/3" }
        return url.newBuilder().encodedPath("$path/").build().toString()
    }

    fun check(raw: String): String? = if (normalize(raw) == null)
        "请输入 HTTP 或 HTTPS 地址，不含账号、密码、查询参数或片段" else null
}
