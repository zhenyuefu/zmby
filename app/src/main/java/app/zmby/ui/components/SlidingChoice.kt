package app.zmby.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.*
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import app.zmby.ui.theme.Z

/** One focus stop: left/right moves a single thumb, OK toggles the selection. */
@Composable
fun SlidingChoice(first: String, second: String, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier, focusKey: String = "restoreMode") {
    val position by animateFloatAsState(selected.coerceIn(0,1).toFloat(), tween(160), label = "choiceThumb")
    val shape = RoundedCornerShape(50)
    Focusable({ onSelect(1-selected) }, modifier
        .onPreviewKeyEvent { event ->
            if(event.type != KeyEventType.KeyDown) false else when(event.key) {
                Key.DirectionLeft -> { onSelect(0); true }
                Key.DirectionRight -> { onSelect(1); true }
                else -> false
            }
        }.semantics { stateDescription = if(selected == 0) first else second },
        shape = shape, focusedScale = 1.02f, outline = 3.dp, shadow = 0.dp, focusKey = focusKey, label = "方式") {
        BoxWithConstraints(Modifier.width(360.dp).height(60.dp).well(shape).padding(5.dp)) {
            val segment = maxWidth / 2
            Box(Modifier.offset(x = segment * position).width(segment).fillMaxHeight().background(Z.focusFill,shape))
            Row(Modifier.fillMaxSize()) {
                listOf(first,second).forEachIndexed { index,text ->
                    Box(Modifier.weight(1f).fillMaxHeight(),contentAlignment = Alignment.Center) {
                        Text(text,fontSize = 20.sp,color = if(selected == index) Z.onFocus else Z.textPrimary)
                    }
                }
            }
        }
    }
}
