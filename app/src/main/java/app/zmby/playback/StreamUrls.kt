package app.zmby.playback

import app.zmby.emby.MediaSourceInfo
import app.zmby.emby.MediaStream
import app.zmby.zidoo.ZidooPlayer
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Building and rewriting the HTTP addresses handed to the player. */
object StreamUrls {
    private const val EMBY_PREFIX = "/emby"

    /** A server-relative or absolute URL from Emby, made absolute against [baseUrl]. */
    fun resolve(baseUrl: String, raw: String): HttpUrl? {
        if (raw.startsWith("http://", true) || raw.startsWith("https://", true)) return raw.toHttpUrlOrNull()
        val base = baseUrl.trimEnd('/')
        var path = "/" + raw.trimStart('/')
        // A base that already ends in /emby and a path that starts with it must not double up.
        if (base.endsWith(EMBY_PREFIX, ignoreCase = true) && path.startsWith("$EMBY_PREFIX/", ignoreCase = true)) {
            path = path.substring(EMBY_PREFIX.length)
        }
        return (base + path).toHttpUrlOrNull()
    }

    private fun HttpUrl.withToken(token: String): HttpUrl =
        if (queryParameter("api_key") != null || queryParameter("X-Emby-Token") != null) this
        else newBuilder().addQueryParameter("api_key", token).build()

    /**
     * The MediaSource's `DirectStreamUrl`, preferred over a self-built `/Videos/{id}/stream` URL:
     * it is what the server itself would hand a client, and 302 cloud-drive setups rewrite it.
     * The player cannot send headers, so the token is added as `api_key` when missing.
     */
    fun direct(baseUrl: String, source: MediaSourceInfo, token: String): String? {
        val raw = source.directStreamUrl?.takeIf { it.isNotBlank() } ?: return null
        return resolve(baseUrl, raw)?.withToken(token)?.toString()
    }

    /** "/emby" when the URL's path is under the /emby prefix, else "". */
    fun prefixOf(url: String): String {
        val path = url.toHttpUrlOrNull()?.encodedPath ?: return ""
        return if (path.equals(EMBY_PREFIX, true) || path.startsWith("$EMBY_PREFIX/", true)) EMBY_PREFIX else ""
    }

    /** The same URL with its path moved under [prefix] ("" or "/emby"). */
    fun withPrefix(url: String, prefix: String): String {
        val u = url.toHttpUrlOrNull() ?: return url
        val path = u.encodedPath
        val bare = if (prefixOf(url).isNotEmpty()) path.substring(EMBY_PREFIX.length).ifEmpty { "/" } else path
        return u.newBuilder().encodedPath(prefix + bare).build().toString()
    }

    fun otherPrefix(prefix: String) = if (prefix.isEmpty()) EMBY_PREFIX else ""

    /** Where an external subtitle stream can be fetched over HTTP. */
    fun subtitle(baseUrl: String, itemId: String, source: MediaSourceInfo, stream: MediaStream, token: String): String? {
        stream.deliveryUrl?.takeIf { it.isNotBlank() }?.let { raw -> return resolve(baseUrl, raw)?.withToken(token)?.toString() }
        val ext = subtitleExtension(stream.codec)
        return resolve(baseUrl, "/Videos/$itemId/${source.id}/Subtitles/${stream.index}/Stream.$ext")?.withToken(token)?.toString()
    }

    fun subtitleExtension(codec: String?): String = ZidooPlayer.externalFormat(codec)
}
