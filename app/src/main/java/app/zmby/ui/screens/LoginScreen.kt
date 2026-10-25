package app.zmby.ui.screens

import app.zmby.ui.userMessage
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import app.zmby.ui.components.Focusable
import app.zmby.ui.rememberInitialFocus
import app.zmby.ui.components.GlassButton
import app.zmby.ui.components.Glyph
import app.zmby.ui.components.ZmbyLogo
import app.zmby.ui.components.groupCard
import app.zmby.ui.components.well
import app.zmby.ui.theme.Z
import app.zmby.zmby
import kotlinx.coroutines.launch

/** Not in the design set yet: a plain form in the same tokens, typed with the system IME. */
@Composable
fun LoginScreen(onDone: () -> Unit, onImport: (() -> Unit)?) {
    val library = LocalContext.current.zmby.library
    val scope = rememberCoroutineScope()
    var url by rememberSaveable { mutableStateOf("") }
    var user by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            Modifier.width(760.dp).groupCard(RoundedCornerShape(28.dp)).padding(48.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                ZmbyLogo()
                Text("添加 Emby 服务器", fontSize = 22.sp, color = Z.textSecondary)
            }
            fun submit() {
                if (busy) return
                busy = true
                error = null
                scope.launch {
                    runCatching { library.addServer(url, user, password) }
                        .onSuccess { onDone() }
                        .onFailure { error = "登录失败：${it.userMessage()}" }
                    busy = false
                }
            }
            Field("服务器地址", url, { url = it }, KeyboardType.Uri, placeholder = "192.168.1.10:8096", initialFocus = rememberInitialFocus(Unit))
            Field("用户名", user, { user = it }, KeyboardType.Text)
            Field("密码", password, { password = it }, KeyboardType.Password, secret = true, last = true, onSubmit = ::submit)
            error?.let { Text(it, fontSize = 20.sp, color = Z.warn) }
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                GlassButton(if (busy) "正在登录…" else "登录", ::submit)
                if (onImport != null) GlassButton("扫码导入", onImport, glyph = Glyph.Qr)
            }
        }
    }
}

/**
 * A text field that works with a remote: at rest it is an ordinary focusable box, so D-pad up/down
 * moves between fields and the system keyboard does not pop up on its own; OK starts editing, the
 * keyboard's next/done (or leaving the field) ends it.
 */
@Composable
internal fun Field(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    type: KeyboardType,
    placeholder: String = "",
    secret: Boolean = false,
    last: Boolean = false,
    onSubmit: () -> Unit = {},
    /** Initial focus for the screen, when this is its first field. */
    initialFocus: FocusRequester? = null,
) {
    var editing by remember { mutableStateOf(false) }
    val box = remember { FocusRequester() }
    val input = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val shape = RoundedCornerShape(percent = 50)
    val style = TextStyle(fontSize = 26.sp, color = Z.textPrimary, fontWeight = FontWeight.SemiBold)
    fun finish(next: Boolean) {
        editing = false
        if (next) focusManager.moveFocus(FocusDirection.Down) else box.requestFocus()
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(label, fontSize = 19.sp, color = Z.textTertiary)
        if (editing) {
            var hadFocus by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) { input.requestFocus() }
            BackHandler { finish(next = false) }
            Box(
                Modifier.fillMaxWidth().height(72.dp).well(shape).border(3.dp, Z.focusFill, shape).padding(horizontal = 28.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                BasicTextField(
                    value = value,
                    onValueChange = onChange,
                    singleLine = true,
                    textStyle = style,
                    cursorBrush = SolidColor(Z.focusFill),
                    keyboardOptions = KeyboardOptions(keyboardType = type, imeAction = if (last) ImeAction.Done else ImeAction.Next),
                    keyboardActions = KeyboardActions(
                        onNext = { finish(next = true) },
                        onDone = { finish(next = false); onSubmit() },
                    ),
                    visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(input)
                        // onFocusChanged also fires once before the field gets focus; only a real loss ends editing.
                        .onFocusChanged { if (it.isFocused) hadFocus = true else if (hadFocus) editing = false }
                        // Text fields swallow the D-pad; leave the field instead, like a TV form should.
                        .onPreviewKeyEvent { e ->
                            if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                            when (e.key) {
                                Key.DirectionDown -> { finish(next = true); true }
                                Key.DirectionUp -> { editing = false; focusManager.moveFocus(FocusDirection.Up); true }
                                else -> false
                            }
                        },
                )
            }
        } else {
            Focusable({ editing = true }, Modifier.focusRequester(box).then(if (initialFocus != null) Modifier.focusRequester(initialFocus) else Modifier), shape = shape, focusedScale = 1.02f, outline = 3.dp, shadow = 0.dp, label = label) {
                Box(
                    Modifier.fillMaxWidth().height(72.dp).well(shape).padding(horizontal = 28.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    val shown = if (secret) "•".repeat(value.length) else value
                    Text(shown.ifEmpty { placeholder }, style = style, color = if (shown.isEmpty()) Z.textTertiary else Z.textPrimary, maxLines = 1)
                }
            }
        }
    }
}
