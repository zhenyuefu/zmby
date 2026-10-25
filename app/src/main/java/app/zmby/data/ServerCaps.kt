package app.zmby.data

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap

/** User override for a detected capability. */
enum class Override { Auto, On, Off }

/**
 * What the app has learned about one server, plus the user's overrides. Each fact is probed once
 * and re-checked after [RECHECK_MS]; a server's facts never stand in for another server's.
 */
@Serializable
data class ServerCaps(
    /** "" or "/emby": the path prefix under which stream Range requests work; null = not probed. */
    val rangePrefix: String? = null,
    /** The stream URL redirects to another host (302 cloud-drive server); null = not probed. */
    val redirects: Boolean? = null,
    val streamCheckedAt: Long = 0,
    /** On = play through the local relay, Off = hand the server URL to the player, Auto = decide from [redirects]. */
    val relay: Override = Override.Auto,
    /** SearchTerm matches pinyin initials (a server-side plugin); null = unknown. */
    val pinyin: Boolean? = null,
    val pinyinCheckedAt: Long = 0,
    val pinyinOverride: Override = Override.Auto,
    /** Server version at the last pinyin probe; a new version is probed again. */
    val version: String? = null,
) {
    fun streamStale(now: Long) = rangePrefix == null || now - streamCheckedAt > RECHECK_MS

    val supportsPinyin: Boolean
        get() = when (pinyinOverride) {
            Override.On -> true
            Override.Off -> false
            Override.Auto -> pinyin == true
        }

    companion object {
        const val RECHECK_MS = 7L * 24 * 3600 * 1000
    }
}

interface ServerCapsStore {
    fun get(server: String): ServerCaps
    fun update(server: String, change: (ServerCaps) -> ServerCaps): ServerCaps
}

class MemoryCapsStore : ServerCapsStore {
    private val map = ConcurrentHashMap<String, ServerCaps>()
    override fun get(server: String) = map[server] ?: ServerCaps()
    override fun update(server: String, change: (ServerCaps) -> ServerCaps) = change(get(server)).also { map[server] = it }
}

/** Kept in app-private preferences as one JSON object per server. */
class PrefsCapsStore(context: Context) : ServerCapsStore {
    private val prefs = context.getSharedPreferences("server_caps", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
    private val cache = ConcurrentHashMap<String, ServerCaps>()

    override fun get(server: String): ServerCaps = cache.getOrPut(server) {
        prefs.getString(server, null)?.let { runCatching { json.decodeFromString(ServerCaps.serializer(), it) }.getOrNull() } ?: ServerCaps()
    }

    @Synchronized
    override fun update(server: String, change: (ServerCaps) -> ServerCaps): ServerCaps {
        val next = change(get(server))
        cache[server] = next
        Disk.write { prefs.edit().putString(server, json.encodeToString(ServerCaps.serializer(), next)).apply() }
        return next
    }
}
