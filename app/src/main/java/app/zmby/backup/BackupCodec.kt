package app.zmby.backup

import app.zmby.data.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

@Serializable
data class BackupOptions(val accounts: Boolean = true, val tokens: Boolean = true, val serverSettings: Boolean = true, val appSettings: Boolean = false)
@Serializable
data class BackupServer(val config: ServerConfig, val homeOff: List<String> = emptyList(), val searchOff: List<String> = emptyList(), val relay: String = "Auto", val pinyin: String = "Auto", val sources: Map<String,Boolean> = emptyMap())
@Serializable
data class BackupProvider(val key: String, val enabled: Boolean, val order: Int)

@Serializable
data class BackupContent(val servers: List<BackupServer>, val settings: JsonObject = JsonObject(emptyMap()), val options: BackupOptions = BackupOptions(), val providers: List<BackupProvider> = emptyList())
@Serializable
data class BackupHeader(val format: String = "zmby-backup", val version: Int = 1, val createdAt: Long, val device: String, val serverCount: Int, val parts: BackupOptions)
@Serializable
data class BackupEncryption(val salt: String, val nonce: String, val iterations: Int = 310_000, val algorithm: String = "PBKDF2-HMAC-SHA256/AES-256-GCM")
@Serializable
private data class Envelope(val header: BackupHeader, val encryption: BackupEncryption? = null, val body: JsonElement)

/** Portable, authenticated backups. No password, token or plaintext is written to logs. */
object BackupCodec {
    const val MAX_BYTES = 8 * 1024 * 1024
    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val b64 = Base64.getEncoder()
    private val un64 = Base64.getDecoder()
    private val random = SecureRandom()

    fun encode(content: BackupContent, options: BackupOptions, password: String, device: String, now: Long = System.currentTimeMillis()): String {
        val sanitized = content.copy(servers = content.servers.map { row ->
            val c = row.config
            val clean = if (options.serverSettings) c else ServerConfig(c.id, c.name, c.lines, c.lineId, c.userId, c.token, c.userName, c.embyServerId)
            row.copy(config = clean.copy(token = if (options.tokens) c.token else "", userId = if (options.accounts || options.tokens) c.userId else "", userName = c.userName.takeIf { options.accounts }),
                homeOff = row.homeOff.takeIf { options.serverSettings }.orEmpty(), searchOff = row.searchOff.takeIf { options.serverSettings }.orEmpty(),
                relay = if (options.serverSettings) row.relay else "Auto", pinyin = if (options.serverSettings) row.pinyin else "Auto", sources = row.sources.takeIf { options.serverSettings }.orEmpty())
        }, settings = if (options.appSettings) content.settings else JsonObject(emptyMap()), options = options, providers = content.providers.takeIf { options.appSettings }.orEmpty())
        val containsCredentials = sanitized.servers.any { it.config.token.isNotBlank() } || sanitized.settings.any { (k,v) -> (k.contains("token") || k.contains("key")) && v.jsonPrimitive.content.isNotBlank() }
        require(!options.tokens || password.length >= 8) { "包含令牌时，备份密码至少需要 8 位" }
        require(!containsCredentials || password.length >= 8) { "包含凭据时，备份密码至少需要 8 位" }
        require(password.isEmpty() || password.length >= 8) { "备份密码至少需要 8 位" }
        validate(sanitized)
        val header = BackupHeader(createdAt = now, device = device.take(120), serverCount = sanitized.servers.size, parts = options)
        val payload = json.encodeToString(sanitized)
        val envelope = if (password.isEmpty()) Envelope(header, body = json.parseToJsonElement(payload)) else {
            val params = BackupEncryption(b64.encodeToString(ByteArray(16).also(random::nextBytes)), b64.encodeToString(ByteArray(12).also(random::nextBytes)))
            val cipher = cipher(Cipher.ENCRYPT_MODE, password, params, header)
            Envelope(header, params, JsonPrimitive(b64.encodeToString(cipher.doFinal(payload.toByteArray(Charsets.UTF_8)))))
        }
        return json.encodeToString(envelope).also { require(it.toByteArray().size <= MAX_BYTES) { "备份内容过大" } }
    }

    fun header(text: String): Pair<BackupHeader, Boolean> {
        val e = envelope(text)
        return e.header to (e.encryption != null)
    }

    fun decode(text: String, password: String): BackupContent {
        val e = envelope(text)
        val payload = if (e.encryption == null) e.body.toString() else try {
            val bytes = un64.decode(e.body.jsonPrimitive.content)
            cipher(Cipher.DECRYPT_MODE, password, e.encryption, e.header).doFinal(bytes).toString(Charsets.UTF_8)
        } catch (_: Exception) { throw IllegalArgumentException("密码不对或备份已损坏") }
        val content = try { json.decodeFromString<BackupContent>(payload) } catch (_: Exception) { throw IllegalArgumentException("备份内容无法识别") }
        validate(content)
        require(content.servers.size == e.header.serverCount) { "备份数量不一致" }
        require(e.encryption != null || content.servers.none { it.config.token.isNotBlank() } && content.settings.none { (k,v) -> (k.contains("token") || k.contains("key")) && v.jsonPrimitive.content.isNotBlank() }) { "凭据必须加密" }
        return content.copy(options = e.header.parts)
    }

