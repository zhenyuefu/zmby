package app.zmby.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.currentCompositeKeyHash
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory

/**
 * One screen on the back stack. Its composition is dropped while another screen is on top; what
 * must survive until the user comes back lives here: loaded data ([rememberRetained]) and the
 * element that had focus ([focus]). Scroll positions survive through rememberSaveable.
 */
class NavEntry(val id: Long, val route: Route) : ViewModelStoreOwner {
    internal val retained = HashMap<String, Any?>()
    val focus = FocusMemory()
    /** The screen's ViewModels (state holders); cleared when the entry leaves the stack. */
    override val viewModelStore = ViewModelStore()
}

/**
 * The ViewModel for this screen, created by [create] the first time and kept while the back-stack
 * entry exists; [key] tells apart several of one type on a screen (tabs, servers).
 */
@Composable
inline fun <reified VM : ViewModel> screenModel(key: String? = null, crossinline create: () -> VM): VM =
    viewModel(key = key, factory = viewModelFactory { initializer { create() } })

class FocusMemory {
    /** focusKey of the element focused last on this screen. */
    var lastKey: String? = null

    /** Set when the user returns to the screen; cleared once [lastKey] has focus again or restore gave up. */
    var restorePending by mutableStateOf(false)
    var restoreRequest by mutableIntStateOf(0)
        private set
    var restored = false

    fun beginRestore() {
        restored = false
        restorePending = lastKey != null
        restoreRequest++
    }
}

val LocalNavEntry = staticCompositionLocalOf<NavEntry?> { null }

/**
 * Like [remember], but the value outlives the screen's composition for as long as its back-stack
 * entry exists, so data is on screen at once when the user comes back (and focus can be restored
 * onto it) instead of flashing empty while it reloads. [name] must be unique within the caller.
 */
@Composable
fun <T> rememberRetained(name: String, vararg keys: Any?, init: () -> T): T {
    val entry = LocalNavEntry.current
    // The composite key hash tells apart the same composable at different places (rows, tabs), but it
    // is equal for sibling calls in one function, hence the explicit [name].
    val hash = currentCompositeKeyHash
    return remember(*keys) {
        if (entry == null) {
            init()
        } else {
            val k = "$name/$hash/${keys.contentDeepHashCode()}"
            @Suppress("UNCHECKED_CAST")
            entry.retained.getOrPut(k, init) as T
        }
    }
}
