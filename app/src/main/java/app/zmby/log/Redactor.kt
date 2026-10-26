package app.zmby.log

import java.util.concurrent.CopyOnWriteArraySet

/**
 * Masks credentials before a line reaches the log buffer or logcat: Emby tokens in URLs, headers
 * and JSON, passwords, and the signature parameters of signed cloud-drive links. Values learned at
 * runtime (the session token) are also masked literally via [addSecret], which catches places the
 * patterns do not know about.
 */
class Redactor {
    private val secrets = CopyOnWriteArraySet<String>()

    fun addSecret(value: String?) {
        // Short values would mask ordinary words; real tokens are long.
        if (value != null && value.length >= 8) secrets += value
    }

    fun redact(text: String): String {
        var out = text
        for (s in secrets) if (s in out) out = out.replace(s, MASK)
        out = QUOTED_FIELD.replace(out) { "${it.groupValues[1]}=\"$MASK\"" }
        out = QUERY.replace(out) { "${it.groupValues[1]}=$MASK" }
        out = TOKEN_HEADER.replace(out) { "${it.groupValues[1]}: $MASK" }
        out = AUTH_HEADER.replace(out) { "${it.groupValues[1]}: ${it.groupValues[2]}$MASK" }
        out = JSON_FIELD.replace(out) { "\"${it.groupValues[1]}\":\"$MASK\"" }
        out = BEARER.replace(out) { "Bearer $MASK" }
        return out
    }

    companion object {
        const val MASK = "***"

        /** Query and form parameters that carry credentials or link signatures. */
        private val SENSITIVE_PARAMS = listOf(
            "api_key", "apikey", "x-emby-token", "x-mediabrowser-token", "token", "access_token", "accesstoken",
            "auth_key", "authkey", "auth", "sign", "signature", "sig", "x-amz-signature", "x-amz-credential",
            "x-amz-security-token", "ossaccesskeyid", "googleaccessid", "x-oss-signature", "x-oss-credential",
            "password", "passwd", "pwd", "pw", "secret", "key",
        )

        private val QUERY = Regex(
            "(?i)(?<=[?&;\\s]|^)(${SENSITIVE_PARAMS.joinToString("|") { Regex.escape(it) }})=([^&#\\s\"'<>]+)",
        )

        /** `Token="…"` inside X-Emby-Authorization, and the same shape for passwords. */
        private val QUOTED_FIELD = Regex("(?i)\\b(Token|Pw|Password)\\s*=\\s*\"[^\"]*\"")

        private val TOKEN_HEADER = Regex("(?i)\\b(X-Emby-Token|X-MediaBrowser-Token|Cookie|Set-Cookie)\\s*:\\s*[^\\r\\n,;]+")

        /** A plain Authorization header; X-Emby-Authorization keeps its fields and loses only Token="…". */
        private val AUTH_HEADER = Regex("(?i)(?<![-\\w])(Authorization)\\s*:\\s*(Bearer |Basic )?[^\\r\\n]+")

        /** A bearer token anywhere in a line (the segment databases' API keys, e.g. PublicMetaDB). */
        private val BEARER = Regex("(?i)\\bBearer\\s+(?!\\*\\*\\*)[A-Za-z0-9._~+/=-]{8,}")

        private val JSON_FIELD = Regex("(?i)\"(AccessToken|Pw|Password|backupPassword|confirm|Token|tmdb_read_token|publicmetadb_key|api_key|X-Emby-Token)\"\\s*:\\s*\"[^\"]*\"")

        /**
         * A signed link reduced to what is safe and useful to log: scheme, host, path and the
         * parameter names. Used for 302 targets, whose signature parameter names vary by provider.
         */
        fun linkShape(url: String): String {
            val q = url.indexOf('?')
            if (q < 0) return url
            val names = url.substring(q + 1).split('&').filter { it.isNotEmpty() }.map { it.substringBefore('=') + "=" + MASK }
            return url.substring(0, q) + "?" + names.joinToString("&")
        }
    }
}
