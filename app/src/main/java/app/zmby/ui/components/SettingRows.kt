package app.zmby.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import app.zmby.ui.theme.Z

/*
 * Settings rows (Settings.dc.html): a group title (`.gt`) over a card of `.row.set` rows. Controls
 * are only segments, toggles, or a value with ›.
 */

@Composable
fun SettingsGroup(title: String, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Z.textTertiary, letterSpacing = 1.sp, modifier = Modifier.padding(horizontal = 24.dp))
        Column(Modifier.fillMaxWidth().groupCard(RoundedCornerShape(28.dp)).padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp), content = content)
    }
}

private val SetPadding = PaddingValues(start = 24.dp, end = 14.dp, top = 8.dp, bottom = 8.dp)

@Composable
private fun SettingLabel(title: String, sub: String?, mono: Boolean, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(title, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (sub != null) Text(sub, fontSize = 18.sp, color = secondaryText(), fontFamily = if (mono) FontFamily.Monospace else null, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** A row that toggles on OK; the whole row takes focus. */
@Composable
fun ToggleSetting(title: String, checked: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier, sub: String? = null, mono: Boolean = false, dimmed: Boolean = false) {
    SelectableRow(
        onToggle,
        modifier.defaultMinSize(minHeight = 70.dp),
        showCheck = false,
        dimmed = dimmed,
        radius = 20.dp,
        padding = SetPadding,
        label = "$title ${if (checked) "开" else "关"}",
        focusKey = title,
    ) {
        SettingLabel(title, sub, mono, Modifier.weight(1f))
        GlassToggle(checked)
    }
}

/** A row with a segmented choice; the segments take focus (left/right), confirmed with OK. */
@Composable
fun ChoiceSetting(title: String, options: List<String>, selected: Int, onSelect: (Int) -> Unit, sub: String? = null, dim: Set<Int> = emptySet()) {
    Row(
        Modifier.fillMaxWidth().defaultMinSize(minHeight = 70.dp).padding(SetPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SettingLabel(title, sub, false, Modifier.weight(1f))
        SegmentedWell(
            items = options.mapIndexed { i, o -> Segment(o, dim = i in dim) },
            selected = selected,
            onSelect = onSelect,
            selectOnFocus = false,
            segHeight = 44.dp,
            fontSize = 18,
            horizontalPadding = 22.dp,
            labelPrefix = title,
            focusKeyPrefix = title,
        )
    }
}

/** A row showing a value; with [onClick] it opens something (›). [glyph] is the row's single-colour icon. */
@Composable
fun ValueSetting(title: String, value: String?, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, sub: String? = null, mono: Boolean = false, glyph: Glyph? = null) {
    val body: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {
        if (glyph != null) Icon(glyph, size = 26.dp, strokeWidth = 2.2f)
        SettingLabel(title, sub, mono, Modifier.weight(1f))
        if (value != null) Text(value, fontSize = 20.sp, color = secondaryText(), maxLines = 1, fontFamily = if (mono && sub == null) FontFamily.Monospace else null)
        if (onClick != null) Icon(Glyph.Chevron, size = 20.dp, strokeWidth = 2.4f)
    }
    if (onClick == null) {
        Row(
            modifier.fillMaxWidth().defaultMinSize(minHeight = 70.dp).padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            content = body,
        )
    } else {
        SelectableRow(
            onClick,
            modifier.defaultMinSize(minHeight = 70.dp),
            showCheck = false,
            radius = 20.dp,
            padding = PaddingValues(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 8.dp),
            label = title,
        ) { body() }
    }
}
