package app.zmby.ui.screens

import androidx.compose.runtime.produceState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import app.zmby.data.ServerItem
import app.zmby.data.withPlaybackProgress
import app.zmby.ui.components.ActionFeedback
import app.zmby.ui.components.ActionIconButton
import app.zmby.ui.components.Glyph
import app.zmby.ui.components.LocalActionFeedback
import app.zmby.ui.userMessage
import app.zmby.zmby
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * The icon buttons of a detail header, in their fixed order (ui-spec §2.15): 快速选集 (series only)
 * → 观看标记 → 收藏 → 待看 → 片头片尾.
 */
internal enum class HeaderAction { QuickPick, Watched, Favorite, Watchlist, Marks }

/** The focus name tag of a header button: it names what the button is now, or why it is unavailable. */
internal fun actionTip(action: HeaderAction, on: Boolean = false, busy: Boolean = false, enabled: Boolean = true): String = when (action) {
    HeaderAction.QuickPick -> "快速选集"
    HeaderAction.Watched -> when {
        !enabled -> "暂无可标记的服务器条目"
        busy -> "正在标记…"
        on -> "标记为未观看"
        else -> "标记为已观看"
    }
    HeaderAction.Favorite -> when {
        !enabled -> "暂无服务器条目，不能收藏"
        busy -> if (on) "正在取消收藏…" else "正在加入收藏…"
        on -> "已收藏"
        else -> "加入收藏"
    }
    HeaderAction.Watchlist -> when {
        !enabled -> "暂无服务器条目，不能加入待看"
        busy -> if (on) "正在移出待看…" else "正在加入待看…"
        on -> "已在待看"
        else -> "加入待看"
    }
    HeaderAction.Marks -> if (enabled) "片头片尾" else "片头片尾 · 暂无可播放的版本"
}

/** 「已标记第 7 集为已观看 · 家里 NAS」; a movie has no episode part. */
internal fun watchedNotice(episode: Int?, played: Boolean, server: String?): String =
    listOfNotNull("已标记${episode?.let { "第 $it 集" }.orEmpty()}为${if (played) "已观看" else "未观看"}", server).joinToString(" · ")

/** 「没能加入待看 · 公益服 A 连接超时」: what failed, then where and why. */
internal fun failureNotice(action: String, server: String?, reason: String?): String =
    listOfNotNull("没能$action", listOfNotNull(server, reason).joinToString(" ").ifEmpty { null }).joinToString(" · ")

internal fun favoriteNotice(added: Boolean): String = if (added) "已加入收藏" else "已取消收藏"
internal fun watchlistNotice(added: Boolean): String = if (added) "已加入待看" else "已移出待看"

/**
 * Runs [work] once at a time and reports it on [feedback]: [success] after it returns, the failure
 * text with a retry (the same action) when it throws.
 */
internal fun runAction(
    scope: kotlinx.coroutines.CoroutineScope,
    feedback: ActionFeedback?,
    setBusy: (Boolean) -> Unit,
    failure: (Exception) -> String,
    success: () -> String?,
    work: suspend () -> Unit,
) {
    fun attempt() {
        setBusy(true)
        scope.launch {
            try {
                work()
                success()?.let { feedback?.success(it) }
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                feedback?.failure(failure(e)) { attempt() }
            } finally { setBusy(false) }
        }
    }
    attempt()
}

