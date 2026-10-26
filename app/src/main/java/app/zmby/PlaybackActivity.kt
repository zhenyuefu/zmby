package app.zmby

import app.zmby.ui.userMessage
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import androidx.tv.material3.Text
import app.zmby.data.ServerItem
import app.zmby.emby.BaseItemDto
import app.zmby.log.ZLog
import app.zmby.media.episodeCode
import app.zmby.media.episodeName
import app.zmby.playback.matchesPlaybackPath
import app.zmby.playback.PreparedPlayback
import app.zmby.playback.PlaybackMonitor
import app.zmby.playback.SubtitlePrepareException
import app.zmby.playback.SubtitleSelectionVerifier
import app.zmby.playback.TrackChoice
import app.zmby.playback.TrackLanguages
import app.zmby.playback.Alternate
import app.zmby.playback.relayReturnAction
import app.zmby.playback.RelayReturnAction
import app.zmby.playback.Failover
import app.zmby.segments.SegmentSkipper
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import app.zmby.ui.components.Glyph
import app.zmby.ui.components.GlassButton
import app.zmby.ui.rememberInitialFocus
import app.zmby.ui.theme.Z
import app.zmby.ui.theme.ZmbyTheme
import app.zmby.zidoo.ZidooIntents
import app.zmby.zidoo.ZidooPlayer
import app.zmby.zidoo.ZidooTracks
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import app.zmby.emby.msToTicks
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Hands one item to the Zidoo system player and stays underneath it, so the player's result
 * comes back here. Progress is polled and reported by [PlaybackMonitor] in the app scope. After an
 * episode is watched past the threshold it offers the next one.
 */
class PlaybackActivity : ComponentActivity() {

    private val message = mutableStateOf("正在准备播放…")
    private val failed = mutableStateOf(false)
    private val retryRelay = mutableStateOf<(() -> Unit)?>(null)
    private val next = mutableStateOf<BaseItemDto?>(null)
    /** Offered when the chosen external subtitle could not be prepared. */
    private val playWithoutSubtitle = mutableStateOf<(() -> Unit)?>(null)
    private var current: BaseItemDto? = null
    /** The server the item belongs to; progress and the next episode come from it. */
    private var server: String = ""
    /** Up Next: seconds left before the next episode plays by itself; null when not counting. */
    private val countdown = mutableStateOf<Int?>(null)
    /** 播放失败自动换源: the other versions in default order, and which were tried. */
    private var alternates: List<Alternate> = emptyList()
    private val tried = mutableSetOf<String>()
    private var requestedStartMs = 0L
    /** Languages of the last playback, for the next episode or another version. */
    private var carried: TrackLanguages? = null
    private var launchedAt = 0L
    /** A switch to another version is in progress (shown instead of the error). */
    private var switching = false

