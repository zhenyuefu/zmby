package app.zmby.ui.screens

import app.zmby.zmby

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.*
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import app.zmby.ui.components.*
import app.zmby.ui.theme.Z

/** All distances are design dp; the same frame is used in both scroll directions. */
internal data class DetailHeadingFrame(
    val progress: Float, val titleY: Float, val titleScale: Float,
    val metaX: Float, val metaY: Float, val metaScale: Float,
)

internal fun detailHeadingFrame(scroll: Float, titleWidth: Float, titleHeight: Float, gap: Float): DetailHeadingFrame {
    val t = (scroll / 300f).coerceIn(0f, 1f)
    val p = t * t * (3f - 2f * t)
    // Shrink and make room before the metadata rises to the same baseline as the title.
    val makeRoom = 1f - (1f - p) * (1f - p)
    val scale = minOf(0.5f, 64f / titleHeight.coerceAtLeast(1f), 420f / titleWidth.coerceAtLeast(1f))
    fun mix(from: Float, to: Float, progress: Float = p) = from + (to - from) * progress
    return DetailHeadingFrame(p,
        mix(140f, (140f - titleHeight * scale) / 2f), mix(1f, scale, makeRoom),
        (titleWidth * scale + 24f) * makeRoom, mix(140f + titleHeight + gap, (140f - 34f * 20f / 22f) / 2f, p * p),
        mix(1f, 20f / 22f))
}

@Stable
internal class DetailHeadingState(val scroll: ScrollState, val density: Float, val gap: Float) {
    var titleSize by mutableStateOf(IntSize.Zero)
    val titleHeight: Float get() = if (titleSize.height > 0) titleSize.height / density else 150f
    private val frameState = derivedStateOf {
        detailHeadingFrame(scroll.value / density,
            if (titleSize.width > 0) titleSize.width / density else 520f, titleHeight, gap)
    }
    val frame: DetailHeadingFrame get() = frameState.value
    val backdropProgress: Float get() = (scroll.value / density / 600f).coerceIn(0f, 1f)
}

@Composable
internal fun rememberDetailHeading(scroll: ScrollState, gap: Float): DetailHeadingState {
    val density = LocalDensity.current.density
    return remember(scroll, density, gap) { DetailHeadingState(scroll, density, gap) }
}


/** Directional contrast recedes as the fixed artwork becomes uniformly dark and blurred. */
@Composable
internal fun rememberDetailScrims(heading: DetailHeadingState, series: Boolean = false, trailer: app.zmby.ui.components.TrailerPlayer? = null): Scrims = remember(heading, series, trailer) {
    Scrims(
        horizontal = if (series) listOf(0f to 0.96f, 0.4f to 0.75f, 0.75f to 0.15f)
            else listOf(0f to 0.97f, 0.4f to 0.8f, 0.75f to 0.15f),
        bottomFrom = (if (series) 300f else 360f) / 1080f,
        bottomStops = listOf(0f to 0f, 0.55f to 0.55f, 1f to 0.9f),
        strength = { 1f - heading.backdropProgress },
        dim = { 0.35f + 0.23f * heading.backdropProgress },
        // Lighter while the trailer shows, so the stage is not darkened (TrailerDetail.dc.html; ui-spec §2.23).
        lighter = listOf(0f to 0.9f, 0.36f to 0.62f, 0.64f to 0f),
        lightness = { trailer?.alpha?.value ?: 0f },
    )
}

/** These two slots reserve the original header geometry; its title and metadata are drawn once above. */
@Composable
internal fun DetailHeadingSlots(heading: DetailHeadingState) {
    Spacer(Modifier.height(heading.titleHeight.dp))
    Spacer(Modifier.height(34.dp))
}

/** One Logo and metadata row travel from the hero to the glass bar; neither is swapped or reloaded. */
@Composable
internal fun CompactHeader(heading: DetailHeadingState, display: DetailDisplay, meta: String, playLabel: String?, ratings: app.zmby.ratings.ExternalRatings? = null) {
    val density = heading.density
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxWidth().height(140.dp)
            .detailTopGlass { heading.frame.progress })
        Box(Modifier.padding(start = 80.dp)) {
            TitleLogo(display.logo, display.title, maxWidth = 520.dp, maxHeight = 150.dp,
                modifier = Modifier.widthIn(max = 860.dp).heightIn(max = 150.dp)
                    .onSizeChanged { heading.titleSize = it }
                    .graphicsLayer {
                        val frame = heading.frame
                        transformOrigin = TransformOrigin(0f, 0f)
                        translationY = frame.titleY * density
                        scaleX = frame.titleScale
                        scaleY = frame.titleScale
                    })
            Row(Modifier.height(34.dp).widthIn(max = 1200.dp).graphicsLayer {
                val frame = heading.frame
                transformOrigin = TransformOrigin(0f, 0f)
                translationX = frame.metaX * density
                translationY = frame.metaY * density
                scaleX = frame.metaScale
                scaleY = frame.metaScale
            }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(meta, fontSize = 22.sp, color = Z.textPrimary.copy(alpha = 0.78f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                RatingBadges(display, ratings)
            }
        }
        Row(Modifier.align(Alignment.TopEnd).padding(end = 80.dp, top = 56.dp)
            .graphicsLayer { alpha = heading.frame.progress * heading.frame.progress },
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            if (playLabel != null) {
                Icon(Glyph.Play, size = 20.dp, color = Z.textSecondary)
                Text(playLabel, fontSize = 19.sp, color = Z.textSecondary, maxLines = 1)
            }
        }
    }
}

