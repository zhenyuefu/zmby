package com.android.gallery3d.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.view.KeyEvent
import android.widget.TextView
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.concurrent.thread

/**
 * Pretends to be the Zidoo player: "plays" the given URL in real time from the requested
 * position, mirrors that state into tools/mock_emby.py's fake 9529 API, and returns the MX-style
 * result. Back = stop by user; OK = jump to the end (playback_completion); Left/Right = seek ±60 s;
 * Play/Pause (or Space) = pause and resume. The mock's address comes from the debug property
 * `debug.zmby.fake_zidoo_base` (default `http://10.0.2.2:8096`), so parallel mocks on other ports work.
 * Seeks sent to the mock's 9529 API (zmby's skips) are taken over, and reaching the end finishes as
 * playback_completion like the real player. `debug.zmby.fake_zidoo_duration` (ms) shortens the video.
 */
class MovieActivity : Activity() {
    private var basePosition = 0L
    private var startedAt = 0L
    private var paused = false
    private val mockBase by lazy {
        runCatching { Runtime.getRuntime().exec(arrayOf("getprop", "debug.zmby.fake_zidoo_base")).inputStream.bufferedReader().readText().trim() }
            .getOrNull()?.takeIf { it.startsWith("http") }?.trimEnd('/') ?: "http://10.0.2.2:8096"
    }
    private val duration by lazy {
        runCatching { Runtime.getRuntime().exec(arrayOf("getprop", "debug.zmby.fake_zidoo_duration")).inputStream.bufferedReader().readText().trim().toLong() }
            .getOrNull()?.takeIf { it > 10_000 } ?: 9_960_000L
    }
    @Volatile private var mockPosition: Long? = null
    private lateinit var label: TextView

    private val position get() = if (paused) basePosition else (basePosition + SystemClock.uptimeMillis() - startedAt).coerceAtMost(duration)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val fromStart = intent.getBooleanExtra("from_start", true)
        basePosition = if (fromStart) 0L else intent.getIntExtra("position", 0).toLong()
        startedAt = SystemClock.uptimeMillis()
        label = TextView(this).apply { textSize = 28f; gravity = Gravity.CENTER; setTextColor(0xFFFFFFFF.toInt()) }
        setContentView(label)
        mirror(open = true)
        tick()
    }

    private fun tick() {
        // zmby seeks through the mock's 9529 API: follow it.
        mockPosition?.let { m ->
            mockPosition = null
            if (kotlin.math.abs(m - position) > 2_000) {
                basePosition = m.coerceIn(0, duration)
                startedAt = SystemClock.uptimeMillis()
            }
        }
        if (!paused && position >= duration && !isFinishing) return finishWith(null, "playback_completion")
        poll()
        label.text = "FAKE ZIDOO PLAYER${if (paused) " (paused)" else ""}\n${intent.getStringExtra("title")}\n${position / 1000}s / ${duration / 1000}s\n" +
            "return_result=${intent.getBooleanExtra("return_result", false)}\n${intent.data}"
        label.postDelayed({ if (!isFinishing) tick() }, 500)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_BACK -> finishWith(position, "user")
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> finishWith(null, "playback_completion")
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_LEFT -> {
                basePosition = (position + if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) 60_000 else -60_000).coerceAtLeast(0)
                startedAt = SystemClock.uptimeMillis()
                mirror(open = true)
            }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_SPACE -> {
                basePosition = position
                startedAt = SystemClock.uptimeMillis()
                paused = !paused
                mirror(open = true)
            }
            else -> return super.onKeyDown(keyCode, event)
        }
        return true
    }

    private fun finishWith(pos: Long?, endBy: String) {
        val data = Intent("com.mxtech.intent.result.VIEW").putExtra("end_by", endBy).putExtra("duration", duration.toInt())
        if (pos != null) data.putExtra("position", pos.toInt())
        setResult(RESULT_OK, data)
        mirror(open = false)
        finish()
    }

    private fun poll() {
        thread {
            runCatching {
                val body = (URL("$mockBase/ZidooVideoPlay/getPlayStatus").openConnection() as HttpURLConnection).run {
                    connectTimeout = 1000; readTimeout = 1000
                    inputStream.bufferedReader().readText().also { disconnect() }
                }
                Regex("\"currentPosition\":\\s*(\\d+)").find(body)?.groupValues?.get(1)?.toLong()?.let { mockPosition = it }
            }
        }
    }

    /** Tell the mock's fake 9529 API what is "playing" (the app polls it via adb reverse). */
    private fun mirror(open: Boolean) {
        val path = URLEncoder.encode(intent.data.toString(), "UTF-8")
        val url = "$mockBase/_mock/zidoo?open=${if (open) 1 else 0}&path=$path&position=$position&duration=$duration&playing=${if (paused) 0 else 1}"
        thread { runCatching { (URL(url).openConnection() as HttpURLConnection).run { connectTimeout = 2000; inputStream.close(); disconnect() } } }
    }
}