    private val playerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        onPlayerResult(result.data)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ZmbyTheme {
                Column(
                    Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(32.dp, Alignment.CenterVertically),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    val upNext = next.value
                    if (upNext != null) {
                        NextEpisodePrompt(upNext)
                    } else {
                        Text(message.value, fontSize = 30.sp, color = if (failed.value) Z.warn else Z.textSecondary)
                        if (failed.value) {
                            Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                                retryRelay.value?.let { retry -> GlassButton("通过本地转发重试", retry) }
                                val without = playWithoutSubtitle.value
                                if (without != null) {
                                    GlassButton("不带字幕播放", without, Modifier.focusRequester(rememberInitialFocus(Unit)))
                                    GlassButton("返回", { finish() })
                                } else {
                                    GlassButton("返回", { finish() }, Modifier.focusRequester(rememberInitialFocus(Unit)))
                                }
                            }
                        }
                    }
                }
            }
        }
        // After a recreation the player is already running; just wait for its result.
        if (savedInstanceState?.getBoolean(KEY_LAUNCHED) == true) return
        server = intent.getStringExtra(EXTRA_SERVER).orEmpty()
        val tracks = TrackChoice(
            audio = intent.getIntExtra(EXTRA_AUDIO, NO_TRACK).takeIf { it != NO_TRACK },
            subtitle = intent.getIntExtra(EXTRA_SUBTITLE, NO_TRACK).takeIf { it != NO_TRACK },
        )
        lifecycleScope.launch {
            // Normally ready long ago; after the process was killed this screen can come first.
            zmby.awaitReady()
            alternates = zmby.takeAlternates(intent.getStringExtra(EXTRA_ALTERNATES))
            val json = intent.getStringExtra(EXTRA_ITEM) ?: return@launch fail("缺少播放条目")
            val item = withContext(Dispatchers.Default) { zmby.emby.json.decodeFromString(BaseItemDto.serializer(), json) }
            requestedStartMs = intent.getLongExtra(EXTRA_START_MS, 0)
            val sourceId = intent.getStringExtra(EXTRA_SOURCE_ID)
            tried += Failover.key(server, sourceId)
            start(item, requestedStartMs, sourceId, tracks)
        }
    }

    /**
     * 连播 Up Next: the next episode on the same server (and so the same line), with the languages
     * of the episode just watched. Counts down when 倒计时自动播放 is on; any key stops the count.
     */
    @androidx.compose.runtime.Composable
    private fun NextEpisodePrompt(ep: BaseItemDto) {
        val left = countdown.value
        androidx.compose.runtime.LaunchedEffect(ep.id, left != null) {
            while (true) {
                val n = countdown.value ?: break
                if (n <= 0) { playNext(ep); break }
                kotlinx.coroutines.delay(1_000)
                countdown.value = countdown.value?.minus(1)
            }
        }
        Column(
            Modifier.onPreviewKeyEvent { e ->
                // Moving around or pressing a key means the user is choosing: stop counting.
                if (e.type == KeyEventType.KeyDown && e.key != Key.DirectionCenter && e.key != Key.Enter) countdown.value = null
                false
            },
            verticalArrangement = Arrangement.spacedBy(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(if (left != null) "$left 秒后播放下一集" else "下一集", fontSize = 26.sp, color = Z.textSecondary)
            Text(
                listOfNotNull(ep.seriesName, episodeCode(ep), episodeName(ep)).joinToString("  ·  "),
                fontSize = 44.sp,
                fontWeight = FontWeight.Black,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                GlassButton("播放下一集", { playNext(ep) }, Modifier.focusRequester(rememberInitialFocus(ep.id)), glyph = Glyph.Play)
                GlassButton("返回", { countdown.value = null; finish() })
            }
        }
    }

    private fun playNext(ep: BaseItemDto) {
        countdown.value = null
        next.value = null
        // A new episode: its own versions are not known here, so nothing to fall back on.
        alternates = emptyList()
        tried.clear()
        requestedStartMs = ep.resumeMs
        start(ep, ep.resumeMs, null, TrackChoice(), carried)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_LAUNCHED, zmby.activePlayback != null)
    }

    private fun start(item: BaseItemDto, startMs: Long, sourceId: String?, tracks: TrackChoice = TrackChoice(), languages: TrackLanguages? = null, retry: (suspend () -> PreparedPlayback)? = null) {
        val app = zmby
        val session = app.registry.session(server) ?: return fail("这台服务器已删除")
        current = item
        failed.value = false
        retryRelay.value = null
        playWithoutSubtitle.value = null
        if (!switching) message.value = "正在准备播放…"
        switching = false
        val percent = app.settings.watchedPercent.value
        val playServer = server
        // 片头片尾: looked up beside the preparation and never waited for (local-data spec).
        val plan = app.appScope.async(Dispatchers.IO) {
            runCatching { app.segmentPlan(playServer, item, item.runtimeMs) }
                .onFailure { ZLog.w(TAG, "segments for ${item.id} unavailable", it) }
                .getOrNull()
        }
        val skipper = app.appScope.async {
            plan.await()?.takeIf { it.isNotEmpty() }?.let {
                SegmentSkipper.of(it, episode = item.type == "Episode", intro = app.settings.skipIntro.value, recap = app.settings.skipRecap.value, credits = app.settings.skipCredits.value)
            }
        }

        lifecycleScope.launch {
            val prepared = try {
                val target = ZidooIntents.installedPlayer(this@PlaybackActivity)
                retry?.invoke() ?: app.preparer.prepare(
                    session, item, startMs, sourceId, percent, tracks,
                    subtitles = target?.subtitles ?: ZidooPlayer.SubtitleDelivery.IntentExtras,
                    audioLanguage = languages?.audio ?: app.settings.audioLanguage.value,
                    subtitleLanguage = languages?.subtitle ?: app.settings.subtitleLanguage.value,
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                skipper.cancel()
                throw e
            } catch (e: SubtitlePrepareException) {
                ZLog.w(TAG, "subtitle prepare failed for ${item.id}", e)
                skipper.cancel()
                if (retry == null) {
                    playWithoutSubtitle.value = { start(item, startMs, sourceId, tracks.copy(subtitle = ZidooTracks.SUBTITLE_OFF), languages) }
                }
                return@launch fail("所选字幕无法加载：${e.userMessage()}")
            } catch (e: Exception) {
                ZLog.w(TAG, "prepare failed for ${item.id}", e)
                skipper.cancel()
                if (switchToNext(item, "无法获取播放地址")) return@launch
                return@launch fail("无法获取播放地址：${e.userMessage()}")
            }
            carried = prepared.languages
            val zidoo = ZidooIntents.installedPlayer(this@PlaybackActivity)
            val useZidoo = zidoo != null
            val playerIntent = if (zidoo != null) {
                ZidooIntents.playerIntent(prepared.launch, zidoo)
            } else {
                ZidooIntents.genericIntent(prepared.launch)
            }
            val monitor = PlaybackMonitor(app.zidooStatus, prepared.reporter, prepared.startPositionMs, prepared.runtimeMs, percent, skipper = skipper,
                acceptsStatus = { status -> prepared.statusPaths.any { matchesPlaybackPath(status.path, it) } },
            )
            app.activePlayback?.let { old ->
                old.watchJob.cancel()
                app.releaseLocal(old.local)
                old.prepared.releaseRelay()
            }
            val local = prepared.local
            val job = app.appScope.launch {
                if (!useZidoo) return@launch
                if (local != null) {
                    val strings = ZidooIntents.playerStrings(app, zidoo)
                    launch { SubtitleSelectionVerifier(app.zidooStatus, app.zidooStatus, strings).verify(local.strm.name) }
                }
                monitor.watch()
            }
            launchedAt = android.os.SystemClock.elapsedRealtime()
            app.activePlayback = ActivePlayback(monitor, job, local, prepared, server, item, launchedAt)
            message.value = "正在播放：${item.displayTitle}"
            ZLog.i(
                TAG,
                "launch ${zidoo?.pkg ?: "generic player"} item=${item.id} start=${prepared.startPositionMs}ms " +
                    "type=${prepared.launch.mimeType} url=${prepared.launch.uri} extras=${prepared.launch.extras.keys}",
            )
            try {
                playerLauncher.launch(playerIntent)
            } catch (e: RuntimeException) {
                ZLog.e(TAG, "no player for ${prepared.launch.mimeType}", e)
                job.cancel()
                app.activePlayback = null
                app.releaseLocal(local)
                prepared.releaseRelay()
                fail(if (e is ActivityNotFoundException) "找不到可用的播放器" else "无法启动播放器：${e.userMessage()}")
            }
        }
    }

    private fun onPlayerResult(data: Intent?) {
        lifecycleScope.launch { handlePlayerResult(data) }
    }

    private suspend fun handlePlayerResult(data: Intent?) {
        val app = zmby
        val active = app.activePlayback
        app.activePlayback = null
        if (active == null) return finish()
        // Cleanup must survive an activity recreation while the cancelled poll is finishing.
        withContext(kotlinx.coroutines.NonCancellable) {
            active.watchJob.cancelAndJoin()
            app.releaseLocal(active.local)
            active.prepared.releaseRelay()
        }
        server = active.server
        current = active.item
        val result = ZidooIntents.parseResult(data)
        val elapsed = android.os.SystemClock.elapsedRealtime() - active.launchedAt
        ZLog.i(TAG, "player returned $result after ${elapsed}ms (video seen: ${active.monitor.started}, skips: ${active.monitor.skips})")
        val playedItem = current
        val failedStart = Failover.failedToStart(active.monitor.started, result)
        val relayRetry = active.prepared.retryWithRelay
        val relayAction = relayReturnAction(relayRetry != null, active.monitor.started, result)
        if (playedItem != null && relayRetry != null && relayAction == RelayReturnAction.Retry) {
            // This closure only exists for the first Auto/direct attempt. It preserves the selected
            // server, source, session, position and tracks, and cannot create another relay retry.
            active.monitor.finish(result)
            val resume = result?.positionMs?.takeIf { it > 0 } ?: active.monitor.lastPositionMs ?: active.prepared.startPositionMs
            start(playedItem, resume, null, retry = { relayRetry(resume) })
            return
        }
        // No result cannot distinguish an open failure from backing out before startup. Let the
        // user decide; a missing result code alone never triggers an automatic retry.
        if (playedItem != null && relayRetry != null && relayAction == RelayReturnAction.OfferRetry) {
            app.appScope.launch { active.monitor.finish(result) }
            retryRelay.value = { start(playedItem, active.prepared.startPositionMs, null, retry = { relayRetry(active.prepared.startPositionMs) }) }
            fail("播放器已返回，未确认开始播放")
            return
        }
        if (playedItem != null && failedStart && switchToNext(playedItem, "播放器没能打开这个版本")) {
            app.appScope.launch { active.monitor.finish(result) }
            return
        }
        // Reporting runs in the app scope so it completes even if this screen goes away.
        val outcome = app.appScope.async { active.monitor.finish(result) }
        val item = current
        val playedServer = server
        // The local rows change at once (local-data spec: 从播放器回来时先用本地上报结果更新).
        if (item != null) app.appScope.launch(Dispatchers.IO) {
            val done = outcome.await() ?: return@launch
            if (done.positionMs == null && !done.watched) return@launch
            runCatching { app.store.recordProgress(playedServer, item.id, msToTicks(done.positionMs ?: 0), done.watched) }
                .onFailure { ZLog.w(TAG, "local progress not recorded", it) }
            // 本地手动标记 › 取上次停止位置.
            done.positionMs?.let { pos -> runCatching { app.segments.recordStop(app.workKeyOf(playedServer, item), pos) } }
            app.onPlayed(ServerItem(playedServer, item), done)
        }
        lifecycleScope.launch {
            // Give the stop report a moment so the screen underneath reloads the new resume point.
            val done = withTimeoutOrNull(REPORT_WAIT_MS) { outcome.await() }
            if (item?.type != "Episode" || done?.watched != true || !app.settings.offerNextEpisode.value) return@launch finish()
            val upNext = runCatching { app.library.nextEpisode(ServerItem(server, item)) }
                .onFailure { ZLog.w(TAG, "next episode lookup failed", it) }
                .getOrNull()
            if (upNext == null) return@launch finish()
            ZLog.i(TAG, "offer next episode ${upNext.id} ${episodeCode(upNext)}")
            countdown.value = if (app.settings.upNextCountdown.value) UP_NEXT_SECONDS else null
            next.value = upNext
        }
    }

    /**
     * 播放失败自动换源: plays the next untried version (default order) with the same start and the
     * languages chosen for this one, and says which server and version it switched to. False when
     * switched off or nothing is left.
     */
    private fun switchToNext(failedItem: BaseItemDto, why: String): Boolean {
        val app = zmby
        if (!app.settings.autoFailover.value) return false
        val alt = Failover.next(alternates, tried) ?: return false
        tried += Failover.key(alt)
        val from = app.registry.get(server)?.name ?: "这台服务器"
        ZLog.i(TAG, "failover: ${failedItem.id} on $from failed ($why); trying ${alt.label}")
        server = alt.server
        switching = true
        message.value = "$why，已换到 ${alt.label}"
        failed.value = false
        lifecycleScope.launch {
            kotlinx.coroutines.delay(SWITCH_NOTICE_MS)
            start(alt.item, requestedStartMs, alt.mediaSourceId, TrackChoice(), carried)
        }
        return true
    }

    private fun fail(text: String) {
        ZLog.w(TAG, "playback screen shows: $text")
        message.value = text
        failed.value = true
    }

    companion object {
        private const val TAG = "Play"
        private const val KEY_LAUNCHED = "launched"
        private const val EXTRA_ITEM = "item"
        private const val EXTRA_SERVER = "server"
        private const val EXTRA_START_MS = "start_ms"
        private const val EXTRA_SOURCE_ID = "media_source_id"
        private const val EXTRA_AUDIO = "audio_index"
        private const val EXTRA_SUBTITLE = "subtitle_index"
        private const val NO_TRACK = Int.MIN_VALUE
        private const val EXTRA_ALTERNATES = "alternates"
        private const val REPORT_WAIT_MS = 3_000L
        private const val UP_NEXT_SECONDS = 10
        /** Long enough to read which version it switched to. */
        private const val SWITCH_NOTICE_MS = 1_800L

        fun intent(context: Context, server: String, itemJson: String, startPositionMs: Long, mediaSourceId: String? = null, tracks: TrackChoice = TrackChoice(), alternatesToken: String? = null): Intent =
            Intent(context, PlaybackActivity::class.java)
                .putExtra(EXTRA_ALTERNATES, alternatesToken)
                .putExtra(EXTRA_SERVER, server)
                .putExtra(EXTRA_ITEM, itemJson)
                .putExtra(EXTRA_START_MS, startPositionMs)
                .putExtra(EXTRA_SOURCE_ID, mediaSourceId)
                .putExtra(EXTRA_AUDIO, tracks.audio ?: NO_TRACK)
                .putExtra(EXTRA_SUBTITLE, tracks.subtitle ?: NO_TRACK)
    }
}
