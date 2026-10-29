package app.zmby.ui.screens

import android.content.Context
import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import androidx.tv.material3.Text
import app.zmby.data.ServerItem
import app.zmby.aggregate.Keys
import app.zmby.aggregate.VersionPrefs
import app.zmby.aggregate.VersionRanker
import app.zmby.emby.BaseItemDto
import app.zmby.media.episodeCode
import app.zmby.media.episodeName
import app.zmby.media.formatDuration
import app.zmby.media.heroBadges
import app.zmby.media.workTitleOf
import app.zmby.playback.TrackChoice
import app.zmby.playback.TrackPreference
import app.zmby.ui.components.Badge
import app.zmby.ui.components.GlassButton
import app.zmby.ui.components.Scrims
import app.zmby.ui.components.TitleLogo
import app.zmby.ui.components.backdropRequest
import app.zmby.ui.components.blurredBackdropKey
import app.zmby.ui.components.blurredBackdropRequest
import app.zmby.ui.components.logoCacheKey
import app.zmby.ui.components.logoRequest
import app.zmby.ui.components.prefetch
import app.zmby.ui.rememberResumeTick
import app.zmby.ui.theme.Z
import app.zmby.zmby
import coil.compose.AsyncImage
import coil.imageLoader
import coil.memory.MemoryCache
import coil.request.Disposable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.min

/*
 * The home hero (主推区, ui-spec §2.1; focus rules): a carousel of recent additions. Left and right on
 * its single 详情 button turn the page, and the whole page — backdrop, Logo and text — slides in from
 * the side of the key; held or repeated keys keep the slide moving instead of queueing whole
 * animations. A countdown bar on the current page dot turns to the next title when it fills. The
 * titles on each side are fetched ahead, so a turn draws from the memory cache.
 */

/** Titles fetched ahead on each side of the current one: backdrop, blurred copy, Logo, full item. */
internal const val HERO_PREFETCH = 2

/** How long a title stays before the hero turns to the next one. */
internal const val HERO_ROTATE_MS = 9_000L

/** Countdown step: the bar is 34 px, so a step moves it about a third of a pixel. */
private const val HERO_TICK_MS = 100L

/** The slide may run at most this many pages behind the keys; further presses are dropped. */
private const val MAX_LEAD = 2

/** 详情 pressed this soon after an automatic turn opens the title that was on screen before it. */
private const val CONFIRM_GRACE_MS = 600L

/** The hero's text is gone after this much scroll, its backdrop after [BACKDROP_FADE_PX]. */
private const val TEXT_FADE_PX = 300f
private const val BACKDROP_FADE_PX = 540f

/** Scroll beyond which the hero counts as gone (the list has moved past its first item). */
private const val HERO_GONE_PX = 600f

/** Logo box (ui-spec §1.3) and the fixed text block under the 50-px gap below the top bar. */
private val LOGO_WIDTH = 560.dp
private val LOGO_HEIGHT = 170.dp
private val INFO_HEIGHT = 374.dp

/**
 * The hero's state, kept on the home's back stack entry: the page counter runs without bounds
 * (page = counter mod titles), so a turn always slides in the key's direction, also across the wrap.
 */
@Stable
class HeroCarousel {
    var items by mutableStateOf<List<ServerItem>>(emptyList())

    /** The source line above each title by [ServerItem.key] (「家里 NAS · 为你推荐」), else [defaultLabel]. */
    var labels by mutableStateOf<Map<String, String>>(emptyMap())
    var defaultLabel by mutableStateOf<String?>(null)

    /** The page the keys asked for (unbounded). */
    var target by mutableIntStateOf(0)
        private set

    /** Where the slide is, in pages; follows [target] with a spring. */
    val position = Animatable(0f, visibilityThreshold = 0.0005f)

    /** The countdown to the next automatic turn, 0..1. */
    var progress by mutableFloatStateOf(0f)
        private set

    /** The home list; how far it has scrolled the hero up drives the fade. */
    var list by mutableStateOf<LazyListState?>(null)

    private var elapsed = 0L
    private var autoAt = 0L
    private var beforeAuto = 0

