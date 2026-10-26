package app.zmby.data

import androidx.core.content.edit
import android.content.Context
import app.zmby.aggregate.FailureTracker
import app.zmby.aggregate.HealthBook
import app.zmby.emby.ServerSession
import app.zmby.emby.UaMode
import app.zmby.emby.UaSetting
import app.zmby.log.ZLog
import app.zmby.ui.components.Health
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/** One address of a server (LAN, DDNS, CDN…); all lines of a server reach the same Emby with the same token. */
@Serializable
data class ServerLine(val id: String, val name: String, val url: String)

/** How this server takes part in favourites (favorites spec): two-way, push only, or not at all. */
@Serializable
enum class FavoriteSync(val label: String) { TwoWay("双向"), PushOnly("只推送"), Off("不同步") }

/** One server in the registry, in priority order (the list order). */
@Serializable
data class ServerConfig(
    /** Local id, stable across renames and line changes; keys caps, library switches and favourites. */
    val id: String,
    val name: String,
    val lines: List<ServerLine>,
    val lineId: String,
    val userId: String,
    val token: String,
    val userName: String? = null,
    /** Emby's own server id (System/Info), to spot the same server added twice. */
    val embyServerId: String? = null,
    /** Off = not queried at all (kept for later). */
    val enabled: Boolean = true,
    /** 允许聚合: off = not in aggregated search, version lookup or cross-server home rows; still browsable. */
    val aggregate: Boolean = true,
    /** Pick the fastest line after each speed test. */
    val autoFastest: Boolean = false,
    val favoriteSync: FavoriteSync = FavoriteSync.TwoWay,
    /** Why [favoriteSync] was turned off automatically (the server refused a write). */
    val favoriteSyncNote: String? = null,
    /** Which client the server is spoken to as (User-Agent and client name), with what Auto found. */
    val ua: UaSetting = UaSetting(),
    val needsLogin: Boolean = false,
) {
    val line: ServerLine get() = lines.firstOrNull { it.id == lineId } ?: lines.first()

    fun session(): ServerSession = ServerSession(line.url, userId, token, embyServerId, name, id = id, ua = ua)

    /** Whether [url] is on one of this server's lines. */
    fun owns(url: String): Boolean = lines.any { url == it.url || url.startsWith(it.url.trimEnd('/') + "/") || url.startsWith(it.url.trimEnd('/') + "?") }
}

/** A server as the screens show it: configuration plus what was measured this run. */
data class ServerStatus(
    val config: ServerConfig,
    /** 0-based priority. */
    val order: Int,
    /** Measured round trip per line id; null value = unreachable, absent = not measured yet. */
    val lineLatency: Map<String, Long?> = emptyMap(),
    val version: String? = null,
    val health: Health = Health.Warn,
    val coolingUntil: Long = 0,
    val lastError: String? = null,
) {
    val id: String get() = config.id
    val name: String get() = config.name
    val session: ServerSession get() = config.session()
    val lineName: String get() = config.line.name
    val latencyMs: Long? get() = lineLatency[config.lineId]
    val measured: Boolean get() = config.lineId in lineLatency
    val enabled: Boolean get() = config.enabled
    /** Takes part in aggregation right now. */
    val aggregates: Boolean get() = config.enabled && config.aggregate
}

interface RegistryStore {
    fun load(): List<ServerConfig>
    fun save(servers: List<ServerConfig>)
}

class MemoryRegistryStore(initial: List<ServerConfig> = emptyList()) : RegistryStore {
    var saved: List<ServerConfig> = initial
    override fun load() = saved
    override fun save(servers: List<ServerConfig>) { saved = servers }
}

