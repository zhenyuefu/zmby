package app.zmby.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import app.zmby.ui.theme.Z
import coil.compose.AsyncImage

private val Placeholder = Color(0xFF1D2A30)

@Composable
fun Artwork(url: String?, modifier: Modifier = Modifier) {
    Box(modifier.background(Placeholder)) {
        if (url != null) {
            AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
    }
}

/** 16:9 card for 继续观看 and episodes: server label, progress bar, caption below. */
@Composable
fun MediaCard16x9(
    imageUrl: String?,
    onClick: () -> Unit,
    width: Dp = 400.dp,
    title: String? = null,
    topStart: String? = null,
    topEnd: String? = null,
    watched: Boolean = false,
    progress: Float = 0f,
    onFocus: (() -> Unit)? = null,
    focusKey: String? = null,
    modifier: Modifier = Modifier,
    caption: @Composable () -> Unit = {},
) {
    CardFrame(onClick, width, width * 9 / 16, onFocus, caption, label = title ?: topStart, focusKey = focusKey, modifier = modifier) {
        Artwork(imageUrl, Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.45f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.7f))))
        if (topStart != null) GlassChip(topStart, Modifier.align(Alignment.TopStart).padding(14.dp))
        if (watched) WatchedMark(Modifier.align(Alignment.TopStart).padding(14.dp))
        if (topEnd != null) GlassChip(topEnd, Modifier.align(Alignment.TopEnd).padding(12.dp), fontSize = 15, horizontalPadding = 12.dp)
        if (title != null) {
            Text(
                title,
                fontSize = 26.sp,
                fontWeight = FontWeight.ExtraBold,
                lineHeight = 30.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.align(Alignment.BottomStart).padding(start = 18.dp, end = 18.dp, bottom = 34.dp),
            )
        }
        if (progress > 0f) {
            ProgressBar(progress, Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(start = 18.dp, end = 18.dp, bottom = 16.dp))
        }
    }
}

/**
 * Shared card focus treatment (ui-spec §1.2): the whole card, caption included, scales 1.08 from its
 * top-left corner; the 4 px white outline and the large shadow belong to the artwork only.
 */
@Composable
private fun CardFrame(
    onClick: () -> Unit,
    width: Dp,
    height: Dp,
    onFocus: (() -> Unit)?,
    caption: @Composable () -> Unit,
    label: String? = null,
    focusKey: String? = null,
    modifier: Modifier = Modifier,
    art: @Composable BoxScope.() -> Unit,
) {
    val shape = RoundedCornerShape(22.dp)
    Focusable(onClick, modifier.width(width), shape = shape, origin = TransformOrigin(0f, 0f), outline = 0.dp, shadow = 0.dp, onFocus = onFocus, label = label, focusKey = focusKey) { focused ->
        Column {
            Box(
                Modifier
                    .width(width)
                    .height(height)
                    .then(if (focused) Modifier.shadow(40.dp, shape, clip = false, ambientColor = Color.Black.copy(alpha = 0.7f), spotColor = Color.Black.copy(alpha = 0.7f)) else Modifier)
                    .clip(shape)
                    .then(if (focused) Modifier.border(4.dp, Z.focusFill, shape) else Modifier),
            ) {
                // Chips sit on the card's own artwork, not on the page backdrop: plain glass, without
                // the backdrop slice and the per-chip position callback it needs on every scroll frame.
                CompositionLocalProvider(LocalBackdrop provides null) { art() }
            }
            Box(Modifier.padding(top = 14.dp)) { caption() }
        }
    }
}

@Composable
fun WatchedMark(modifier: Modifier = Modifier) {
    Box(modifier.size(30.dp).clip(CircleShape).background(Z.focusFill), contentAlignment = Alignment.Center) {
        Icon(Glyph.Check, size = 18.dp, color = Z.onFocus, strokeWidth = 3f)
    }
}

/** 2:3 poster with an optional version-count label; title and subtitle sit below the art. */
@Composable
fun PosterCard2x3(
    imageUrl: String?,
    title: String,
    onClick: () -> Unit,
    width: Dp = 196.dp,
    topEnd: String? = null,
    subtitle: String? = null,
    onFocus: (() -> Unit)? = null,
    focusKey: String? = null,
    modifier: Modifier = Modifier,
    overlay: @Composable BoxScope.() -> Unit = {},
) {
    CardFrame(onClick, width, width * 3 / 2, onFocus, label = title, focusKey = focusKey, modifier = modifier, caption = {
        Column {
            if (imageUrl != null) {
                Text(title, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (subtitle != null) {
                Text(subtitle, fontSize = 18.sp, color = Z.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }) {
        Artwork(imageUrl, Modifier.fillMaxSize())
        if (imageUrl == null) {
            Text(
                title,
                fontSize = 24.sp,
                fontWeight = FontWeight.ExtraBold,
                lineHeight = 29.sp,
                maxLines = 3,
                modifier = Modifier.align(Alignment.BottomStart).padding(start = 14.dp, end = 14.dp, bottom = 16.dp),
            )
        }
        if (topEnd != null) GlassChip(topEnd, Modifier.align(Alignment.TopEnd).padding(10.dp), fontSize = 15, horizontalPadding = 12.dp)
        overlay()
    }
}

/** Section heading used above rows. */
@Composable
fun RowTitle(text: String, size: Int = 30, trailing: String? = null) {
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(text, fontSize = size.sp, fontWeight = FontWeight.Bold)
        if (trailing != null) Text(trailing, fontSize = 20.sp, color = Z.textSecondary)
    }
}
