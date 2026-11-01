package app.zmby.overlay

import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import android.provider.Settings

/**
 * 「允许显示在其他应用的上层」 (overlay research, 2026-10-31): on the Z9X 8K the package URI opens the
 * app list, not zmby's own page, so the settings screen tells the user to find zmby there and checks
 * again when it comes back.
 */
object OverlayPermission {
    fun granted(context: Context): Boolean = Settings.canDrawOverlays(context)

    fun settingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, "package:${context.packageName}".toUri())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
