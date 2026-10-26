package app.zmby.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import app.zmby.store.CastEntry
import app.zmby.store.CastRules
import app.zmby.store.CastUnit
import app.zmby.ui.Nav
import app.zmby.ui.Route
import app.zmby.ui.components.Badge
import app.zmby.ui.components.Focusable
import app.zmby.ui.components.RowTitle
import app.zmby.ui.components.glass
import app.zmby.ui.components.horizontalRow
import app.zmby.ui.theme.Z
import app.zmby.zmby
import coil.compose.AsyncImage
import kotlinx.coroutines.launch

/**
 * A round picture with the name's first character on a glass disc while it loads, when it fails or
 * when there is none — never an empty circle (same rule as the title Logo).
 */
@Composable
internal fun Avatar(name: String, url: String?, size: Dp, focused: Boolean, letterSize: Int) {
    var loaded by remember(url) { mutableStateOf(false) }
    Box(
        Modifier
            .size(size)
            .then(if (focused) Modifier.shadow(36.dp, CircleShape, clip = false, ambientColor = Color.Black, spotColor = Color.Black) else Modifier)
            .clip(CircleShape)
            .then(if (focused) Modifier.border(4.dp, Z.focusFill, CircleShape) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        if (!loaded) {
            Box(Modifier.fillMaxSize().glass(CircleShape, shadow = false), contentAlignment = Alignment.Center) {
                Text(CastRules.initial(name), fontSize = letterSize.sp, fontWeight = FontWeight.Bold)
            }
        }
        if (url != null) {
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                onSuccess = { loaded = true },
                onError = { loaded = false },
                modifier = Modifier.fillMaxSize().background(Color.Transparent),
            )
        }
    }
}
