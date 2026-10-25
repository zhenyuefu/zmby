package app.zmby.ui

import app.zmby.emby.EmbyException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * An error as the screen shows it: short, in Chinese, and without URLs or paths (the UI shows no
 * slashes; the full message is in the log). Messages the app wrote for the user pass through.
 */
fun Throwable.userMessage(): String = when (this) {
    is EmbyException -> when (code) {
        null -> message?.takeIf { USER_TEXT.containsMatchIn(it) }?.let(::clean) ?: "服务器返回了无法识别的内容"
        400 -> "服务器拒绝了请求（400）"
        401 -> "用户名或密码不对，或登录已失效（401）"
        403 -> "服务器不允许这个操作（403）"
        404 -> "服务器上没有这个条目（404）"
        405 -> "服务器不支持这个操作（405）"
        in 500..599 -> "服务器出错（$code）"
        else -> "服务器返回 $code"
    }
    is UnknownHostException -> "找不到这个服务器地址"
    is ConnectException, is NoRouteToHostException -> "连不上服务器"
    is InterruptedIOException -> "连接超时"
    is SSLException -> "安全连接失败"
    is kotlinx.serialization.SerializationException -> "服务器返回了无法识别的内容"
    is IllegalArgumentException -> if (message?.contains("URL", ignoreCase = true) == true) "地址格式不对" else clean(message ?: "参数不对")
    else -> message?.takeIf { USER_TEXT.containsMatchIn(it) }?.let(::clean) ?: "出错了（${javaClass.simpleName}）"
}

/** Messages written for the user contain Chinese. */
private val USER_TEXT = Regex("[\\u4e00-\\u9fff]")

private fun clean(s: String) = s.replace('/', ' ').replace('\\', ' ').take(120)
