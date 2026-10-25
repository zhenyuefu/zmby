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
import app.zmby.playback.PlaybackMonitor
import app.zmby.playback.SubtitlePrepareException
import app.zmby.playback.SubtitleSelectionVerifier
import app.zmby.playback.TrackChoice
import app.zmby.ui.components.Glyph
import app.zmby.ui.components.GlassButton
import app.zmby.ui.rememberInitialFocus
import app.zmby.ui.theme.Z
import app.zmby.ui.theme.ZmbyTheme
import app.zmby.zidoo.ZidooIntents
import app.zmby.zidoo.ZidooPlayer
import app.zmby.zidoo.ZidooTracks
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
    private val next = mutableStateOf<BaseItemDto?>(null)
    /** Offered when the chosen external subtitle could not be prepared. */
    private val playWithoutSubtitle = mutableStateOf<(() -> Unit)?>(null)
    private var current: BaseItemDto? = null
    /** The server the item belongs to; progress and the next episode come from it. */
    private var server: String = ""

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
            val json = intent.getStringExtra(EXTRA_ITEM) ?: return@launch fail("缺少播放条目")
            val item = withContext(Dispatchers.Default) { zmby.emby.json.decodeFromString(BaseItemDto.serializer(), json) }
            start(item, intent.getLongExtra(EXTRA_START_MS, 0), intent.getStringExtra(EXTRA_SOURCE_ID), tracks)
        }
    }

    @androidx.compose.runtime.Composable
    private fun NextEpisodePrompt(ep: BaseItemDto) {
        Text("下一集", fontSize = 26.sp, color = Z.textSecondary)
        Text(
            listOfNotNull(ep.seriesName, episodeCode(ep), ep.name).joinToString("  ·  "),
            fontSize = 44.sp,
            fontWeight = FontWeight.Black,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            GlassButton("播放下一集", {
                next.value = null
                start(ep, ep.resumeMs, null)
            }, Modifier.focusRequester(rememberInitialFocus(ep.id)), glyph = Glyph.Play)
            GlassButton("返回", { finish() })
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_LAUNCHED, zmby.activePlayback != null)
    }

    private fun start(item: BaseItemDto, startMs: Long, sourceId: String?, tracks: TrackChoice = TrackChoice()) {
        val app = zmby
        val session = app.registry.session(server) ?: return fail("这台服务器已删除")
        current = item
        failed.value = false
        playWithoutSubtitle.value = null
        message.value = "正在准备播放…"
        val percent = app.settings.watchedPercent.value

        lifecycleScope.launch {
            val prepared = try {
                val target = ZidooIntents.installedPlayer(this@PlaybackActivity)
                app.preparer.prepare(
                    session, item, startMs, sourceId, percent, tracks,
                    subtitles = target?.subtitles ?: ZidooPlayer.SubtitleDelivery.IntentExtras,
                    audioLanguage = app.settings.audioLanguage.value,
                    subtitleLanguage = app.settings.subtitleLanguage.value,
                )
            } catch (e: SubtitlePrepareException) {
                ZLog.w(TAG, "subtitle prepare failed for ${item.id}", e)
                playWithoutSubtitle.value = { start(item, startMs, sourceId, tracks.copy(subtitle = ZidooTracks.SUBTITLE_OFF)) }
                return@launch fail("所选字幕无法加载：${e.userMessage()}")
            } catch (e: Exception) {
                ZLog.w(TAG, "prepare failed for ${item.id}", e)
                return@launch fail("无法获取播放地址：${e.userMessage()}")
            }
            val zidoo = ZidooIntents.installedPlayer(this@PlaybackActivity)
            val useZidoo = zidoo != null
            val playerIntent = if (zidoo != null) {
                ZidooIntents.playerIntent(prepared.launch, zidoo)
            } else {
                ZidooIntents.genericIntent(prepared.launch)
            }
            val monitor = PlaybackMonitor(app.zidooStatus, prepared.reporter, prepared.startPositionMs, prepared.runtimeMs, percent)
            app.activePlayback?.let { old ->
                old.watchJob.cancel()
                app.releaseLocal(old.local)
            }
            val local = prepared.local
            val job = app.appScope.launch {
                if (!useZidoo) return@launch
                if (local != null && zidoo != null) {
                    val strings = ZidooIntents.playerStrings(app, zidoo)
                    launch { SubtitleSelectionVerifier(app.zidooStatus, app.zidooStatus, strings).verify(local.strm.name) }
                }
                monitor.watch()
            }
            app.activePlayback = ActivePlayback(monitor, job, local)
            message.value = "正在播放：${item.displayTitle}"
            ZLog.i(
                TAG,
                "launch ${zidoo?.pkg ?: "generic player"} item=${item.id} start=${prepared.startPositionMs}ms " +
                    "type=${prepared.launch.mimeType} url=${prepared.launch.uri} extras=${prepared.launch.extras.keys}",
            )
            try {
                playerLauncher.launch(playerIntent)
            } catch (e: ActivityNotFoundException) {
                ZLog.e(TAG, "no player for ${prepared.launch.mimeType}", e)
                job.cancel()
                app.activePlayback = null
                app.releaseLocal(local)
                fail("找不到可用的播放器")
            }
        }
    }

    private fun onPlayerResult(data: Intent?) {
        val app = zmby
        val active = app.activePlayback
        app.activePlayback = null
        if (active == null) return finish()
        active.watchJob.cancel()
        app.releaseLocal(active.local)
        val result = ZidooIntents.parseResult(data)
        ZLog.i(TAG, "player returned $result")
        // Reporting runs in the app scope so it completes even if this screen goes away.
        val outcome = app.appScope.async { active.monitor.finish(result) }
        val item = current
        // The local rows change at once (local-data spec: 从播放器回来时先用本地上报结果更新).
        if (item != null) app.appScope.launch(Dispatchers.IO) {
            val done = outcome.await() ?: return@launch
            runCatching { app.store.recordProgress(server, item.id, msToTicks(done.positionMs ?: 0), done.watched) }
                .onFailure { ZLog.w(TAG, "local progress not recorded", it) }
            app.onPlayed(ServerItem(server, item), done)
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
            next.value = upNext
        }
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
        private const val REPORT_WAIT_MS = 3_000L

        fun intent(context: Context, server: String, itemJson: String, startPositionMs: Long, mediaSourceId: String? = null, tracks: TrackChoice = TrackChoice()): Intent =
            Intent(context, PlaybackActivity::class.java)
                .putExtra(EXTRA_SERVER, server)
                .putExtra(EXTRA_ITEM, itemJson)
                .putExtra(EXTRA_START_MS, startPositionMs)
                .putExtra(EXTRA_SOURCE_ID, mediaSourceId)
                .putExtra(EXTRA_AUDIO, tracks.audio ?: NO_TRACK)
                .putExtra(EXTRA_SUBTITLE, tracks.subtitle ?: NO_TRACK)
    }
}