/**
 * Plays the page's trailer behind the header once [DetailViewModel.trailer] finds it (trailer design;
 * ui-spec §2.2, §2.23), with 自动播放预告片 on, once per visit: back from the player it does not start
 * again, nor after the full-screen trailer was opened. As soon as the page scrolls down it pauses and
 * fades back to the still backdrop; at the top it goes on.
 */
@Composable
internal fun DetailTrailerHost(vm: DetailViewModel, trailer: app.zmby.ui.components.TrailerPlayer, heading: DetailHeadingState, full: FullTrailerState) {
    val app = androidx.compose.ui.platform.LocalContext.current.zmby
    val key = "${vm.server}:${vm.itemId}"
    androidx.compose.runtime.LaunchedEffect(vm, trailer) {
        kotlinx.coroutines.flow.combine(vm.trailer, app.settings.trailerSound.flow, app.settings.trailerAutoplay.flow,
            androidx.compose.runtime.snapshotFlow { full.used }) { t, sound, auto, used -> t.takeIf { auto && !used } to sound }
            .collect { (t, sound) ->
                if (t == null) trailer.stop() else trailer.load("$key:${t.source}:${t.videoId}", t, sound)
            }
    }
    androidx.compose.runtime.LaunchedEffect(trailer, heading) {
        androidx.compose.runtime.snapshotFlow { heading.backdropProgress >= TRAILER_HOLD_AT }.collect { trailer.hold(it) }
    }
}

/** The page has scrolled this far towards its blurred backdrop (60 of 600 dp) when the trailer pauses. */
private const val TRAILER_HOLD_AT = 0.1f

/**
 * The header's ratings (ratings design; ui-spec §2.2): IMDb, Tomatometer, Popcornmeter, then the
 * catalogue's own (TMDB, or the server's 「评分」). Each only when known; the external ones arrive
 * after the page, so they appear without moving what is already there.
 */
@Composable
internal fun RatingBadges(display: DetailDisplay, ratings: app.zmby.ratings.ExternalRatings?) {
    ratingBadges(display.rating, display.ratingTmdb, ratings).forEach { b ->
        app.zmby.ui.components.RatingBadge(b.value, label = b.label, glyph = b.glyph, spoken = b.spoken)
    }
}

/** One badge of [RatingBadges]. */
internal data class RatingBadgeModel(val value: String, val label: String? = null, val glyph: app.zmby.ui.components.Glyph? = null, val spoken: String)

/** The badges in order; percentages as Rotten Tomatoes shows them, ratings to one decimal. */
internal fun ratingBadges(own: Double?, ownTmdb: Boolean, ratings: app.zmby.ratings.ExternalRatings?): List<RatingBadgeModel> = buildList {
    ratings?.imdb?.let { add(RatingBadgeModel("%.1f".format(java.util.Locale.ROOT, it), label = "IMDb", spoken = "IMDb 评分 %.1f".format(java.util.Locale.ROOT, it))) }
    ratings?.tomatometer?.let { t ->
        add(RatingBadgeModel("${t.percent}%", glyph = if (t.fresh) app.zmby.ui.components.Glyph.Tomato else app.zmby.ui.components.Glyph.Splat,
            spoken = "烂番茄新鲜度 ${t.percent}%${if (t.certified) "，认证新鲜" else if (t.fresh) "，新鲜" else "，腐烂"}"))
    }
    ratings?.popcornmeter?.let { p ->
        add(RatingBadgeModel("${p.percent}%", glyph = if (p.fresh) app.zmby.ui.components.Glyph.Popcorn else app.zmby.ui.components.Glyph.PopcornSpilled,
            spoken = "爆米花指数 ${p.percent}%"))
    }
    own?.takeIf { it > 0 }?.let {
        val label = if (ownTmdb) "TMDB" else "评分"
        add(RatingBadgeModel("%.1f".format(java.util.Locale.ROOT, it), label = label, spoken = "$label %.1f".format(java.util.Locale.ROOT, it)))
    }
}