    /** Pixels the hero has scrolled up, clamped: derived, so readers wake only while it changes. */
    private val scrolledState = derivedStateOf {
        val l = list ?: return@derivedStateOf 0f
        if (l.firstVisibleItemIndex > 0) HERO_GONE_PX else min(l.firstVisibleItemScrollOffset.toFloat(), HERO_GONE_PX)
    }
    val scrolled: Float get() = scrolledState.value
    val textVisible: Float get() = (1f - scrolled / TEXT_FADE_PX).coerceIn(0f, 1f)
    val backdropVisible: Float get() = (1f - scrolled / BACKDROP_FADE_PX).coerceIn(0f, 1f)

    /** The pages on screen: the one the slide rests on, or the two it is between. */
    private val pagesState = derivedStateOf {
        if (items.isEmpty()) return@derivedStateOf emptyList()
        val p = position.value
        val f = floor(p).toInt()
        if (p == f.toFloat()) listOf(f) else listOf(f, f + 1)
    }
    val pages: List<Int> get() = pagesState.value

    val size: Int get() = items.size
    val page: Int get() = if (items.isEmpty()) 0 else Math.floorMod(target, items.size)
    fun itemAt(i: Int): ServerItem? = items.takeIf { it.isNotEmpty() }?.let { it[Math.floorMod(i, it.size)] }
    val current: ServerItem? get() = itemAt(target)

    /** Turns [delta] pages; a manual turn restarts the countdown. False when it was dropped. */
    fun turn(scope: CoroutineScope, delta: Int, auto: Boolean = false): Boolean {
        if (size < 2) return false
        if (abs(target + delta - position.value) > MAX_LEAD) return false
        if (auto) {
            beforeAuto = target
            autoAt = SystemClock.uptimeMillis()
        } else {
            autoAt = 0L
        }
        target += delta
        elapsed = 0L
        progress = 0f
        scope.launch { position.animateTo(target.toFloat(), SLIDE) }
        return true
    }

    /** One countdown step; turns to the next title when the bar is full. */
    fun tick(scope: CoroutineScope, ms: Long) {
        elapsed += ms
        if (elapsed >= HERO_ROTATE_MS) turn(scope, 1, auto = true) else progress = elapsed.toFloat() / HERO_ROTATE_MS
    }

    /** What 详情 opens: right after an automatic turn, still the title the user was reading. */
    fun chosen(): ServerItem? =
        if (autoAt != 0L && SystemClock.uptimeMillis() - autoAt < CONFIRM_GRACE_MS) itemAt(beforeAuto) else current

    /** Back on home after a slide was cut off (navigation cancels its coroutine): rest on the target. */
    suspend fun settle() {
        if (position.value != target.toFloat()) position.snapTo(target.toFloat())
    }

    private companion object {
        /** No bounce; about 0.4 s for a page, and a new target keeps the current speed. */
        val SLIDE = spring<Float>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessLow)
    }
}

/**
 * [items] around [target] in fetch order — the current one, then alternately right and left — up
 * to [radius] on each side, each title once (fewer titles than the window wrap onto each other).
 */
internal fun <T> heroWindow(items: List<T>, target: Int, radius: Int = HERO_PREFETCH): List<T> {
    if (items.isEmpty()) return emptyList()
    val order = buildList {
        add(0)
        for (d in 1..radius) {
            add(d)
            add(-d)
        }
    }
    return order.map { Math.floorMod(target + it, items.size) }.distinct().map { items[it] }
}

/** The full-screen backdrop size the hero draws and prefetches at, px. */
internal fun screenPx(context: Context): Pair<Int, Int> =
    context.resources.displayMetrics.let { it.widthPixels to it.heightPixels }

/**
 * The hero's backdrop pages (the home's [app.zmby.ui.components.BackdropLayer]): each page is its
 * backdrop with the home scrims, in its own offscreen layer, so a slide only moves cached layers and
 * a resting page is one textured quad (P4 perf). The pages move up and fade with the scroll.
 */
@Composable
fun HeroBackdrop(carousel: HeroCarousel, scrims: Scrims) {
    val context = LocalContext.current
    val library = context.zmby.library
    val (w, h) = remember(context) { screenPx(context) }
    for (i in carousel.pages) {
        key(i) {
            val url = carousel.itemAt(i)?.let { library.backdrop(it) }
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        translationX = (i - carousel.position.value) * size.width
                        translationY = -carousel.scrolled
                        alpha = carousel.backdropVisible
                        compositingStrategy = CompositingStrategy.Offscreen
                    },
            ) {
                if (url != null) {
                    AsyncImage(
                        model = remember(url) { backdropRequest(context, url, w, h) },
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                Box(Modifier.fillMaxSize().drawWithCache { onDrawBehind { with(scrims) { draw(size) } } })
            }
        }
    }
}

