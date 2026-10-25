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
 * 演职人员 (ui-spec §2.2 4, §2.3 6): the page's last block, one row of 160-wide units; nothing at all
 * when the work has no people. Down from the tracks enters the first unit, up goes back to [up].
 */
@Composable
internal fun CastRow(nav: Nav, server: String, entries: List<CastEntry>, up: FocusRequester?, entry: FocusRequester) {
    val units = remember(entries) { CastRules.units(entries) }
    if (units.isEmpty()) return
    val app = LocalContext.current.zmby
    val scope = rememberCoroutineScope()
    Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Box(Modifier.padding(start = 80.dp)) { RowTitle("演职人员") }
        LazyRow(
            modifier = Modifier
                .horizontalRow()
                // Entering from above lands on the first person, not the one straight below.
                .focusProperties { onEnter = { entry.requestFocus() } }
                .focusGroup(),
            contentPadding = PaddingValues(start = 80.dp, end = 120.dp, bottom = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(28.dp),
        ) {
            itemsIndexed(units, key = { _, u -> u.personKey }, contentType = { _, _ -> "person" }) { i, u ->
                PersonUnit(
                    u,
                    imageUrl = u.image?.let { app.library.imageUrl(it, "Primary", 280) },
                    // By place in the row: the person's key can change on the person page (merged to TMDB).
                    focusKey = "cast:$i",
                    modifier = Modifier
                        .then(if (i == 0) Modifier.focusRequester(entry) else Modifier)
                        .focusProperties { if (up != null) this.up = up },
                    onClick = {
                        scope.launch {
                            // Person ids are per server: open from this server's own id when it has one.
                            val aliases = app.store.aliases(u.personKey)
                            val alias = aliases.firstOrNull { it.serverId == server } ?: aliases.firstOrNull() ?: return@launch
                            nav.go(Route.Person(u.personKey, alias.serverId, alias.personId))
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun PersonUnit(u: CastUnit, imageUrl: String?, modifier: Modifier, focusKey: String, onClick: () -> Unit) {
    // Scale 1.08 from the top centre; the avatar gets the white ring and shadow, the name stays as is.
    Focusable(onClick, modifier.width(160.dp), shape = CircleShape, focusedScale = 1.08f, outline = 0.dp, shadow = 0.dp, origin = TransformOrigin(0.5f, 0f), label = u.name, focusKey = focusKey) { focused ->
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Avatar(u.name, imageUrl, 140.dp, focused, letterSize = 52)
            Text(u.name, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
            when {
                u.badge != null -> Badge(u.badge, fill = true)
                u.role != null -> Text(u.role, fontSize = 18.sp, color = Z.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
            }
        }
    }
}

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
