package app.zmby.ui

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.focus.FocusRequester
import kotlinx.coroutines.android.awaitFrame
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.zmby.PlaybackActivity
import app.zmby.data.ServerItem
import app.zmby.emby.BaseItemDto
import app.zmby.log.ZLog
import app.zmby.playback.TrackChoice
import app.zmby.playback.WatchThreshold
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.platform.LocalContext
import app.zmby.zmby

sealed interface Route {
    data object Home : Route
    /** Every item route names its server: the same id elsewhere can be a different film. */
    data class Detail(val server: String, val itemId: String, val resourceOnly: Boolean = false) : Route
    /** A series; with [episodeId], opened on that episode's season with the episode focused. */
    data class Series(val server: String, val seriesId: String, val episodeId: String? = null) : Route
    data class Catalog(val ref: app.zmby.catalog.CatalogId, val fallbackServer: String? = null, val fallbackId: String? = null) : Route
    data class CatalogPerson(val source: String, val id: String) : Route
    data object Search : Route
    data object Servers : Route
    /** Add a server (log in). */
    data object AddServer : Route
    /** Phone-side bulk import over the LAN (QR code). */
    data object Import : Route
    data object BackupImport : Route
    data object Backup : Route
    data class Restore(val uri: String, val source: String) : Route
    data object Settings : Route
    /** 人物页: [personKey] in the store, reached from [server]'s own person [personId]. */
    data class Person(val personKey: String, val server: String, val personId: String) : Route
    /** 设置 › 数据来源 › one feature's servers or providers. */
    data class DataSource(val feature: app.zmby.data.SourceFeature) : Route
    /** 观影统计 (P4). */
    data object Stats : Route
}

interface Nav {
    fun go(route: Route)
    fun back()
    fun replaceAll(route: Route)
}

/**
 * Opens what a card points at. Series, seasons and episodes all go to the series page (an episode
 * opens it on its season, focused): episodes have no page of their own, so switching episodes is
 * always one press away. Films and other videos go to the detail page.
 */
fun Nav.open(ref: ServerItem) {
    val item = ref.item
    if (ref.server == "catalog") {
        val parts = item.id.removePrefix("catalog:").split(':')
        if (parts.size == 3) go(Route.Catalog(app.zmby.catalog.CatalogId(parts[0], parts[1], parts[2])))
        return
    }
    when (item.type) {
        "Series" -> go(Route.Series(ref.server, item.id))
        "Season" -> item.seriesId?.let { go(Route.Series(ref.server, it)) }
        "Episode" -> item.seriesId?.let { go(Route.Series(ref.server, it, episodeId = item.id)) } ?: go(Route.Detail(ref.server, item.id))
        else -> go(Route.Detail(ref.server, item.id))
    }
}

/** The resume point a play button offers: none once it is past the watch threshold. */
@Composable
fun resumeMsOf(item: BaseItemDto): Long {
    val percent by LocalContext.current.zmby.settings.watchedPercent.collectAsState()
    return WatchThreshold.startPosition(item.resumeMs, item.runtimeMs, percent)
}

/**
 * Plays [ref] on its own server; progress goes back to that server. [alternates] are the other
 * versions in default order, tried in turn if this one fails to start (播放失败自动换源).
 */
fun play(context: Context, ref: ServerItem, startMs: Long, mediaSourceId: String? = null, tracks: TrackChoice = TrackChoice(), alternates: List<app.zmby.playback.Alternate> = emptyList()) {
    require(ref.server != "catalog") { "Catalogue entries have no playback address" }
    val app = context.zmby
    val json = app.library.itemJson(ref.item)
    context.startActivity(PlaybackActivity.intent(context, ref.server, json, startMs, mediaSourceId, tracks, app.putAlternates(alternates)))
}

/**
 * Bumps every time the screen comes back to the foreground (e.g. after the external player
 * returns), so screens can reload progress and played state.
 */
@Composable
fun rememberResumeTick(): Int {
    val owner = LocalLifecycleOwner.current
    var tick by remember { mutableIntStateOf(0) }
    DisposableEffect(owner) {
        var first = true
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                if (first) first = false else tick++
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    return tick
}

/** When this composable first appeared, on the same clock as key events. */
@Composable
fun rememberShownAt(): Long = remember { android.os.SystemClock.uptimeMillis() }

/** When the user last pressed a remote key; MainActivity feeds it. */
object RemoteInput {
    @Volatile var lastKeyAt: Long = 0L
}

/**
 * A requester that takes focus once [key] is ready (e.g. data loaded), so a remote always has
 * somewhere to start. It never fires after the user has pressed a key on this screen, so data that
 * arrives late cannot pull focus back. Pass a null key to wait.
 */
@Composable
fun rememberInitialFocus(key: Any?, shownAt: Long = rememberShownAt()): FocusRequester {
    val requester = remember { FocusRequester() }
    val memory = LocalNavEntry.current?.focus
    LaunchedEffect(key) {
        if (key == null) return@LaunchedEffect
        awaitFrame()
        if (memory != null && memory.restorePending) {
            // Coming back to this screen: the element focused before takes precedence.
            withTimeoutOrNull(RESTORE_WAIT_MS) { snapshotFlow { memory.restorePending }.first { !it } }
            if (memory.restored) return@LaunchedEffect
            memory.restorePending = false
            ZLog.d("Focus", "restore of ${memory.lastKey} gave up; using initial focus")
            runCatching { requester.requestFocus() }
            return@LaunchedEffect
        }
        if (RemoteInput.lastKeyAt < shownAt) {
            runCatching { requester.requestFocus() }
                .onSuccess { if (!it) ZLog.w("Focus", "initial focus refused for $key") }
                .onFailure { ZLog.w("Focus", "initial focus failed: $it") }
        } else {
            ZLog.d("Focus", "initial focus skipped: a key was pressed after the screen appeared")
        }
    }
    return requester
}

/** How long a returning screen waits for its previously focused element to be composed again. */
private const val RESTORE_WAIT_MS = 1_500L
