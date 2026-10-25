package app.zmby.playback

import app.zmby.emby.MediaStream
import app.zmby.zidoo.ZidooPlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * One playback handed to com.zidoo.player as a local .strm with the chosen external subtitle beside
 * it (see [ZidooPlayer.SubtitleDelivery.LocalStrm]). The player reads the .strm's first line and
 * streams that URL; its subtitle scanner looks next to the .strm for files whose name starts with
 * the .strm's base name, and [subtitlePid] picks the file.
 */
data class LocalStrmSession(val baseName: String, val strm: File, val subtitle: File, val subtitlePid: String) {
    val uri: String get() = "file://${strm.absolutePath}"
}

/** The chosen subtitle could not be made available; the user decides whether to play without it. */
class SubtitlePrepareException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** File names, contents and checks for a local STRM session. Pure Kotlin. */
object LocalStrm {
    /** Larger "subtitles" are not subtitles (or not worth a blind download). */
    const val MAX_SUBTITLE_BYTES = 10 * 1024 * 1024

    fun supports(stream: MediaStream): Boolean =
        stream.type == "Subtitle" && stream.isExternal && ZidooPlayer.externalFormat(stream.codec) in ZidooPlayer.LOCAL_SUBTITLE_FORMATS

    /** Stable per server + item + version, so different videos never share a base name. */
    fun sourceKey(server: String, itemId: String, mediaSourceId: String): String =
        hex(MessageDigest.getInstance("SHA-256").digest("$server\n$itemId\n$mediaSourceId".toByteArray())).take(16)

    /**
     * All sessions share one directory, so every playback gets its own base name. The session part
     * has a fixed length: the player matches subtitles by name prefix, and a shorter id must never be
     * a prefix of a longer one.
     */
    fun baseName(sourceKey: String, nowMs: Long, random: Int): String =
        "zmby_${sourceKey}_%011x%04x".format(nowMs and 0xFFFFFFFFFFFL, random and 0xFFFF)

    fun strmName(base: String) = "$base.strm"

    /** Same base name as the .strm, then language, Emby index and the real extension. */
    fun subtitleName(base: String, stream: MediaStream): String {
        val lang = stream.language?.filter { it.isLetterOrDigit() && it.code < 128 || it == '-' }?.take(16)?.ifEmpty { null } ?: "und"
        return "$base.$lang.${stream.index}.${ZidooPlayer.externalFormat(stream.codec)}"
    }

    /** Exactly one UTF-8 line, no BOM: the player takes the first line as the video URL. */
    fun strmContent(videoUrl: String): ByteArray {
        require(videoUrl.startsWith("http://", true) || videoUrl.startsWith("https://", true)) { "STRM needs an HTTP URL" }
        require('\n' !in videoUrl && '\r' !in videoUrl) { "URL contains a line break" }
        return (videoUrl + "\n").toByteArray(Charsets.UTF_8)
    }

    /** What com.zidoo.player uses as the external subtitle's id: MD5 of the whole file, lowercase hex. */
    fun subtitlePid(bytes: ByteArray): String = hex(MessageDigest.getInstance("MD5").digest(bytes))

    /** Why [bytes] are not a usable subtitle in [format], or null when they look fine. */
    fun problem(bytes: ByteArray, format: String): String? {
        if (bytes.isEmpty()) return "字幕文件是空的"
        if (bytes.size > MAX_SUBTITLE_BYTES) return "字幕文件过大"
        // ISO-8859-1 keeps every byte; a UTF-8 BOM reads as "ï»¿".
        val head = String(bytes, 0, minOf(bytes.size, 64 * 1024), Charsets.ISO_8859_1).removePrefix("ï»¿").trimStart()
        if (head.startsWith("<") && Regex("<(!doctype|html|head|body)", RegexOption.IGNORE_CASE).containsMatchIn(head.take(1024))) {
            return "服务器返回的是网页而不是字幕"
        }
        val looksRight = when (format) {
            "srt" -> "-->" in head
            "ass", "ssa" -> head.contains("[Events]", true) || head.contains("Dialogue:", true) || head.contains("[Script Info]", true)
            else -> true
        }
        return if (looksRight) null else "字幕内容不是 ${format.uppercase()} 格式"
    }

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
}

/**
 * The shared directory com.zidoo.player reads local-STRM sessions from (it runs as the system uid
 * with all-files access, 2026-10-24). Files appear complete or not at all; only files this app
 * created are touched.
 */
interface PlaybackFileStore {
    /** Where the files go, for messages. */
    val location: String

