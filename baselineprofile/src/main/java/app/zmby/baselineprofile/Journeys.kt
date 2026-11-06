package app.zmby.baselineprofile

import android.view.KeyEvent
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until

internal const val PACKAGE = "app.zmby"

/**
 * The remote-control paths a profile should cover, on the mock servers of tools/mock_emby.py
 * (`--multi`): home rows, a film's detail sections, a series' episode row, the 电影 grid and pinyin
 * search. Each starts from a cold start on home, so one path going astray does not take the next
 * with it. Keys go through the instrumentation, one at a time, with a short wait so each screen draws.
 */
internal fun MacrobenchmarkScope.journeys() {
    // Home: into 在看, along it and back.
    home()
    key(KeyEvent.KEYCODE_DPAD_DOWN)
    repeat(3) { key(KeyEvent.KEYCODE_DPAD_RIGHT) }
    repeat(3) { key(KeyEvent.KEYCODE_DPAD_LEFT) }
    key(KeyEvent.KEYCODE_DPAD_DOWN)
    repeat(4) { key(KeyEvent.KEYCODE_DPAD_RIGHT) }
    // A film (沙丘2, third in 在看): its detail sections down and up.
    restart()
    key(KeyEvent.KEYCODE_DPAD_DOWN)
    repeat(2) { key(KeyEvent.KEYCODE_DPAD_RIGHT) }
    key(KeyEvent.KEYCODE_DPAD_CENTER, waitMs = 5_000)
    repeat(5) { key(KeyEvent.KEYCODE_DPAD_DOWN) }
    repeat(6) { key(KeyEvent.KEYCODE_DPAD_UP) }
    // A series (漫长的季节, first in 在看, through its menu: OK may resume playback): the episode row.
    restart()
    key(KeyEvent.KEYCODE_DPAD_DOWN)
    key(KeyEvent.KEYCODE_MENU)
    key(KeyEvent.KEYCODE_DPAD_CENTER, waitMs = 5_000)
    key(KeyEvent.KEYCODE_DPAD_DOWN)
    repeat(4) { key(KeyEvent.KEYCODE_DPAD_RIGHT, waitMs = 800) }
    repeat(2) { key(KeyEvent.KEYCODE_DPAD_DOWN) }
    // 电影 from the side bar: down a page and back.
    restart()
    key(KeyEvent.KEYCODE_MENU)
    repeat(2) { key(KeyEvent.KEYCODE_DPAD_DOWN) }
    key(KeyEvent.KEYCODE_DPAD_CENTER, waitMs = 3_000)
    repeat(8) { key(KeyEvent.KEYCODE_DPAD_DOWN) }
    repeat(8) { key(KeyEvent.KEYCODE_DPAD_UP) }
    // Search from the side bar (above 首页): s q on the pinyin keyboard.
    restart()
    key(KeyEvent.KEYCODE_MENU)
    key(KeyEvent.KEYCODE_DPAD_UP)
    key(KeyEvent.KEYCODE_DPAD_CENTER, waitMs = 2_000)
    repeat(3) { key(KeyEvent.KEYCODE_DPAD_DOWN) }
    key(KeyEvent.KEYCODE_DPAD_CENTER)
    repeat(4) { key(KeyEvent.KEYCODE_DPAD_RIGHT) }
    key(KeyEvent.KEYCODE_DPAD_UP)
    key(KeyEvent.KEYCODE_DPAD_CENTER, waitMs = 4_000)
}

/** A cold start on home for the next path. */
private fun MacrobenchmarkScope.restart() {
    killProcess()
    home()
}

/** Starts zmby and waits until home has drawn; a fresh install first gets the mock servers. */
internal fun MacrobenchmarkScope.home() {
    pressHome()
    startActivityAndWait()
    device.wait(Until.hasObject(By.pkg(PACKAGE).depth(0)), 10_000)
    Thread.sleep(3_000)
    if (device.hasObject(By.text("扫码导入"))) {
        importMockServers()
        pressHome()
        startActivityAndWait()
        Thread.sleep(5_000)
    }
}

/**
 * The five servers of `tools/mock_emby.py --multi` on the host (10.0.2.2 from an emulator), added
 * through zmby's own 扫码导入 page on 127.0.0.1:8790, as the emulator guide does from the Mac.
 */
private fun MacrobenchmarkScope.importMockServers() {
    device.findObject(By.text("扫码导入")).click()
    val address = device.wait(Until.findObject(By.textContains("/import/")), 10_000)?.text ?: error("no import page")
    val code = address.substringAfterLast("/import/").trim()
    val body = listOf(
        "http://10.0.2.2:8096 me pw 家里 NAS", "http://10.0.2.2:8098 me pw 朋友的服", "http://10.0.2.2:8099 me pw 公益服 A",
        "http://10.0.2.2:8100 me pw 公益服 B", "http://10.0.2.2:8101 me pw 无 ID 服",
    ).joinToString("\n")
    // A plain socket: the platform's cleartext policy covers its HTTP stack, and this is loopback.
    val payload = body.toByteArray()
    java.net.Socket("127.0.0.1", 8790).use { socket ->
        socket.soTimeout = 60_000
        val out = socket.getOutputStream()
        out.write(("POST /import/$code HTTP/1.1\r\nHost: 127.0.0.1\r\nContent-Type: text/plain; charset=utf-8\r\n" +
            "Content-Length: ${payload.size}\r\nConnection: close\r\n\r\n").toByteArray())
        out.write(payload)
        out.flush()
        val status = socket.getInputStream().bufferedReader().readLine().orEmpty()
        check(" 200 " in status) { "import answered '$status'" }
    }
}

private fun MacrobenchmarkScope.key(code: Int, waitMs: Long = 500) {
    device.pressKeyCode(code)
    device.waitForIdle(waitMs)
    Thread.sleep(waitMs)
}
