package app.zmby

import android.app.Activity
import android.app.Application
import android.content.pm.ApplicationInfo
import android.os.StrictMode
import android.os.strictmode.Violation
import androidx.metrics.performance.JankStats
import androidx.metrics.performance.PerformanceMetricsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import app.zmby.log.ZLog
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

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
    @Volatile var enabled = false
        private set

    /**
     * Drawing parts turned off for a cost experiment (performance plan R6), debug builds only:
     * `adb shell setprop debug.zmby.perf_off shadow,slice,tint` before starting the app. Also
     * `winbg` (window background), `rootbg` (the theme's root fill), `scrim`, `backdrop` (the image),
     * `bdlayer` / `herolayer` / `rowlayer` (offscreen layers of the backdrop, home hero and home rows),
     * `blurcover` (the detail pages' blurred backdrop cover).
     */
    @Volatile var drawOff: Set<String> = emptySet()
        private set

    private fun readDrawOff() {
        drawOff = runCatching {
            val p = ProcessBuilder("/system/bin/getprop", "debug.zmby.perf_off").start()
            try { p.inputStream.bufferedReader().use { it.readLine().orEmpty() } } finally { p.destroy() }
        }.getOrDefault("").split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        if (drawOff.isNotEmpty()) ZLog.w(TAG, "drawing turned off for an experiment: $drawOff")
    }

    /** Recompositions per traced block since the last window (performance plan R0 ②). */
    private val recompositions = ConcurrentHashMap<String, AtomicInteger>()

    /** Recompositions per traced block since the process started, for tools/perf_walk.py. */
    private val totals = ConcurrentHashMap<String, AtomicInteger>()
    @Volatile private var totalsChanged = false

    /** Counts one composition of [block]; called through [TraceRecomposition]. */
    fun recomposed(block: String) {
        recompositions.getOrPut(block) { AtomicInteger() }.incrementAndGet()
        totals.getOrPut(block) { AtomicInteger() }.incrementAndGet()
        totalsChanged = true
    }

    /**
     * Logs the running totals once a second when they changed (`recomposition totals: …`): a walkthrough
     * script takes the difference between the lines before and after its key sequence, which the
     * per-window counts cannot give when the window spans the setup.
     */
    private fun logTotals() {
        java.util.Timer("zmby-recompositions", true).schedule(object : java.util.TimerTask() {
            override fun run() {
                if (!totalsChanged) return
                totalsChanged = false
                ZLog.d(TAG, "recomposition totals: " + totals.entries.sortedBy { it.key }.joinToString(" ") { "${it.key}=${it.value.get()}" })
            }
        }, 1_000L, 1_000L)
    }

    /** The counts since the last call, busiest first, then cleared: `Home.feed=12 Series.root=3`. */
    fun drainRecompositions(): String {
        if (recompositions.isEmpty()) return ""
        val counts = recompositions.keys.toList().mapNotNull { k -> recompositions.remove(k)?.get()?.takeIf { it > 0 }?.let { k to it } }
        return counts.sortedByDescending { it.second }.joinToString(" ") { "${it.first}=${it.second}" }
    }

    /** Time of the last back press, for the back-to-home timing (R0 ③). */
    @Volatile private var backAt = 0L

    fun backPressed() {
        if (enabled) backAt = android.os.SystemClock.uptimeMillis()
    }

    /** Logs how long after the last back press [what] happened on [screen]; once per back press. */
    fun afterBack(screen: String, what: String) {
        val at = backAt
        if (!enabled || at == 0L) return
        ZLog.i(TAG, "$screen $what ${android.os.SystemClock.uptimeMillis() - at} ms after back")
        if (what == "restored") backAt = 0L
    }

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
        logTotals()
        listener.execute { readDrawOff() }
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
                    drainRecompositions().takeIf { it.isNotEmpty() }?.let { ZLog.i(TAG, "recomposed in ${now - windowStart} ms: $it") }
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

/**
 * Counts each composition of the enclosing scope as [block] in debug builds (performance plan R0 ②);
 * the counts are logged with the JankStats window. Inline, so it is part of the caller's restart
 * scope and is never skipped on its own.
 */
@Composable
@Suppress("NOTHING_TO_INLINE")
inline fun TraceRecomposition(block: String) {
    if (Diagnostics.enabled) SideEffect { Diagnostics.recomposed(block) }
}