/** App-private preferences, one JSON list. */
class PrefsRegistryStore(context: Context) : RegistryStore {
    private val prefs = context.getSharedPreferences("servers", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
    private val serializer = ListSerializer(ServerConfig.serializer())

    override fun load(): List<ServerConfig> =
        prefs.getString(KEY, null)?.let { runCatching { json.decodeFromString(serializer, it) }.onFailure { e -> ZLog.w("Servers", "registry unreadable", e) }.getOrNull() }
            .orEmpty()

    override fun save(servers: List<ServerConfig>) {
        prefs.edit { putString(KEY, json.encodeToString(serializer, servers)) }
    }

    private companion object { const val KEY = "list" }
}

/**
 * The servers (aggregation spec §3.1, architecture: ServerRegistry): many servers in priority order,
 * each with lines, switches, sync policy and this run's health. Health combines speed tests with
 * fan-out results; a server that keeps failing cools down for five minutes.
 */
class ServerRegistry(
    private val store: RegistryStore,
    private val clock: () -> Long = System::currentTimeMillis,
    private val failures: FailureTracker = FailureTracker(clock),
    /** Runs a save; the app hands it to [Disk] so a change on screen never waits for the file. */
    private val persist: (() -> Unit) -> Unit = { it() },
) : HealthBook {
    private var configs: List<ServerConfig> = store.load().filter { it.lines.isNotEmpty() }.onEach { ZLog.secret(it.token) }
    private val latency = HashMap<String, Map<String, Long?>>()
    private val versions = HashMap<String, String>()

    private val _servers = MutableStateFlow(build())
    val servers: StateFlow<List<ServerStatus>> = _servers.asStateFlow()

    val all: List<ServerStatus> get() = _servers.value
    fun get(id: String): ServerStatus? = _servers.value.firstOrNull { it.id == id }
    fun session(id: String): ServerSession? = get(id)?.session
    /** Enabled servers, in priority order. */
    val enabled: List<ServerStatus> get() = all.filter { it.enabled }
    /** Servers that take part in aggregation, in priority order. */
    val aggregating: List<ServerStatus> get() = all.filter { it.aggregates }

    @Synchronized
    private fun build(): List<ServerStatus> = configs.mapIndexed { i, c ->
        val f = failures.state(c.id)
        val lines = latency[c.id].orEmpty()
        val cooling = f.coolingUntil > clock()
        val health = when {
            !c.enabled || cooling -> Health.Off
            c.lineId in lines && lines[c.lineId] == null -> Health.Off
            c.needsLogin || f.failures > 0 -> Health.Warn
            c.lineId !in lines -> Health.Warn
            (lines[c.lineId] ?: 0) > SLOW_MS -> Health.Warn
            else -> Health.Ok
        }
        ServerStatus(c, i, lines, versions[c.id], health, f.coolingUntil, f.lastError)
    }

    private fun publish() { _servers.value = build() }

    @Synchronized
    private fun change(update: (List<ServerConfig>) -> List<ServerConfig>) {
        configs = update(configs)
        val snapshot = configs
        persist { store.save(snapshot) }
        publish()
    }

    /** Adds [config], or when the same Emby server and user is already there, adds its line to that entry. */
    fun add(config: ServerConfig): ServerConfig {
        ZLog.secret(config.token)
        var result = config
        change { list ->
            val same = list.firstOrNull { existing ->
                existing.userId == config.userId && config.embyServerId != null && existing.embyServerId == config.embyServerId
            }
            if (same == null) {
                list + config
            } else {
                val newLines = config.lines.filter { l -> same.lines.none { it.url.equals(l.url, true) } }
                    .mapIndexed { i, l -> l.copy(id = newId(), name = l.name.takeUnless { it == "主线路" } ?: "线路 ${same.lines.size + i + 1}") }
                // The login just made tells what this server accepts now.
                result = same.copy(lines = same.lines + newLines, token = config.token, needsLogin = false, ua = if (same.ua.canFallBack) same.ua.copy(detected = config.ua.detected ?: same.ua.detected) else same.ua)
                list.map { if (it.id == same.id) result else it }
            }
        }
        return result
    }

    fun update(id: String, edit: (ServerConfig) -> ServerConfig) = change { list -> list.map { if (it.id == id) edit(it) else it } }

    /** A validated restore is published as one registry update. */
    fun restore(configs: List<ServerConfig>) {
        require(configs.map { it.id }.distinct().size == configs.size && configs.all { it.lines.isNotEmpty() })
        configs.forEach { ZLog.secret(it.token) }
        change { configs }
    }

    fun remove(id: String) {
        change { list -> list.filter { it.id != id } }
        synchronized(this) { latency.remove(id); versions.remove(id) }
        failures.reset(id)
        publish()
    }

    /** Moves a server one place up ([delta] −1) or down (+1) in priority. */
    fun move(id: String, delta: Int) = change { list ->
        val i = list.indexOfFirst { it.id == id }
        val j = i + delta
        if (i < 0 || j !in list.indices) list else list.toMutableList().apply { add(j, removeAt(i)) }
    }

    /** Auto found that [id] needs [mode]; a fixed choice is left alone. */
    fun recordIdentity(id: String, mode: UaMode) = update(id) { if (it.ua.canFallBack && it.ua.detected != mode) it.copy(ua = it.ua.copy(detected = mode)) else it }

    /** The server whose line [url] is on, if any (images, subtitles: requests that carry no session). */
    fun ownerOf(url: String): ServerStatus? = all.firstOrNull { it.config.owns(url) }

    fun selectLine(id: String, lineId: String) = update(id) { if (it.lines.any { l -> l.id == lineId }) it.copy(lineId = lineId) else it }

    fun addLine(id: String, name: String, url: String) = update(id) { it.copy(lines = it.lines + ServerLine(newId(), name, url)) }

    /** Records a speed test of one line; with auto-fastest, switches to the fastest reachable line once all are measured. */
    fun recordLatency(id: String, lineId: String, ms: Long?, version: String?) {
        synchronized(this) {
            latency[id] = latency[id].orEmpty() + (lineId to ms)
            if (version != null) versions[id] = version
        }
        // A ping only shows the server is reachable; a cool-down from failed library requests stays.
        val c = configs.firstOrNull { it.id == id }
        val measured = latency[id].orEmpty()
        if (c != null && c.autoFastest && c.lines.all { it.id in measured }) {
            val best = c.lines.filter { measured[it.id] != null }.minByOrNull { measured[it.id]!! }
            if (best != null && best.id != c.lineId) {
                ZLog.i("Servers", "${c.name}: fastest line is ${best.name}")
                update(id) { it.copy(lineId = best.id) }
                return
            }
        }
        publish()
    }

    override fun isCooling(serverId: String): Boolean = failures.isCooling(serverId)

    override fun onSuccess(serverId: String) {
        if (failures.state(serverId).failures == 0) return
        failures.success(serverId)
        publish()
    }

    override fun onFailure(serverId: String, error: Throwable, timedOut: Boolean) {
        ZLog.w("Servers", "${get(serverId)?.name ?: serverId}: ${if (timedOut) "request timed out" else "request failed"}: ${error.message ?: error.javaClass.simpleName}")
        if (failures.failure(serverId, error.message ?: error.javaClass.simpleName, timedOut)) {
            ZLog.w("Servers", "${get(serverId)?.name ?: serverId} failed twice in a row; skipped for ${FailureTracker.COOL_MS / 60_000} minutes")
        }
        publish()
    }

    /** Clears a cool-down (the user asked to retry). */
    fun retry(id: String) {
        failures.reset(id)
        publish()
    }

    companion object {
        const val SLOW_MS = 200L

        fun newId(): String = UUID.randomUUID().toString().take(8)

        /** The registry entry for a P0/P1 single login. */
        fun fromLegacy(s: ServerSession, id: String = newId()): ServerConfig {
            val line = ServerLine(newId(), "主线路", s.baseUrl)
            return ServerConfig(id, s.serverName ?: hostOf(s.baseUrl), listOf(line), line.id, s.userId, s.accessToken, embyServerId = s.serverId)
        }

        fun hostOf(url: String) = url.substringAfter("://").substringBefore('/').substringBefore(':')
    }
}
