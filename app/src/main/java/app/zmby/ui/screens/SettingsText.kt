package app.zmby.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import app.zmby.data.InputField
import app.zmby.ui.components.GlassButton
import app.zmby.ui.components.Glyph
import app.zmby.ui.components.well
import app.zmby.ui.theme.Z
import kotlinx.coroutines.android.awaitFrame

private val Pill = RoundedCornerShape(percent = 50)

/** What a 文本项 shows for [value] (ui-spec §2.20): 「未设置」, the masked secret, or the text itself. */
internal fun textSettingValue(value: String, secret: Boolean): String = when {
    value.isBlank() -> "未设置"
    secret -> "已设置 · ••••••••"
    else -> value
}

/**
 * 文本项 (ui-spec §2.20): the name, the current value (addresses and patterns in monospace, secrets
 * masked, empty as a light 「未设置」) and two 52-high glass buttons 「输入」 and 「手机输入」.
 * 「输入」 opens the row in place: a 220-wide name, a sunken field with the white ring, prefilled
 * with the current value, then 保存 and 取消; [save] returns why the value is rejected (shown in amber
 * under the row) or null once stored. Saving or cancelling closes the row and puts focus back on 「输入」.
 * 「手机输入」 opens the QR panel (§2.13); its 「用遥控器输入」 opens this row's field instead.
 */
@Composable
internal fun TextSetting(
    title: String,
    value: String,
    phone: InputField?,
    save: (String) -> String?,
    type: KeyboardType = KeyboardType.Ascii,
    mono: Boolean = false,
    secret: Boolean = false,
    placeholder: String = "",
    /** Shown instead of [value] (an address without its scheme). */
    display: (String) -> String = { it },
) {
    var editing by rememberSaveable(title) { mutableStateOf(false) }
    var phoneOpen by remember { mutableStateOf(false) }
    val inputButton = remember { FocusRequester() }
    var refocus by remember { mutableIntStateOf(0) }
    LaunchedEffect(refocus) {
        if (refocus == 0) return@LaunchedEffect
        awaitFrame()
        runCatching { inputButton.requestFocus() }
    }
    if (editing) {
        TextSettingEditor(title, value, type, secret, mono, placeholder, save, onClose = { editing = false; refocus++ })
    } else {
        Row(
            Modifier.fillMaxWidth().defaultMinSize(minHeight = 70.dp).padding(start = 24.dp, end = 14.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(title, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            val shown = textSettingValue(display(value), secret)
            Text(
                shown,
                fontSize = if (mono && value.isNotBlank() && !secret) 19.sp else 20.sp,
                fontFamily = if (mono && value.isNotBlank() && !secret) FontFamily.Monospace else null,
                color = if (value.isBlank()) Color.White.copy(alpha = 0.4f) else Z.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 560.dp),
            )
            GlassButton("输入", { editing = true }, Modifier.focusRequester(inputButton), glyph = Glyph.Keyboard,
                height = 52.dp, horizontalPadding = 22.dp, fontSize = 19, label = "$title 输入")
            if (phone != null) GlassButton("手机输入", { phoneOpen = true }, glyph = Glyph.Phone,
                height = 52.dp, horizontalPadding = 22.dp, fontSize = 19, label = "$title 手机输入")
        }
    }
    if (phoneOpen && phone != null) {
        PhoneInputDialog(phone, onDismiss = { phoneOpen = false; refocus++ }, onRemote = { phoneOpen = false; editing = true })
    }
}

@Composable
private fun TextSettingEditor(
    title: String,
    value: String,
    type: KeyboardType,
    secret: Boolean,
    mono: Boolean,
    placeholder: String,
    save: (String) -> String?,
    onClose: () -> Unit,
) {
    var draft by remember { mutableStateOf(value) }
    var error by remember { mutableStateOf<String?>(null) }
    val field = remember { FocusRequester() }
    val saveButton = remember { FocusRequester() }
    LaunchedEffect(Unit) { awaitFrame(); runCatching { field.requestFocus() } }
    BackHandler(onBack = onClose)
    fun submit() {
        error = save(draft.trim())
        if (error == null) onClose()
    }
    Column(Modifier.fillMaxWidth().background(Color.White.copy(alpha = 0.10f), RoundedCornerShape(20.dp))) {
        Row(
            Modifier.fillMaxWidth().padding(start = 24.dp, end = 14.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(title, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(220.dp))
            Box(
                Modifier.weight(1f).height(58.dp).well(Pill, alpha = 0.38f).border(3.dp, Z.focusFill, Pill).padding(horizontal = 24.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                val style = TextStyle(
                    fontSize = if (mono) 19.sp else 21.sp,
                    fontFamily = if (mono) FontFamily.Monospace else null,
                    fontWeight = FontWeight.SemiBold,
                    color = Z.textPrimary,
                )
                if (draft.isEmpty() && placeholder.isNotEmpty()) Text(placeholder, style = style, color = Z.textTertiary, maxLines = 1)
                BasicTextField(
                    value = draft,
                    onValueChange = { draft = it; error = null },
                    singleLine = true,
                    textStyle = style,
                    cursorBrush = SolidColor(Z.focusFill),
                    keyboardOptions = KeyboardOptions(keyboardType = type, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                    visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(field)
                        // The field keeps Left and Right for the cursor; Down goes on to 保存.
                        .onPreviewKeyEvent { e ->
                            if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                            when (e.key) {
                                Key.DirectionDown -> { runCatching { saveButton.requestFocus() }; true }
                                Key.DirectionUp -> true
                                else -> false
                            }
                        },
                )
            }
            GlassButton("保存", ::submit, Modifier.focusRequester(saveButton), height = 58.dp, horizontalPadding = 22.dp, fontSize = 19, label = "$title 保存")
            GlassButton("取消", onClose, height = 58.dp, horizontalPadding = 22.dp, fontSize = 19, label = "$title 取消")
        }
        error?.let { Text(it, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Z.warn, modifier = Modifier.padding(start = 260.dp, bottom = 12.dp)) }
    }
}