    private fun envelope(text: String): Envelope {
        require(text.toByteArray().size <= MAX_BYTES) { "备份文件过大" }
        val e = try { json.decodeFromString<Envelope>(text) } catch (_: Exception) { throw IllegalArgumentException("不支持的备份文件") }
        require(e.header.format == "zmby-backup" && e.header.version == 1) { "不支持的备份版本" }
        require(e.header.serverCount in 0..500) { "备份数量异常" }
        e.encryption?.let {
            require(it.algorithm == "PBKDF2-HMAC-SHA256/AES-256-GCM" && it.iterations in 310_000..1_000_000) { "不支持的加密参数" }
            require(runCatching { un64.decode(it.salt).size == 16 && un64.decode(it.nonce).size == 12 }.getOrDefault(false)) { "加密参数异常" }
        }
        return e
    }

    private fun cipher(mode: Int, password: String, params: BackupEncryption, header: BackupHeader): Cipher {
        val spec = PBEKeySpec(password.toCharArray(), un64.decode(params.salt), params.iterations, 256)
        val key = try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded } finally { spec.clearPassword() }
        return try { Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(128, un64.decode(params.nonce)))
            updateAAD((json.encodeToString(header) + json.encodeToString(params)).toByteArray(Charsets.UTF_8))
        } } finally { key.fill(0) }
    }

    private fun validate(content: BackupContent) {
        require(content.servers.size <= 500) { "备份数量过多" }
        require(content.servers.map { it.config.id }.distinct().size == content.servers.size) { "备份服务器 ID 重复" }
        content.servers.forEach { row ->
            val c = row.config
            require(c.id.isNotBlank() && c.id != "catalog" && c.id.length <= 128 && c.lines.size in 1..64 && c.lines.map { it.id }.distinct().size == c.lines.size && c.lines.any { it.id == c.lineId }) { "备份线路无效" }
            require(c.lines.all { ConnectionText.normalizeUrl(it.url) != null }) { "备份包含无效地址" }
            require(row.relay in Override.entries.map { it.name } && row.pinyin in Override.entries.map { it.name }) { "备份设置无效" }
        }
    }
}

@Serializable
enum class RestoreMode { Merge, Replace }
/** What restoring one backed-up server does; the wording lives with the UI ([app.zmby.data.label]). */
@Serializable
enum class RestoreAction { Add, UpdateLines, Same }
@Serializable
data class RestoreRow(val incoming: BackupServer, val existingId: String?, val action: RestoreAction, val needsLogin: Boolean = false)
@Serializable
data class RestorePlan(val rows: List<RestoreRow>) {
    val added get() = rows.count { it.action == RestoreAction.Add }
    val updated get() = rows.count { it.action == RestoreAction.UpdateLines }
    val skipped get() = rows.count { it.action == RestoreAction.Same }
}

object RestorePlanner {
    /** Preserve the selected line and local identity while unioning newly imported addresses. */
    fun merge(old: ServerConfig, incoming: ServerConfig, settings: Boolean): ServerConfig {
        val fresh = incoming.lines.filter { b -> old.lines.none { a -> a.url.trimEnd('/').equals(b.url.trimEnd('/'),true) } }
            .map { it.copy(id = ServerRegistry.newId()) }
        return (if(settings) incoming else old).copy(id = old.id,lines = old.lines + fresh,lineId = old.lineId,
            token = old.token,userId = old.userId,userName = incoming.userName ?: old.userName,embyServerId = incoming.embyServerId ?: old.embyServerId)
    }

    fun plan(existing: List<ServerConfig>, content: BackupContent): RestorePlan {
        val used = mutableSetOf<String>()
        return RestorePlan(content.servers.map { row ->
            val c = row.config
            val same = existing.firstOrNull { it.id !in used && c.embyServerId != null && c.embyServerId == it.embyServerId && c.userId.isNotBlank() && c.userId == it.userId }
                ?: existing.firstOrNull { it.id !in used && (c.userId.isBlank() || c.userId == it.userId) && it.lines.any { a -> c.lines.any { b -> a.url.trimEnd('/').equals(b.url.trimEnd('/'), true) } } }
            same?.let { used += it.id }
            RestoreRow(row, same?.id, when {
                same == null -> RestoreAction.Add
                c.lines.any { b -> same.lines.none { a -> a.url.trimEnd('/').equals(b.url.trimEnd('/'), true) } } -> RestoreAction.UpdateLines
                else -> RestoreAction.Same
            }, needsLogin = if(same == null) c.token.isBlank() else same.needsLogin && c.token.isBlank())
        })
    }
}
