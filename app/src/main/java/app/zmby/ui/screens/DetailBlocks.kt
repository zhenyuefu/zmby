package app.zmby.ui.screens

import androidx.core.graphics.scale
import androidx.core.graphics.get
import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import app.zmby.catalog.CatalogPerson
import app.zmby.data.ServerItem
import app.zmby.media.SubtitleChip
import app.zmby.media.audioSummary
import app.zmby.media.hdrBadge
import app.zmby.media.subtitleChips
import app.zmby.media.versionTitle
import app.zmby.media.audioFormat
import app.zmby.media.defaultAudio
import app.zmby.playback.TrackChoice
import app.zmby.store.ImageRef
import app.zmby.ui.Nav
import app.zmby.ui.Route
import app.zmby.ui.components.Focusable
import app.zmby.ui.components.Glyph
import app.zmby.ui.components.Icon
import app.zmby.ui.components.LocalBackdrop
import app.zmby.ui.components.RowTitle
import app.zmby.ui.components.TitleLogo
import app.zmby.ui.components.horizontalRow
import app.zmby.ui.theme.Z
import app.zmby.zmby

/*
 * Detail blocks: bounded track lists, retained artwork rows and compact summaries (ui-spec §2.14).
 */

/** One person in 演职人员, from the catalogue or from the server's own People. */
data class CastCard(val key: String, val name: String, val role: String, val image: String?, val open: () -> Unit)

internal fun catalogCastCards(nav: Nav, cast: List<CatalogPerson>): List<CastCard> =
    cast.map { p -> CastCard("catalogCast:${p.key}", p.name, p.role, p.image) { nav.go(Route.CatalogPerson(p.source, p.id)) } }

/** The server's People of [ref]: actors, directors and writers, one card per person. */
internal fun serverCastCards(app: app.zmby.ZmbyApp, nav: Nav, ref: ServerItem): List<CastCard> =
    ref.item.people.filter { it.id != null && !it.name.isNullOrBlank() && it.type in setOf("Actor", "Director", "Writer") }
        .groupBy { it.id }.values.map { same ->
            // Actors show their part; directors and writers their job (「导演 · 编剧」).
            val role = same.firstOrNull { it.type == "Actor" }?.role
                ?: same.mapNotNull { p -> when (p.type) { "Director" -> "导演"; "Writer" -> "编剧"; else -> null } }.distinct().joinToString(" · ")
            same.first().copy(role = role)
        }
        .map { p ->
            CastCard(
                "serverCast:${ref.server}:${p.id}",
                p.name.orEmpty(),
                p.role.orEmpty(),
                p.primaryImageTag?.let { app.library.imageUrl(ImageRef(ref.server, p.id!!, it), "Primary", 280) },
            ) {
                val tmdb = p.providerIds.entries.firstOrNull { it.key.equals("tmdb", true) }?.value
                if (tmdb?.toLongOrNull()?.let { it > 0 } == true) nav.go(Route.CatalogPerson("tmdb", tmdb))
                else nav.go(Route.Person("local:${ref.server}:${p.id}", ref.server, p.id!!))
            }
        }