/** The sliding text pages: Logo or title, the meta line with badges, two lines of overview. */
@Composable
fun HeroPages(carousel: HeroCarousel, details: Map<String, BaseItemDto>) {
    Box(Modifier.fillMaxWidth().height(INFO_HEIGHT)) {
        for (i in carousel.pages) {
            val ref = carousel.itemAt(i)
            if (ref != null) key(i) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            translationX = (i - carousel.position.value) * size.width
                            alpha = carousel.textVisible
                            // Cached like the rows: redrawn when the title changes, not while it slides.
                            compositingStrategy = CompositingStrategy.Offscreen
                        },
                ) {
                    HeroInfo(ref, details[ref.key], carousel.labels[ref.key] ?: carousel.defaultLabel)
                }
            }
        }
    }
}

@Composable
private fun HeroInfo(ref: ServerItem, full: BaseItemDto?, label: String?) {
    val app = LocalContext.current.zmby
    val library = app.library
    val item = ref.item
    val isEpisode = item.type == "Episode"
    val pattern by app.settings.versionPattern.flow.collectAsState()
    val quality by app.settings.quality.flow.collectAsState()
    val nonDisc by app.settings.preferNonDisc.flow.collectAsState()
    val audioLanguage by app.settings.audioLanguage.flow.collectAsState()
    val resumeTick = rememberResumeTick()
    // Only this item's known sources; do not mix capabilities from different versions or servers.
    val badges by key(ref.key, full) {
        produceState<List<String>>(emptyList(), pattern, quality, nonDisc, audioLanguage, resumeTick) {
            value = withContext(Dispatchers.Default) {
                val manual = library.pickedVersion(Keys.work(ref.server, full ?: item) + episodeSuffix(full ?: item))
                val source = VersionRanker.bestSource(ref.server, full?.mediaSources.orEmpty(), VersionPrefs(
                    manual = manual, pattern = VersionPrefs.pattern(pattern), quality = quality, preferNonDisc = nonDisc,
                ))
                val audio = source?.let { TrackPreference.choose(it, TrackChoice(), audioLanguage, TrackPreference.SERVER).audio }
                heroBadges(source, audio)
            }
        }
    }
    Column(Modifier.padding(start = 80.dp).width(880.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        // Where the title came from (Main.dc.html: 19/600 at 68 % above the Logo).
        Text(label.orEmpty(), fontSize = 19.sp, fontWeight = FontWeight.SemiBold, color = Z.textSecondary, maxLines = 1, modifier = Modifier.height(24.dp))
        Box(Modifier.height(LOGO_HEIGHT), contentAlignment = Alignment.BottomStart) {
            TitleLogo(library.logo(ref), workTitleOf(item), maxWidth = LOGO_WIDTH, maxHeight = LOGO_HEIGHT)
        }
        Row(Modifier.height(34.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            val meta = listOfNotNull(
                item.productionYear?.toString(),
                if (isEpisode) listOfNotNull(episodeCode(item), episodeName(item)).joinToString(" ") else genresLabel(item),
                formatDuration(item.runtimeMs),
            )
            Text(meta.joinToString("  ·  "), fontSize = 22.sp, color = Z.textPrimary.copy(alpha = 0.78f), maxLines = 1)
            badges.forEach { Badge(it, fontSize = 16) }
        }
        val overview = (full ?: item).overview
        Box(Modifier.height(74.dp)) {
            if (!overview.isNullOrBlank()) {
                Text(overview, fontSize = 24.sp, lineHeight = 37.sp, color = Z.textPrimary.copy(alpha = 0.75f), maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(760.dp))
            }
        }
    }
}

/**
 * The parts that do not slide: 详情, which keeps the focus while the pages turn under it, and the
 * page dots. Left and right on 详情 turn the page (focus rules); with one title they do nothing.
 */
@Composable
fun HeroControls(
    carousel: HeroCarousel,
    focus: FocusRequester,
    running: Boolean,
    scope: CoroutineScope,
    onOpen: (ServerItem) -> Unit,
    onFocusChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        Modifier
            .padding(start = 80.dp, top = 32.dp)
            .graphicsLayer { alpha = carousel.textVisible }
            // hasFocus of the group covers 详情 (focus rules: back on the hero scrolls the page to the top).
            .onFocusChanged { onFocusChange(it.hasFocus) }
            .focusGroup(),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        GlassButton(
            "详情",
            onClick = { carousel.chosen()?.let(onOpen) },
            modifier = Modifier.focusRequester(focus).then(modifier).onPreviewKeyEvent { e ->
                val delta = when (e.key) {
                    Key.DirectionLeft -> -1
                    Key.DirectionRight -> 1
                    else -> 0
                }
                if (delta == 0 || carousel.size < 2) return@onPreviewKeyEvent false
                // Repeats of a held key turn too; presses too far ahead of the slide are swallowed.
                if (e.type == KeyEventType.KeyDown) carousel.turn(scope, delta)
                true
            },
        )
        PageDots(carousel, running)
    }
}

/** The page dots; the current one is a bar that fills as the countdown runs (full when it does not). */
@Composable
private fun PageDots(carousel: HeroCarousel, running: Boolean) {
    val n = carousel.size
    if (n < 2) return
    val page = carousel.page
    Row(Modifier.padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        repeat(n) { i ->
            if (i == page) {
                Box(
                    Modifier.height(8.dp).width(34.dp).clip(CircleShape).drawBehind {
                        drawRect(Z.textPrimary.copy(alpha = 0.4f))
                        // Read while drawing: the countdown redraws the bar only.
                        val f = if (running) carousel.progress else 1f
                        drawRect(Z.focusFill, size = Size(size.width * f, size.height))
                    },
                )
            } else {
                Box(Modifier.height(8.dp).width(8.dp).clip(CircleShape).background(Z.textPrimary.copy(alpha = 0.4f)))
            }
        }
    }
}

/**
 * Runs the countdown while it should: automatic rotation on, more than one title, the hero on
 * screen and the app in front. Focus on 详情 does not stop it (focus rules, 2026-10-25): 详情 is the
 * only button, and a press right after an automatic turn still opens the earlier title.
 * Returns whether it runs.
 */
@Composable
fun rememberHeroCountdown(carousel: HeroCarousel, rotates: Boolean, scope: CoroutineScope): Boolean {
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val resumed = lifecycle.isAtLeast(Lifecycle.State.RESUMED)
    val shown by remember(carousel) { derivedStateOf { carousel.scrolled < TEXT_FADE_PX } }
    val running = rotates && carousel.size > 1 && shown && resumed
    LaunchedEffect(running) {
        while (running) {
            delay(HERO_TICK_MS)
            carousel.tick(scope, HERO_TICK_MS)
        }
    }
    return rotates && carousel.size > 1
}

/**
 * Fetches the titles around the current one ([HERO_PREFETCH] each side) with the requests the hero
 * draws them with — sharp backdrop at screen size, blurred copy, Logo at its box — so a turn finds
 * them in the memory cache; and their full items (badges) through [prefetchDetails].
 */
@Composable
fun HeroPrefetch(carousel: HeroCarousel, refresh: Any?, prefetchDetails: (List<ServerItem>) -> Unit) {
    val context = LocalContext.current
    val library = context.zmby.library
    val loader = context.imageLoader
    val density = LocalDensity.current
    val logoW = with(density) { LOGO_WIDTH.roundToPx() }
    val logoH = with(density) { LOGO_HEIGHT.roundToPx() }
    val (w, h) = remember(context) { screenPx(context) }
    val inFlight = remember { HashMap<MemoryCache.Key, Disposable>() }
    val target = carousel.target
    val items = carousel.items
    LaunchedEffect(target, items, refresh) {
        val window = heroWindow(items, target)
        for (ref in window) {
            library.backdrop(ref)?.let { url ->
                // The blurred copy after the sharp one: same file, read from the disk cache then.
                prefetch(loader, backdropRequest(context, url, w, h), MemoryCache.Key(url), inFlight) {
                    prefetch(loader, blurredBackdropRequest(context, url), blurredBackdropKey(url), inFlight)
                }
            }
            library.logo(ref)?.let { url -> prefetch(loader, logoRequest(context, url, logoW, logoH), logoCacheKey(url, logoW, logoH), inFlight) }
        }
        inFlight.values.removeAll { it.isDisposed }
        prefetchDetails(window)
    }
}
