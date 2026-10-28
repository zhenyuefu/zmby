package app.zmby.playback

import app.zmby.aggregate.tmdb
import app.zmby.catalog.CatalogId
import app.zmby.catalog.CatalogWork
import app.zmby.data.ServerItem
import app.zmby.emby.BaseItemDto
import app.zmby.net.HttpExchange
import app.zmby.net.MiniHttpServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File
import java.net.InetAddress
import java.util.UUID

data class PlaybackImages(val backdrop: String? = null, val logo: String? = null)

/** Resolves only already known metadata and image bytes. Episode IDs are never mistaken for series IDs. */
class PlaybackArtworkResolver(
    private val catalog: suspend (CatalogId) -> CatalogWork?,
    private val item: suspend (String, String) -> BaseItemDto?,
    private val backdrop: (ServerItem) -> String?,
    private val logo: (ServerItem) -> String?,
    private val serve: (List<String>) -> String?,
) {
    private val hints = object : LinkedHashMap<String, CatalogId>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CatalogId>) = size > 256
    }

    fun remember(server: String, itemId: String, work: CatalogId, seriesId: String? = null) {
        synchronized(hints) {
            hints["$server:$itemId"] = work
            if (seriesId != null) hints["$server:$seriesId"] = work
        }
    }

    suspend fun resolve(server: String, resource: BaseItemDto): PlaybackImages = withContext(Dispatchers.IO) {
        val saved = item(server, resource.id)
        val parent = if (resource.type == "Episode") resource.seriesId?.let { item(server, it) } else null
        val hint = synchronized(hints) { hints["$server:${resource.id}"] ?: resource.seriesId?.let { hints["$server:$it"] } }
        val identity = if (resource.type == "Episode") parent else resource
        val ref = hint ?: identity?.tmdb()?.takeIf { it.toLongOrNull()?.let { n -> n > 0 } == true }?.let {
            CatalogId("tmdb", if (identity.type == "Movie") "movie" else "tv", it)
        }
        val work = ref?.let { catalog(it) }
        val resources = listOfNotNull(resource, saved, parent).map { ServerItem(server, it) }
        PlaybackImages(
            serve((listOfNotNull(work?.backdrop) + resources.mapNotNull(backdrop)).distinct()),
            serve((listOfNotNull(work?.logo) + resources.mapNotNull(logo)).distinct()),
        )
    }
}

/** A pinned view of an existing disk-cache entry; closing it permits normal cache eviction. */
class CachedArtwork(val file: File, private val release: () -> Unit = {}) : Closeable {
    override fun close() = release()
}

/** Zidoo accepts HTTP URLs or public file paths. Loopback serves private Coil files without a new download. */
class CachedArtworkServer(private val open: (String) -> CachedArtwork?) {
    private val keys = object : LinkedHashMap<String, String>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>) = size > 32
    }
    private val server = MiniHttpServer("artwork", InetAddress.getByName("127.0.0.1"), listOf(0),
        accept = { it.isLoopbackAddress }) { serve(it) }

    fun cached(candidates: List<String>): String? {
        val key = candidates.firstOrNull { open(it)?.use { image -> image.file.isFile && image.file.length() > 0 } == true } ?: return null
        val port = server.start()
        val id = synchronized(keys) { keys.entries.firstOrNull { it.value == key }?.key ?: UUID.randomUUID().toString().also { keys[it] = key } }
        return "http://127.0.0.1:$port/art/$id"
    }

    private fun serve(exchange: HttpExchange) {
        val request = exchange.request
        if (request.method !in setOf("GET", "HEAD")) return exchange.sendText(405, "Method not allowed")
        val id = request.path.takeIf { it.startsWith("/art/") }?.removePrefix("/art/")
        val key = synchronized(keys) { keys[id] } ?: return exchange.sendText(404, "Not found")
        val image = open(key) ?: return exchange.sendText(404, "Not found")
        image.use {
            val type = it.file.inputStream().use { input ->
                val bytes = ByteArray(12); input.read(bytes)
                when {
                    bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() -> "image/png"
                    bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte() -> "image/jpeg"
                    String(bytes, Charsets.ISO_8859_1).startsWith("RIFF") -> "image/webp"
                    else -> "application/octet-stream"
                }
            }
            exchange.sendHead(200, listOf("Content-Type" to type, "Content-Length" to it.file.length().toString(), "Cache-Control" to "private, max-age=21600"))
            if (request.method != "HEAD") it.file.inputStream().use { input -> input.copyTo(exchange.output) }
            exchange.output.flush()
        }
    }

    fun stop() { server.stop(); synchronized(keys) { keys.clear() } }
}