/** 演职人员: 160-wide cells (140 avatar, name, role) in one row, always with their text; [entry] on the first. */
@Composable
internal fun CastBlock(cards: List<CastCard>, entry: FocusRequester) {
    Column(Modifier.padding(vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Box(Modifier.padding(horizontal = 80.dp)) { RowTitle("演职人员", trailing = "共 ${cards.size} 人") }
        LazyRow(Modifier.horizontalRow(), contentPadding = PaddingValues(horizontal = 80.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            itemsIndexed(cards, key = { _, c -> c.key }, contentType = { _, _ -> "person" }) { i, c ->
                Focusable(c.open, Modifier.width(160.dp).then(if (i == 0) Modifier.focusRequester(entry) else Modifier), origin = TransformOrigin(0.5f, 0f), outline = 0.dp, shadow = 0.dp, focusKey = c.key, label = c.name) { focused ->
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Avatar(c.name, c.image, 140.dp, focused, 52)
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(c.name, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (c.role.isNotBlank()) Text(c.role, fontSize = 18.sp, color = Z.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}

/** 版本 collapsed: 「家里 NAS · 2160p REMUX · DV P7 · TrueHD Atmos · 内网」. */
internal fun versionSummary(v: Version): String = listOfNotNull(
    v.server.name,
    versionTitle(v.source),
    hdrBadge(v.source.videoStream),
    defaultAudio(v.source)?.let(::audioFormat),
    v.server.lineName,
).joinToString(" · ")

/** 「4K · 共 7 个 · 还在查找 2 台」 or 「· n 台未响应」. */
internal fun versionCount(state: VersionsState, selected: Version?): String = listOfNotNull(
    selected?.tier?.label,
    "共 ${state.all.size} 个",
    state.searching.takeIf { it > 0 }?.let { "还在查找 $it 台" },
    state.unresponsive.size.takeIf { it > 0 && state.searching == 0 }?.let { "$it 台未响应" },
).joinToString(" · ")

/**
 * 音轨与字幕 collapsed (112 high): the current audio with the other languages, then subtitle chips —
 * the current one white with ✓, the rest by preferred language, other Chinese, English, the rest;
 * as many as fit, the last showing 「+n」 when they do not.
 */
@Composable
internal fun RowScope.TrackSummary(v: Version, choice: TrackChoice) {
    val tracks = rememberEffectiveTracks(v, choice)
    val preferred = LocalContext.current.zmby.settings.subtitleLanguage.value
    val (audio, more) = remember(v.source, tracks.audio) { audioSummary(v.source, tracks.audio) }
    val chips = remember(v.source, tracks.subtitle, preferred) { subtitleChips(v.source, tracks.subtitle, preferred) }
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                buildString {
                    append(audio ?: "没有音轨信息")
                    if (more != null) append("　").append(more)
                },
                fontSize = 20.sp, color = Color.White.copy(alpha = 0.75f), maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        if (v.source.subtitleStreams.isNotEmpty()) SubtitleChipRow(chips)
    }
}

/** Subtitle chips cut to the width they get; the last visible one becomes 「+n」 when some do not fit. */
@Composable
private fun SubtitleChipRow(chips: List<SubtitleChip>) {
    androidx.compose.ui.layout.SubcomposeLayout(Modifier.fillMaxWidth().height(32.dp)) { constraints ->
        val gap = 8.dp.roundToPx()
        val measured = mutableListOf<androidx.compose.ui.layout.Placeable>()
        var x = 0
        var fit = 0
        for ((i, chip) in chips.withIndex()) {
            val p = subcompose(i) { Chip(chip) }.first().measure(constraints.copy(minWidth = 0))
            measured += p
            if (x + p.width > constraints.maxWidth) break
            x += p.width + gap
            fit++
        }
        val placed = mutableListOf<Pair<androidx.compose.ui.layout.Placeable, Int>>()
        if (fit < chips.size) {
            // Room for 「+n」: drop chips from the end until it fits after them.
            var keep = fit
            while (keep > 1) {
                val more = subcompose("more$keep") { MoreChip(chips.size - keep) }.first().measure(constraints.copy(minWidth = 0))
                val used = measured.take(keep).sumOf { it.width + gap }
                if (used + more.width <= constraints.maxWidth) {
                    var px = 0
                    measured.take(keep).forEach { placed += it to px; px += it.width + gap }
                    placed += more to px
                    break
                }
                keep--
            }
            if (placed.isEmpty()) measured.take(1).forEach { placed += it to 0 }
        } else {
            var px = 0
            measured.forEach { placed += it to px; px += it.width + gap }
        }
        layout(constraints.maxWidth, 32.dp.roundToPx()) { placed.forEach { (p, px) -> p.place(px, 0) } }
    }
}

@Composable
private fun Chip(c: SubtitleChip) {
    val shape = RoundedCornerShape(percent = 50)
    Row(
        Modifier.height(32.dp).background(if (c.current) Color.White else Color.White.copy(alpha = 0.08f), shape).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val fg = if (c.current) Z.onFocus else Color.White.copy(alpha = 0.88f)
        if (c.current && c.embyIndex >= 0) Icon(Glyph.Check, size = 16.dp, color = fg, strokeWidth = 3f)
        Text(c.label, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = fg, maxLines = 1)
        if (c.detail.isNotEmpty()) Text(c.detail, fontSize = 16.sp, fontWeight = FontWeight.Medium, color = fg.copy(alpha = 0.6f), maxLines = 1)
    }
}

@Composable
private fun MoreChip(n: Int) {
    Box(Modifier.height(32.dp).background(Color.White.copy(alpha = 0.08f), RoundedCornerShape(percent = 50)).padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
        Text("+$n", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Color.White.copy(alpha = 0.88f))
    }
}

/** The average colour of a (blurred) backdrop, for the compact header's gradient. */
internal fun averageColor(image: ImageBitmap?): Color? = image?.let {
    runCatching {
        val one = it.asAndroidBitmap().scale(1, 1, true)
        Color(one[0, 0])
    }.getOrNull()
}

/**
 * The compact header (DetailRecs.dc.html top): once focus has left the header and the page scrolled
 * down, a fixed 140-high bar with a small Logo (at most 64 high, else the title in 30/800), the meta
 * line and, on the right, the play button's text (not focusable). Its gradient runs from the
 * backdrop's main colour to the page colour.
 */
@Composable
internal fun CompactHeader(visible: Boolean, display: DetailDisplay, meta: String, playLabel: String?) {
    val backdrop = LocalBackdrop.current?.blurred
    val tint = remember(backdrop) { averageColor(backdrop) } ?: Color(0xFF2B2419)
    AnimatedVisibility(visible, enter = fadeIn(tween(200)), exit = fadeOut(tween(200))) {
        Box(
            Modifier.fillMaxWidth().height(140.dp)
                .background(Brush.verticalGradient(listOf(tint.copy(alpha = 0.9f), Z.bg.copy(alpha = 0.95f))))
                .background(Brush.verticalGradient(0.99f to Color.Transparent, 1f to Color.White.copy(alpha = 0.06f))),
        ) {
            Row(
                Modifier.padding(start = 80.dp, end = 80.dp, top = 36.dp).height(68.dp).fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                Box(Modifier.widthIn(max = 420.dp), contentAlignment = Alignment.CenterStart) {
                    TitleLogo(display.logo, display.title, maxWidth = 300.dp, maxHeight = 64.dp, compact = true)
                }
                Text(meta, fontSize = 20.sp, color = Color.White.copy(alpha = 0.7f), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (playLabel != null) {
                    Icon(Glyph.Play, size = 20.dp, color = Color.White.copy(alpha = 0.7f))
                    Text(playLabel, fontSize = 19.sp, color = Color.White.copy(alpha = 0.7f), maxLines = 1)
                }
            }
        }
    }
}
