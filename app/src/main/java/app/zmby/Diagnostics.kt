package app.zmby

import android.app.Activity
import android.app.Application
import android.content.pm.ApplicationInfo
import android.os.StrictMode
import android.os.strictmode.Violation
import androidx.metrics.performance.JankStats
import androidx.metrics.performance.PerformanceMetricsState
import app.zmby.log.ZLog
import java.util.concurrent.Executors

/**
 * Debug-build watchdogs (architecture: 线程与性能约定 › 监测): StrictMode reports disk and network
 * access on the main thread, JankStats reports frames over [LONG_FRAME_MS]. Both go to the LAN log,
 * so the box can be checked remotely; release builds install neither.
 */
object Diagnostics {
    private const val TAG = "Perf"
    /** A frame longer than two 60 Hz frames is a visible hitch. */
    const val LONG_FRAME_MS = 33L
    /** Long frames are summed up per window instead of logged one by one. */
    private const val WINDOW_MS = 5_000L

    private val listener = Executors.newSingleThreadExecutor { r -> Thread(r, "zmby-strictmode").apply { isDaemon = true } }
    @Volatile private var enabled = false

    fun install(app: Application) {
        enabled = app.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        if (!enabled) return
        StrictMode.setThreadPolicy(
            StrictMode.ThreadPolicy.Builder()
                .detectDiskReads()
                .detectDiskWrites()
                .detectNetwork()
                .detectCustomSlowCalls()
                .penaltyListener(listener) { v -> report("main thread", v) }
                .build(),
        )
        StrictMode.setVmPolicy(
            StrictMode.VmPolicy.Builder()
                .detectLeakedClosableObjects()
                .detectLeakedSqlLiteObjects()
                .penaltyListener(listener) { v -> report("vm", v) }
                .build(),
        )
        ZLog.i(TAG, "StrictMode on (main-thread disk and network, leaked closables)")
    }

    private fun report(kind: String, v: Violation) {
        // The frames that show where it came from: ours first, then the framework's top.
        val frames = v.stackTrace
        val ours = frames.filter { it.className.startsWith("app.zmby") }.take(6)
        // Without app frames, skip the plumbing (StrictMode, libcore, streams) to show who wrote: e.g.
        // ActivityThread.handleDumpGfxInfo is `dumpsys gfxinfo` itself, not the app.
        val plumbing = listOf("android.os.StrictMode", "libcore.", "java.io.", "com.android.internal.util.FastPrintWriter", "android.system.")
        val others = frames.filterNot { f -> plumbing.any { f.className.startsWith(it) } }.take(6)
        val shown = (ours.ifEmpty { others.ifEmpty { frames.take(6).toList() } }).joinToString(" < ") { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" }
        ZLog.w("StrictMode", "$kind ${v.javaClass.simpleName}: ${v.message ?: ""} at $shown")
    }

    /** Frame timing of [activity]'s window; long frames are logged per [WINDOW_MS] with the screen they were on. */
    fun track(activity: Activity): JankStats? {
        if (!enabled) return null
        var windowStart = 0L
        var frames = 0
        var long = 0
        var worst = 0L
        var states = emptySet<String>()
        return runCatching {
            JankStats.createAndTrack(activity.window) { frame ->
                val ms = frame.frameDurationUiNanos / 1_000_000
                val now = frame.frameStartNanos / 1_000_000
                if (windowStart == 0L) windowStart = now
                frames++
                if (ms > LONG_FRAME_MS) {
                    long++
                    if (ms > worst) worst = ms
                    states = states + frame.states.map { "${it.key}=${it.value}" }
                }
                if (now - windowStart >= WINDOW_MS) {
                    if (long > 0) ZLog.w(TAG, "$long of $frames frames over $LONG_FRAME_MS ms (worst $worst ms) ${states.joinToString(" ")}")
                    windowStart = now; frames = 0; long = 0; worst = 0; states = emptySet()
                }
            }
        }.onFailure { ZLog.w(TAG, "JankStats unavailable", it) }.getOrNull()
    }

    @Volatile private var homeShown = false

    /**
     * The first time home has something to show (rows are read from the local store): logs the time
     * since the process started and reports the activity fully drawn, so `am start -W` and the
     * system's "Fully drawn" line measure cold start to home (architecture: 冷启动 2 秒内). Both builds.
     */
    fun homeShown(activity: Activity) {
        if (homeShown) return
        homeShown = true
        val ms = android.os.SystemClock.elapsedRealtime() - android.os.Process.getStartElapsedRealtime()
        ZLog.i(TAG, "home shown $ms ms after process start")
        runCatching { activity.reportFullyDrawn() }
    }

    /** Tags frames with the screen shown, so a long frame says where it happened. */
    fun screen(activity: Activity, name: String) {
        if (!enabled) return
        PerformanceMetricsState.getHolderForHierarchy(activity.window.decorView).state?.putState("screen", name)
    }
}