/** 观看标记 on the version that plays ([ref]); null when there is no server entry to mark. */
@Composable
internal fun WatchedAction(ref: ServerItem?, instances: List<ServerItem> = emptyList()) {
    val app = LocalContext.current.zmby
    val scope = rememberCoroutineScope()
    val feedback = LocalActionFeedback.current
    val progress by app.recentProgress.collectAsState()
    val persisted by produceState<app.zmby.emby.UserItemData?>(null, ref?.key) {
        value = if (ref?.server == "catalog") kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            app.store.detail(ref.server, ref.item.id)?.item?.userData
        } else null
    }
    val local = persisted?.let { if (ref != null && ref.key !in progress) progress + (ref.key to it) else progress } ?: progress
    val played = ref?.withPlaybackProgress(local)?.item?.userData?.played == true
    var busy by remember { mutableStateOf(false) }
    val enabled = ref != null
    ActionIconButton(Glyph.Check, actionTip(HeaderAction.Watched, played, busy, enabled), onClick = {
        val target = ref ?: return@ActionIconButton
        val mark = !played
        val server = app.registry.get(target.server)?.name
        var unsynced = 0
        val episode = target.item.indexNumber.takeIf { target.item.type == "Episode" }
        runAction(scope, feedback, { busy = it },
            failure = { e -> failureNotice(if (mark) "标记为已观看" else "标记为未观看", server, e.userMessage()) },
            success = { watchedNotice(episode, mark, server) + if (unsynced > 0) " · 已存本地，$unsynced 台尚未同步" else "" }) {
            unsynced = app.setWatched(target, mark, instances)
        }
    }, on = played, busy = busy, enabled = enabled, solidWhenOn = false, focusKey = "action:watched")
}

/** 收藏 is local first, including a catalogue work without a playable copy. */
@Composable
internal fun FavoriteAction(ref: ServerItem, instances: List<ServerItem>) {
    val app = LocalContext.current.zmby
    val scope = rememberCoroutineScope()
    val feedback = LocalActionFeedback.current
    val favorites by app.favorites.items.collectAsState()
    val favorite = remember(favorites, ref.key) { app.favorites.isFavorite(ref) }
    var busy by remember { mutableStateOf(false) }
    ActionIconButton(Glyph.Heart, actionTip(HeaderAction.Favorite, favorite, busy), onClick = {
        val add = !favorite
        runAction(scope, feedback, { busy = it },
            failure = { e -> failureNotice(if (add) "加入收藏" else "取消收藏", null, e.userMessage()) },
            success = { favoriteNotice(app.favorites.isFavorite(ref)) }) { app.favorites.toggle(ref, instances) }
    }, on = favorite, busy = busy, focusKey = "action:favorite")
}

/** 待看 is stored locally for the work. */
@Composable
internal fun WatchlistAction(ref: ServerItem, instances: List<ServerItem>) {
    val app = LocalContext.current.zmby
    val scope = rememberCoroutineScope()
    val feedback = LocalActionFeedback.current
    val entries by app.watchlist.entries.collectAsState()
    val listed = remember(entries, ref.key) { app.watchlist.contains(ref) }
    var busy by remember { mutableStateOf(false) }
    ActionIconButton(Glyph.Bookmark, actionTip(HeaderAction.Watchlist, listed, busy), onClick = {
        val add = !listed
        runAction(scope, feedback, { busy = it },
            failure = { e -> failureNotice(if (add) "加入待看" else "移出待看", null, e.userMessage()) },
            success = { watchlistNotice(add) }) { app.watchlist.toggle(ref, instances) }
    }, on = listed, busy = busy, focusKey = "action:watchlist")
}

/** 片头片尾 (本地手动标记) of the version that plays; unavailable until there is one. */
@Composable
internal fun MarksAction(ref: ServerItem?, onClick: () -> Unit) {
    val state = rememberSegmentStatus(ref)
    ActionIconButton(Glyph.Marks, segmentTip(state), onClick, enabled = ref != null,
        on = state.lookup?.plan?.isNotEmpty() == true, busy = state.loading, dot = state.failed,
        solidWhenOn = false, focusKey = "action:marks", allowWhileBusy = true)
}

@Composable
internal fun QuickPickAction(onClick: () -> Unit) {
    ActionIconButton(Glyph.Episodes, actionTip(HeaderAction.QuickPick), onClick, focusKey = "action:quick-pick")
}
