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
class Pref<T>(private val prefs: SharedPreferences, val key: String, private val default: T, private val read: SharedPreferences.(String, T) -> T, private val write: SharedPreferences.Editor.(String, T) -> Unit, private val normalize: (T) -> T = { it }) {
    private val state = MutableStateFlow(normalize(prefs.read(key, default)))
    val flow: StateFlow<T> = state.asStateFlow()
    val value: T get() = state.value

    fun backupValue(): kotlinx.serialization.json.JsonPrimitive = when (val v = value) {
        is Boolean -> kotlinx.serialization.json.JsonPrimitive(v)
        is Int -> kotlinx.serialization.json.JsonPrimitive(v)
        is Enum<*> -> kotlinx.serialization.json.JsonPrimitive(v.name)
        else -> kotlinx.serialization.json.JsonPrimitive(v.toString())
    }

    @Suppress("UNCHECKED_CAST")
    fun restoreValue(p: kotlinx.serialization.json.JsonPrimitive) {
        val parsed: Any? = when (default) {
            is Boolean -> if (!p.isString && p.content in setOf("true", "false")) p.content == "true" else null
            is Int -> if (!p.isString) p.content.toIntOrNull() else null
            is Enum<*> -> default.javaClass.enumConstants?.firstOrNull { (it as Enum<*>).name == p.content }
            is String -> p.content.takeIf { p.isString }
            else -> null
        }
        parsed?.let { set(it as T) }
    }

    fun set(v: T) {
        val clean = normalize(v)
        state.value = clean
        Disk.write { prefs.edit().apply { write(key, clean) }.apply() }
    }
}

/** Quality preference for the default version (settings › 播放 › 版本选择). */
enum class QualityPreference(val label: String) { UHD("4K"), FHD("1080p"), Auto("自动") }

/** App-wide preferences shown on the settings screen. Construct off the main thread (it reads the file). */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    init {
        // The old skip_intro switch controlled both kinds. Copy it once before they become independent.
        if (!prefs.contains("skip_recap")) {
            prefs.edit().putBoolean("skip_recap", prefs.getBoolean("skip_intro", true)).apply()
        }
    }

    private val backupPrefs = mutableListOf<Pref<*>>()
    private fun <T> registered(pref: Pref<T>) = pref.also { backupPrefs += it }

    private val debuggable = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0

    private fun bool(key: String, default: Boolean) = registered(Pref(prefs, key, default, { k, d -> getBoolean(k, d) }, { k, v -> putBoolean(k, v) }))
    private fun string(key: String, default: String) = registered(Pref(prefs, key, default, { k, d -> getString(k, d) ?: d }, { k, v -> putString(k, v) }))

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
    /** 连播 Up Next: the next-episode prompt counts down and plays it by itself (off: waits for a key). */
    val upNextCountdown = bool("up_next_countdown", true)
    /** 跳过片头 from the segment markers (P4). */
    val skipIntro = bool("skip_intro", true)
    /** 跳过回顾, independently of the opening credits. */
    val skipRecap = bool("skip_recap", true)
    /** 跳过片尾: credits and previews; ending there finishes the playback as watched. */
    val skipCredits = registered(Pref(prefs, "skip_credits", app.zmby.segments.CreditsSkip.Episodes,
        { k, d -> getString(k, null)?.let { runCatching { app.zmby.segments.CreditsSkip.valueOf(it) }.getOrNull() } ?: d }, { k, v -> putString(k, v.name) }))
    /** 播放失败自动换源: a version that fails to start is replaced by the next one in default order. */
    val autoFailover = bool("auto_failover", true)
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
    val quality = registered(Pref(prefs, "quality", QualityPreference.Auto, { k, d -> getString(k, null)?.let { runCatching { QualityPreference.valueOf(it) }.getOrNull() } ?: d }, { k, v -> putString(k, v.name) }))
    val preferNonDisc = bool("prefer_non_disc", true)
    /** Marks the preferred version by file or source name, e.g. `2160p.*REMUX`; never filters. Empty = off. */
    val versionPattern = string("version_pattern", "")
    /** PublicMetaDB's API key (片段标记来源); typed by the user, PublicMetaDB stays off without it. */
    val publicMetaDbKey = string("publicmetadb_key", "")

    val tmdbToken = string("tmdb_read_token", "")
    val metadataLanguage = string("metadata_language", "zh-CN")
    val posterLanguage = string("poster_language", "zh-CN")
    val logoLanguage = string("logo_language", "zh-CN")
    val animeAliases = bool("anime_aliases", true)
    val tmdbSearch = bool("tmdb_search", true)
    val tmdbChart = string("tmdb_chart", "Trending")

    private fun cacheLimit(key: String, default: Int, choices: List<Int>) = registered(Pref(prefs, key, default,
        { k, d -> getInt(k, d).takeIf { it in choices } ?: d },
        { k, v -> putInt(k, v) }, normalize = { it.takeIf { value -> value in choices } ?: default }))
    val metadataCacheMb = cacheLimit("metadata_cache_mb", CacheLimits.METADATA_DEFAULT_MB, CacheLimits.metadataMb)
    val imageCacheMb = cacheLimit("image_cache_mb", CacheLimits.IMAGES_DEFAULT_MB, CacheLimits.imagesMb)

    /** Use live values so a queued preference write cannot make the backup stale. */
    fun backupValues(): kotlinx.serialization.json.JsonObject = kotlinx.serialization.json.JsonObject(
        backupPrefs.associate { it.key to it.backupValue() } + (KEY_WATCHED to kotlinx.serialization.json.JsonPrimitive(_watchedPercent.value)))

    fun restoreValues(values: kotlinx.serialization.json.JsonObject) {
        backupPrefs.forEach { pref -> (values[pref.key] as? kotlinx.serialization.json.JsonPrimitive)?.let(pref::restoreValue) }
        (values[KEY_WATCHED] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull()?.let(::setWatchedPercent)
    }

    private companion object {
        const val KEY_LAN_LOG = "lan_log"
        const val KEY_WATCHED = "watched_percent"
    }
}
