package app.zmby.data

import app.zmby.log.LanServer
import app.zmby.log.ZLog
import app.zmby.net.HttpExchange
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.security.SecureRandom

/** A long setting typed on a phone instead of with the remote (ui-spec §2.13 用手机输入). */
enum class InputField(val source: String, val title: String, val label: String, val helpUrl: String? = null, val singleLine: Boolean = false) {
    TmdbToken("TMDB", "API 读取访问令牌", "令牌", helpUrl = "https://www.themoviedb.org/settings/api"),
    PublicMetaDbKey("PublicMetaDB", "API 密钥", "密钥"),
    UserAgent("客户端身份", "自定义 User-Agent", "User-Agent", singleLine = true),
    ;

    /** The TV panel's heading: 「TMDB API 读取访问令牌」. */
    val heading: String get() = if (this == UserAgent) title else "$source $title"
}

/** What the TV panel shows (2.13 状态胶囊). */
sealed interface InputState {
    data object Waiting : InputState
    data class Opened(val device: String, val count: Int) : InputState
    data object Verifying : InputState
    data object Saved : InputState
    data class Failed(val reason: String) : InputState
}

/**
 * One 用手机输入 page on the box's LAN server: `/input/<code>` with a one-time six-digit code (also
 * in the QR code), the same rules as the server import ([ImportGuard]: wrong codes are limited per
 * source and in total, a closed page needs a new code on the TV). The value travels once, in the body
 * of `POST /input/<code>`; the TV checks it with [validate] (null = good, else the reason shown on
 * both screens) and hands a good one to [save]. Nothing keeps it here, and the log only says that a
 * value was saved. Only accepted requests extend the ten minutes; status polls do not.
 */
