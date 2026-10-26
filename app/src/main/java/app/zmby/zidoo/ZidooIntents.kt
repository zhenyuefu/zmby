package app.zmby.zidoo

import androidx.core.net.toUri
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri

object ZidooIntents {

    /** The first Zidoo player component installed on this box, or null off a Zidoo. */
    fun installedPlayer(context: Context): ZidooPlayer.Component? =
        ZidooPlayer.COMPONENTS.firstOrNull { c ->
            // Not Intent.resolveActivity: with an explicit component it returns that component
            // without checking that it exists.
            runCatching { context.packageManager.getActivityInfo(ComponentName(c.pkg, c.activity), 0) }.isSuccess
        }

    /** The explicit Zidoo player intent; launch it with startActivityForResult. */
    fun playerIntent(spec: ZidooLaunchSpec, player: ZidooPlayer.Component): Intent =
        baseIntent(spec)
            .setClassName(player.pkg, player.activity)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)

    /**
     * Any video player off a Zidoo box (development on an emulator or another TV box). Players
     * that speak the MX Player result protocol report the position the same way.
     */
    fun genericIntent(spec: ZidooLaunchSpec): Intent = baseIntent(spec)

    private fun baseIntent(spec: ZidooLaunchSpec): Intent {
        val intent = (if (spec.viewAction) Intent(Intent.ACTION_VIEW) else Intent()).setDataAndType(spec.uri.toUri(), spec.mimeType)
        for ((key, value) in spec.extras) {
            when (value) {
                is Boolean -> intent.putExtra(key, value)
                is Int -> intent.putExtra(key, value)
                is Long -> intent.putExtra(key, value)
                is String -> intent.putExtra(key, value)
                is UriArray -> intent.putExtra(key, value.uris.map(Uri::parse).toTypedArray<android.os.Parcelable>())
                is List<*> -> intent.putExtra(key, value.map { it.toString() }.toTypedArray())
                else -> error("Unsupported extra type for $key: ${value::class}")
            }
        }
        return intent
    }

    /** The player's own subtitle title suffixes in the current locale (see [ZidooPlayerStrings]). */
    fun playerStrings(context: Context, player: ZidooPlayer.Component): ZidooPlayerStrings = runCatching {
        val res = context.packageManager.getResourcesForApplication(player.pkg)
        // These IDs belong to the installed third-party player and cannot be referenced via our R.
        @android.annotation.SuppressLint("DiscouragedApi")
        fun string(name: String) = res.getIdentifier(name, "string", player.pkg).takeIf { it != 0 }?.let(res::getString)
        ZidooPlayerStrings(string("in_text_subtitle"), string("ex_subtitle"))
    }.getOrDefault(ZidooPlayerStrings())

    fun parseResult(data: Intent?): ZidooResult? =
        data?.extras?.let { extras -> ZidooResult.parse { key -> @Suppress("DEPRECATION") extras.get(key) } }
}
