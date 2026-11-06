package app.zmby.ui.screens

import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import app.zmby.aggregate.*
import app.zmby.catalog.*
import app.zmby.data.ServerItem
import app.zmby.data.withPlaybackProgress
import app.zmby.data.SourceFeature
import app.zmby.playback.TrackChoice
import app.zmby.ui.components.Health
import app.zmby.zmby
import kotlinx.coroutines.*

/** Resolve sources from already mapped concrete addresses. Never guess the first numbered copy. */
@Composable
internal fun rememberCatalogVersions(
    work: CatalogWork,
    episode: CatalogEpisode?,
    resources: List<ServerItem>,
    refresh: Int,
    initial: ServerItem? = null,
    /** The version picked by hand last time (server, media source id); it comes first, as in [rememberVersions]. */
    manual: Pair<String, String>? = null,
    onRefresh: (() -> Unit)? = null,
    lookUp: Boolean = true,
    directoryPending: Boolean = false,
): VersionsState {
    val app = LocalContext.current.zmby
    val scope = rememberCoroutineScope()
    // The lookup, retries and ranking live in a holder off the main thread (performance plan R2).
    val lookup = remember(app, scope) { CatalogVersionLookup(app, scope) }
    lookup.onRefresh = onRefresh
    val input = CatalogVersionLookup.Input(work, episode, resources, refresh, initial, manual, lookUp, directoryPending)
    SideEffect { lookup.update(input) }
    val tagged by lookup.state.collectAsStateWithLifecycle()
    val identity = catalogVersionKey(work, episode)
    // Until the holder has caught up with another episode, its versions are worked out here once.
    return tagged?.takeIf { it.identity == identity }?.value ?: remember(identity) { lookup.computeNow(input) }
}

/** Catalog lookups ranked like [rememberVersions] ranks its own: the hand pick first, then [VersionRanker]. */
internal fun rankCatalogVersions(versions: List<Version>, prefs: VersionPrefs): List<Version> =
    VersionRanker.rank(versions, prefs, ::versionFacts)
