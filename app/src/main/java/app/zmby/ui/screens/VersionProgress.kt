package app.zmby.ui.screens

import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import app.zmby.data.newestProgress
import app.zmby.data.withPlaybackProgress
import app.zmby.emby.UserItemData
import app.zmby.playback.SavedPlayback
import app.zmby.playback.WatchThreshold
import app.zmby.zmby

/**
 * The versions on screen with each copy's merged progress (ui-spec §2.18 在看合并): per copy, the
 * newest of the server's own answer, what the 在看 lists and stop reports last stored and this
 * run's reports. [default] is what the play button starts until a version is picked on this visit.
 */
internal class VersionProgress(val ordered: List<Version>, val default: Version?) {
    /** [v] with its merged progress. */
    fun of(v: Version): Version = ordered.firstOrNull { it.key == v.key } ?: v
}

@Composable
internal fun rememberVersionProgress(versions: VersionsState, tick: Int): VersionProgress {
    val app = LocalContext.current.zmby
    val local by app.recentProgress.collectAsStateWithLifecycle()
    val percent by app.settings.watchedPercent.collectAsStateWithLifecycle()
    val copies = remember(versions.ordered) { versions.ordered.map { it.ref }.distinctBy { it.key } }
    val keys = remember(copies) { copies.map { it.key } }
    // Kept while the copies change, so a version arriving does not drop the resume point for a frame.
    var stored by remember { mutableStateOf<Map<String, UserItemData>>(emptyMap()) }
    LaunchedEffect(keys) { app.store.progressFlow(copies).collect { stored = it } }
    val memory = remember(keys, tick) { app.playbackMemory.latest(copies) }
    return remember(versions.ordered, stored, local, memory, percent) {
        val records = newestProgress(stored, local)
        val ordered = versions.ordered.map { v -> v.copy(item = v.ref.withPlaybackProgress(records).item) }
        VersionProgress(ordered, playbackDefault(ordered, memory, percent))
    }
}

/**
 * The version that plays until one is picked (ui-spec §2.18): the newest activity on any copy of
 * [ordered] (merged progress, ranked order) decides, as in 在看. A resume point (before the watch
 * threshold) plays its own copy from there unless the last successful start here ([memory]) is that
 * copy or came later; otherwise the remembered version while still valid, then the ranked first.
 */
internal fun playbackDefault(ordered: List<Version>, memory: SavedPlayback?, percent: Int): Version? {
    val remembered = memory?.let { m -> ordered.firstOrNull { it.ref.server == m.server && it.item.id == m.itemId && m.selection.restore(it.source) != null } }
    fun time(v: Version) = v.item.userData?.lastPlayedDate?.let { runCatching { java.time.Instant.parse(it).toEpochMilli() }.getOrNull() }
    // Equal or missing times keep the ranked order.
    val latest = ordered.distinctBy { it.ref.key }
        .filter { it.item.userData?.played == true || it.item.resumeMs > 0 }
        .maxWithOrNull(compareBy(nullsFirst()) { time(it) })
    val resumes = latest != null && latest.item.userData?.played != true &&
        WatchThreshold.startPosition(latest.item.resumeMs, latest.item.runtimeMs, percent) > 0
    if (latest == null || !resumes) return remembered ?: ordered.firstOrNull()
    if (memory != null && remembered != null) {
        if (remembered.ref.key == latest.ref.key) return remembered
        val at = time(latest)
        if (at == null || at < memory.playedAt) return remembered
    }
    return ordered.first { it.ref.key == latest.ref.key }
}