    /** Writes [name] and returns its real path; fails if the store renamed or truncated it. */
    fun publish(name: String, bytes: ByteArray): File

    fun delete(file: File)

    /** Deletes this app's files there that are older than [maxAgeMs], except [keep]; returns how many. */
    fun sweep(maxAgeMs: Long, keep: Set<String> = emptySet()): Int
}

/** A plain directory, for tests; on the box the app cannot create the shared directory itself (see [MediaStorePlaybackStore]). */
class DirectoryPlaybackStore(private val root: File, private val now: () -> Long = System::currentTimeMillis) : PlaybackFileStore {
    override val location: String get() = root.path

    override fun publish(name: String, bytes: ByteArray): File {
        if (!root.isDirectory && !root.mkdirs()) throw IOException("cannot create $root")
        val part = File(root, ".$name.part")
        part.writeBytes(bytes)
        val file = File(root, name)
        if (!part.renameTo(file) || file.length() != bytes.size.toLong()) throw IOException("$name was not written intact")
        return file
    }

    override fun delete(file: File) {
        if (file.parentFile?.canonicalPath == root.canonicalPath && OURS.matches(file.name)) file.delete()
    }

    override fun sweep(maxAgeMs: Long, keep: Set<String>): Int {
        val stale = root.listFiles().orEmpty().filter { it.isFile && OURS.matches(it.name) && now() - it.lastModified() > maxAgeMs && it.name !in keep }
        stale.forEach(::delete)
        return stale.size
    }

    private companion object {
        val OURS = Regex("zmby_.+")
    }
}

/** Fetches one external subtitle from Emby with bounded time and size, as the server's client ([userAgent] of the URL). */
class SubtitleDownloader(http: OkHttpClient, private val userAgent: (String) -> String) {
    private val http = http.newBuilder().callTimeout(30, TimeUnit.SECONDS).build()

    suspend fun download(url: String): ByteArray = withContext(Dispatchers.IO) {
        http.newCall(Request.Builder().url(url).header("User-Agent", userAgent(url)).build()).execute().use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            val body = r.body ?: throw IOException("empty response")
            if (body.contentLength() > LocalStrm.MAX_SUBTITLE_BYTES) throw IOException("too large (${body.contentLength()} bytes)")
            val bytes = body.byteStream().use { it.readNBytesCompat(LocalStrm.MAX_SUBTITLE_BYTES + 1) }
            if (bytes.size > LocalStrm.MAX_SUBTITLE_BYTES) throw IOException("too large")
            bytes
        }
    }

    private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        while (out.size() < limit) {
            val n = read(buf, 0, minOf(buf.size, limit - out.size()))
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }
}

/** Builds a [LocalStrmSession]: subtitle downloaded and checked, then published before the .strm. */
class LocalStrmPreparer(
    private val store: PlaybackFileStore,
    private val download: suspend (String) -> ByteArray,
    private val now: () -> Long = System::currentTimeMillis,
) {
    suspend fun prepare(videoUrl: String, sourceKey: String, stream: MediaStream, subtitleUrl: String): LocalStrmSession {
        val format = ZidooPlayer.externalFormat(stream.codec)
        val bytes = try {
            download(subtitleUrl)
        } catch (e: Exception) {
            throw SubtitlePrepareException("字幕下载失败（${e.message ?: e.javaClass.simpleName}）", e)
        }
        LocalStrm.problem(bytes, format)?.let { throw SubtitlePrepareException(it) }
        val base = LocalStrm.baseName(sourceKey, now(), Random.nextInt())
        return withContext(Dispatchers.IO) {
            val subtitle = try {
                store.publish(LocalStrm.subtitleName(base, stream), bytes)
            } catch (e: Exception) {
                throw SubtitlePrepareException("无法在 ${store.location} 写入字幕（${e.message}）", e)
            }
            val strm = try {
                store.publish(LocalStrm.strmName(base), LocalStrm.strmContent(videoUrl))
            } catch (e: Exception) {
                store.delete(subtitle)
                throw SubtitlePrepareException("无法在 ${store.location} 写入播放文件（${e.message}）", e)
            }
            if (strm.parentFile?.path != subtitle.parentFile?.path) {
                store.delete(subtitle)
                store.delete(strm)
                throw SubtitlePrepareException("字幕与播放文件不在同一目录")
            }
            LocalStrmSession(base, strm, subtitle, LocalStrm.subtitlePid(bytes))
        }
    }

    /** Deletes a finished session's files. */
    fun release(session: LocalStrmSession) {
        store.delete(session.subtitle)
        store.delete(session.strm)
    }
}
