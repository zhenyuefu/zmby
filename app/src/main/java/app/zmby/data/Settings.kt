package app.zmby.data

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import app.zmby.playback.TrackPreference
import app.zmby.playback.WatchThreshold
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** One stored preference with its current value as a flow. */
class Pref<T>(private val prefs: SharedPreferences, private val key: String, default: T, private val read: SharedPreferences.(String, T) -> T, private val write: SharedPreferences.Editor.(String, T) -> Unit) {
    private val state = MutableStateFlow(prefs.read(key, default))
    val flow: StateFlow<T> = state.asStateFlow()
    val value: T get() = state.value

    fun set(v: T) {
        state.value = v
        Disk.write { prefs.edit().apply { write(key, v) }.apply() }
    }
}

/** Quality preference for the default version (settings › 播放 › 版本选择). */
enum class QualityPreference(val label: String) { UHD("4K"), FHD("1080p"), Auto("自动") }

/** App-wide preferences shown on the settings screen. Construct off the main thread (it reads the file). */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val debuggable = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0

    private fun bool(key: String, default: Boolean) = Pref(prefs, key, default, { k, d -> getBoolean(k, d) }, { k, v -> putBoolean(k, v) })
    private fun string(key: String, default: String) = Pref(prefs, key, default, { k, d -> getString(k, d) ?: d }, { k, v -> putString(k, v) })

    /** Serve the debug log on the LAN (on by default in debug builds). */
    val lanLogPref = bool(KEY_LAN_LOG, debuggable)
    val lanLog: StateFlow<Boolean> get() = lanLogPref.flow
    fun setLanLog(on: Boolean) = lanLogPref.set(on)

    private val _watchedPercent = MutableStateFlow(WatchThreshold.clamp(prefs.getInt(KEY_WATCHED, WatchThreshold.DEFAULT_PERCENT)))
    /** See [WatchThreshold]. */
    val watchedPercent: StateFlow<Int> = _watchedPercent.asStateFlow()

    fun setWatchedPercent(percent: Int) {
        val p = WatchThreshold.clamp(percent)
        _watchedPercent.value = p
        Disk.write { prefs.edit().putInt(KEY_WATCHED, p).apply() }
    }

    /** After an episode is watched, offer the next one (播放 › 播放器). */
    val offerNextEpisode = bool("offer_next_episode", true)
    /** Home rows (首页与媒体库). */
    val showNextUpRow = bool("home_next_up", true)
    val showLatestRow = bool("home_latest", true)
    /** 首页来源: "" for 全部 (every server merged), else one server's id; browsing follows it. */
    val homeSource = string("home_source", "")
    /** Browse sort and filters per "server|library" (ui-spec §2.8: 按服务器 + 媒体库记住), one JSON map. */
    val browseQueries = string("browse_queries", "{}")
    /** The optional 待看 row on home (favorites spec), off by default. */
    val showWatchlistRow = bool("home_watchlist", false)
    /** The home hero rotates through its titles (外观). */
    val heroRotates = bool("hero_rotates", true)
    /** Default tracks when nothing was picked (音频与字幕); see [TrackPreference]. */
    val audioLanguage = string("audio_language", TrackPreference.SERVER)
    val subtitleLanguage = string("subtitle_language", TrackPreference.SERVER)
    /** Default version (播放 › 版本选择). */
    val quality = Pref(prefs, "quality", QualityPreference.Auto, { k, d -> getString(k, null)?.let { runCatching { QualityPreference.valueOf(it) }.getOrNull() } ?: d }, { k, v -> putString(k, v.name) })
    val preferNonDisc = bool("prefer_non_disc", true)
    /** Marks the preferred version by file or source name, e.g. `2160p.*REMUX`; never filters. Empty = off. */
    val versionPattern = string("version_pattern", "")
    /** PublicMetaDB's API key (片段标记来源); typed by the user, PublicMetaDB stays off without it. */
    val publicMetaDbKey = string("publicmetadb_key", "")

    private companion object {
        const val KEY_LAN_LOG = "lan_log"
        const val KEY_WATCHED = "watched_percent"
    }
}
