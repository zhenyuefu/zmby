package app.zmby.backup

import android.os.Build
import app.zmby.ZmbyApp
import app.zmby.data.*
import app.zmby.log.ZLog
import app.zmby.store.DataSourceRow
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

/** File decoding and token verification finish before publishing one new registry snapshot. */
class BackupRepository(private val app: ZmbyApp) {
    private val lock = Mutex()
    val serverCount get() = app.registry.all.size
    val serverIds get() = app.registry.all.map { it.id }.toSet()
    fun restoredConfig(row: RestoreRow): ServerConfig? = app.registry.all.map { it.config }.firstOrNull { c ->
        c.id == row.existingId || c.id == row.incoming.config.id ||
            row.incoming.config.embyServerId?.let { it == c.embyServerId && c.userId == row.incoming.config.userId } == true
    }
    fun snapshot(): BackupContent = BackupContent(app.registry.all.map { status ->
        val (home,search) = app.library.backupSwitches(status.id)
        val caps = app.capsStore.get(status.session.capsKey)
        BackupServer(status.config, home, search, caps.relay.name, caps.pinyinOverride.name, SourceFeature.entries.filter { !it.ordered }.associate { it.key to app.sources.enabled(it,status.id) })
    }, app.settings.backupValues(), providers = app.sources.providers().map { BackupProvider(it.provider.key,it.enabled,it.order) })

    suspend fun export(options: BackupOptions, password: String): String = withContext(Dispatchers.IO) {
        BackupCodec.encode(snapshot(), options, password, Build.MODEL)
    }

    fun decode(text: String, password: String): BackupContent {
        if (!text.trimStart().startsWith("[")) return BackupCodec.decode(text, password)
        val rows = ServerImport.parse(text).groupBy { it.name to it.user }.map { (_,lines) ->
            val first = lines.first()
            val entries = lines.distinctBy { it.url }.map { ServerLine(ServerRegistry.newId(), it.lineName ?: "线路", ConnectionText.normalizeUrl(it.url) ?: error("备份地址无效")) }
            BackupServer(ServerConfig(ServerRegistry.newId(), first.name ?: ServerRegistry.hostOf(first.url), entries, entries.first().id, "", "", first.user, needsLogin = true))
        }
        require(rows.isNotEmpty() && rows.size <= 500) { "备份中没有可识别的服务器" }
        return BackupContent(rows, options = BackupOptions(tokens = false, serverSettings = false))
    }

    fun preview(content: BackupContent): RestorePlan = RestorePlanner.plan(app.registry.all.map { it.config }, content)

    suspend fun restore(content: BackupContent, mode: RestoreMode, replaceConfirmed: Boolean = false): RestorePlan = withContext(Dispatchers.IO) {
        require(mode != RestoreMode.Replace || replaceConfirmed) { "请再次确认全部替换" }
        lock.withLock {
            val existing = app.registry.all.map { it.config }
            val plan = RestorePlanner.plan(existing, content)
            val usedIds = existing.map { it.id }.toMutableSet()
            val output = mutableListOf<ServerConfig>()
            for (row in plan.rows) {
                val incoming = row.incoming.config
                val old = existing.firstOrNull { it.id == row.existingId }
                var config = if (old == null) {
                    incoming.copy(id = incoming.id.takeUnless { it in usedIds } ?: ServerRegistry.newId())
                } else {
                    RestorePlanner.merge(old,incoming,content.options.serverSettings)
                }
                // An existing valid token wins. An incoming token is only tried if it did not work.
                var valid = verify(config)
                if (!valid && incoming.token.isNotBlank() && incoming.userId.isNotBlank() && (incoming.token != config.token || incoming.userId != config.userId)) {
                    val replacement = config.copy(token = incoming.token, userId = incoming.userId)
                    if (verify(replacement)) { config = replacement; valid = true }
                }
                config = config.copy(needsLogin = !valid)
                ZLog.secret(config.token)
                usedIds += config.id
                output += config
            }
            val restored = if (mode == RestoreMode.Replace) output else existing.map { old -> output.firstOrNull { it.id == old.id } ?: old } + output.filter { row -> existing.none { it.id == row.id } }
            app.registry.restore(restored)
            if (mode == RestoreMode.Replace) existing.filter { old -> restored.none { it.id == old.id } }.forEach { app.store.deleteServer(it.id) }
            if(content.options.serverSettings) output.zip(plan.rows).forEach { (config,row) ->
                app.library.restoreSwitches(config.id, row.incoming.homeOff, row.incoming.searchOff)
                app.sources.restore(row.incoming.sources.mapNotNull { (key,enabled) ->
                    SourceFeature.entries.firstOrNull { it.key == key && !it.ordered }?.let { DataSourceRow(key,config.id,enabled) }
                })
                app.capsStore.update(config.session().capsKey) { it.copy(relay = Override.valueOf(row.incoming.relay), pinyinOverride = Override.valueOf(row.incoming.pinyin)) }
            }
            if (content.options.appSettings) {
                val ids = plan.rows.zip(output).associate { (row,config) -> row.incoming.config.id to config.id }
                val restoredSettings = content.settings.toMutableMap()
                (restoredSettings["home_source"] as? JsonPrimitive)?.content?.let { id -> ids[id]?.let { restoredSettings["home_source"] = JsonPrimitive(it) } }
                (restoredSettings["browse_queries"] as? JsonPrimitive)?.content?.let { encoded ->
                    runCatching { Json.parseToJsonElement(encoded).jsonObject }.getOrNull()?.let { queries ->
                        restoredSettings["browse_queries"] = JsonPrimitive(JsonObject(queries.mapKeys { (key,_) -> (ids[key.substringBefore('|')] ?: key.substringBefore('|')) + "|" + key.substringAfter('|',"") }).toString())
                    }
                }
                app.settings.restoreValues(JsonObject(restoredSettings))
                app.sources.restore(content.providers.filter { p -> SegmentProvider.entries.any { it.key == p.key } }.map { DataSourceRow(SourceFeature.Segments.key,it.key,it.enabled,it.order.coerceIn(0,10000)) })
            }
            plan
        }
    }

    private suspend fun verify(config: ServerConfig): Boolean {
        if (config.token.isBlank() || config.userId.isBlank()) return false
        return withTimeoutOrNull(8_000) {
            try { app.emby.verifyUser(config.session()) }
            catch (e: CancellationException) { throw e } catch (_: Exception) { false }
        } ?: false
    }
}
