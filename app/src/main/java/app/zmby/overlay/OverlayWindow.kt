package app.zmby.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.AbstractComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.LocalTextStyle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme
import app.zmby.log.ZLog
import app.zmby.ui.theme.Z
import app.zmby.ui.theme.Z as Tokens

/** Design pixels (1920 wide, ui-spec §1) to screen pixels, for windows that are not full screen. */
fun designScale(context: Context): Float {
    val metrics = context.resources.displayMetrics
    return maxOf(metrics.widthPixels, metrics.heightPixels) / Z.DESIGN_WIDTH
}

/** `TYPE_APPLICATION_OVERLAY` layout for a window over the system player. */
fun overlayParams(focusable: Boolean, gravity: Int = Gravity.FILL, x: Int = 0, y: Int = 0, fullScreen: Boolean = !focusable): WindowManager.LayoutParams {
    val size = if (fullScreen) WindowManager.LayoutParams.MATCH_PARENT else WindowManager.LayoutParams.WRAP_CONTENT
    var flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
        WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
    // The passive layer never takes keys: they stay with the player (overlay research §2).
    if (!focusable) flags = flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
    return WindowManager.LayoutParams(size, size, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, flags, PixelFormat.TRANSLUCENT).apply {
        this.gravity = gravity
        this.x = x
        this.y = y
        title = if (focusable) "zmby overlay focus" else "zmby overlay passive"
    }
}

/** Adds and removes one overlay window; every failure (permission revoked meanwhile) is logged and swallowed. */
class OverlayWindow(private val context: Context, private val params: WindowManager.LayoutParams) {
    private val windows = context.getSystemService(WindowManager::class.java)
    var view: View? = null
        private set

    val shown: Boolean get() = view != null

    fun show(make: () -> View): Boolean {
        if (view != null) return true
        if (!OverlayPermission.granted(context)) return false
        val v = make()
        return runCatching { windows.addView(v, params) }
            .onFailure { ZLog.w(TAG, "overlay window not added", it) }
            .map { view = v; true }
            .getOrDefault(false)
    }

    fun hide() {
        val v = view ?: return
        view = null
        runCatching { windows.removeView(v) }.onFailure { ZLog.w(TAG, "overlay window not removed", it) }
        (v as? OverlayComposeView)?.dispose()
    }

    private companion object { const val TAG = "Overlay" }
}

/**
 * A ComposeView that lives in an overlay window: no activity, so it brings its own lifecycle and
 * saved state, and the design density (1920 design pixels across the screen). (`Z` inside a View
 * subclass is View.Z, hence [Tokens].)
 */
@android.annotation.SuppressLint("ViewConstructor") // Created in code for one overlay window, never inflated.
class OverlayComposeView(context: Context, private val content: @Composable () -> Unit) : AbstractComposeView(context) {
    private val owner = Owner()
    private val scale = designScale(context)

    init {
        setViewTreeLifecycleOwner(owner)
        setViewTreeSavedStateRegistryOwner(owner)
        owner.start()
    }

    @Composable
    override fun Content() {
        MaterialTheme(colorScheme = darkColorScheme(background = Tokens.bg, surface = Tokens.bg, onSurface = Tokens.textPrimary, primary = Tokens.focusFill)) {
            CompositionLocalProvider(
                LocalDensity provides Density(scale, 1f),
                LocalContentColor provides Tokens.textPrimary,
                LocalTextStyle provides TextStyle(fontSize = 22.sp),
            ) { content() }
        }
    }

    fun dispose() {
        disposeComposition()
        owner.stop()
    }

    private class Owner : LifecycleOwner, SavedStateRegistryOwner {
        private val registry = LifecycleRegistry(this)
        private val saved = SavedStateRegistryController.create(this)
        override val lifecycle: Lifecycle get() = registry
        override val savedStateRegistry: SavedStateRegistry get() = saved.savedStateRegistry

        fun start() {
            saved.performRestore(null)
            registry.currentState = Lifecycle.State.RESUMED
        }

        fun stop() {
            registry.currentState = Lifecycle.State.DESTROYED
        }
    }
}