class InputSession(
    private val lan: LanServer,
    val field: InputField,
    private val validate: suspend (String) -> String?,
    private val save: (String) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
    private val guard: ImportGuard = ImportGuard(clock),
    val code: String = (SecureRandom().nextInt(900_000) + 100_000).toString(),
) {
    @Volatile private var active = false
    private val _expiresAt = MutableStateFlow(clock() + ImportSession.SESSION_MS)
    val expiresAt: StateFlow<Long> = _expiresAt.asStateFlow()
    private val _state = MutableStateFlow<InputState>(InputState.Waiting)
    val state: StateFlow<InputState> = _state.asStateFlow()
    /** Too many wrong codes closed the page; the TV offers a new one. */
    private val _locked = MutableStateFlow(false)
    val locked: StateFlow<Boolean> = _locked.asStateFlow()
    private val clients = java.util.concurrent.ConcurrentHashMap<String, Pair<String, Long>>()
    private val busy = java.util.concurrent.atomic.AtomicBoolean(false)

    private fun valid() = active && clock() < _expiresAt.value
    private fun touch() { if (active) _expiresAt.value = clock() + ImportSession.SESSION_MS }

    /** Starts the page; returns the port. */
    fun open(): Int {
        val port = lan.start()
        active = true
        touch()
        lan.routePrefix(PREFIX, allowPost = true, handler = ::dispatch)
        ZLog.i(TAG, "input page for ${field.name} open on port $port")
        return port
    }

    fun close() {
        if (!active) return
        active = false
        lan.unroutePrefix(PREFIX)
        ZLog.i(TAG, "input page closed")
    }

    internal fun dispatch(ex: HttpExchange) {
        if (!valid()) return ex.sendText(410, "页面已失效，请在电视上重新打开")
        val source = ex.request.remote.hostAddress.orEmpty()
        guard.locked(source)?.let { return locked(ex, it.untilMs) }
        val rest = ex.request.path.trimEnd('/').removePrefix(PREFIX)
        val given = rest.substringBefore('/')
        val suffix = rest.substringAfter('/', "")
        when (val verdict = guard.check(source, ImportGuard.same(given, code))) {
            ImportGuard.Verdict.Ok -> Unit
            is ImportGuard.Verdict.Wrong -> {
                ZLog.w(TAG, "wrong input code from $source (${verdict.left} left)")
                return ex.sendText(403, "输入码不对，请核对电视上的六位数字。再错 ${verdict.left} 次将暂停这台设备的尝试。")
            }
            is ImportGuard.Verdict.Locked -> return locked(ex, verdict.untilMs)
            ImportGuard.Verdict.Closed -> {
                ZLog.w(TAG, "too many wrong input codes: page closed")
                ex.sendText(410, "错误次数过多，页面已关闭，请在电视上重新打开")
                close()
                _locked.value = true
                return
            }
        }
        when (suffix) {
            "" -> if (ex.request.method == "POST") submit(ex) else ex.sendText(200, PhoneInputPage.html(field), "text/html; charset=utf-8")
            "ping" -> ping(ex)
            else -> ex.sendText(404, "not found\n")
        }
    }

    private fun locked(ex: HttpExchange, until: Long) {
        val minutes = if (until == Long.MAX_VALUE) null else ((until - clock() + 59_999) / 60_000).coerceAtLeast(1)
        ex.sendText(429, if (minutes == null) "错误次数过多，页面已关闭，请在电视上重新打开" else "错误次数过多，请 $minutes 分钟后再试")
    }

    private fun deviceOf(ex: HttpExchange): String {
        val ua = ex.request.header("user-agent").orEmpty()
        return when { "iPhone" in ua -> "iPhone"; "iPad" in ua -> "iPad"; "Android" in ua && "Mobile" in ua -> "Android 手机"; "Android" in ua -> "Android 平板"; "Macintosh" in ua -> "Mac"; "Windows" in ua -> "Windows"; else -> "浏览器" }
    }

    /** Status for the page and the TV's 「iPhone 已打开页面」; does not extend the session. */
    private fun ping(ex: HttpExchange) {
        val ua = ex.request.header("user-agent").orEmpty()
        clients[ex.request.remote.hostAddress.orEmpty() + ua] = deviceOf(ex) to clock()
        clients.entries.removeIf { clock() - it.value.second > 30_000 }
        val devices = clients.values.map { it.first }
        if (_state.value is InputState.Waiting || _state.value is InputState.Opened) {
            _state.value = if (devices.isEmpty()) InputState.Waiting else InputState.Opened(devices.first(), devices.size)
        }
        val body = buildJsonObject {
            put("device", android.os.Build.MODEL ?: "电视")
            put("remaining", ((_expiresAt.value - clock()) / 1000).coerceAtLeast(0))
            put("saved", _state.value == InputState.Saved)
        }
        ex.sendText(200, body.toString(), "application/json; charset=utf-8")
    }

    /** The one request that carries the value: checked, saved when good, never logged. */
    private fun submit(ex: HttpExchange) {
        val bytes = ex.readBody(MAX_BYTES) ?: return ex.sendText(413, "内容过长")
        val value = clean(bytes.toString(Charsets.UTF_8), field)
        if (value.isEmpty()) return ex.sendText(400, reply(false, "内容为空"), "application/json; charset=utf-8")
        if (!busy.compareAndSet(false, true)) return ex.sendText(409, reply(false, "正在验证上一次提交，请稍候"), "application/json; charset=utf-8")
        try {
            touch()
            _state.value = InputState.Verifying
            val reason = try { runBlocking { validate(value) } } catch (e: Exception) { "暂时无法验证，请稍后重试" }
            if (reason != null) {
                _state.value = InputState.Failed(reason)
                ZLog.i(TAG, "${field.name}: value refused ($reason)")
                return ex.sendText(200, reply(false, reason), "application/json; charset=utf-8")
            }
            save(value)
            _state.value = InputState.Saved
            ZLog.i(TAG, "${field.name}: 已保存")
            ex.sendText(200, reply(true, okMessage(field)), "application/json; charset=utf-8")
        } finally {
            busy.set(false)
        }
    }

    private fun reply(ok: Boolean, message: String) = buildJsonObject { put("ok", ok); put("message", message) }.toString()

    companion object {
        private const val TAG = "Input"
        private const val PREFIX = "/input/"
        /** Far more than a TMDB token (about 250 characters) or a User-Agent needs. */
        const val MAX_BYTES = 4096

        /** Trims the value; one-line fields keep only their first line, and tokens lose inner whitespace. */
        fun clean(raw: String, field: InputField): String = when (field) {
            InputField.UserAgent -> raw.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }.orEmpty()
            else -> raw.filterNot { it.isWhitespace() }
        }

        fun okMessage(field: InputField) = when (field) {
            InputField.TmdbToken -> "验证通过，已保存到电视"
            else -> "已保存到电视"
        }

        /** A User-Agent the HTTP client accepts: printable ASCII, at most 512 characters. */
        fun checkUserAgent(value: String): String? = when {
            value.length > 512 -> "太长了（最多 512 个字符）"
            value.any { it.code !in 0x20..0x7e } -> "只能包含英文字母、数字和常见符号"
            else -> null
        }
    }
}
