package app.zmby.data

import androidx.core.content.edit
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
        Disk.write { prefs.edit { write(key, clean) } }
    }
}

/**
 * How 弹幕 reach the screen (danmu design): drawn by zmby on the playback overlay, or baked into an
 * ASS subtitle the Zidoo player renders itself (danmu2ass research).
 */
enum class DanmuMode(val label: String) { Overlay("浮层"), Subtitle("播放器字幕") }

/** Quality preference for the default version (settings › 播放 › 版本选择). */
enum class QualityPreference(val label: String) { UHD("4K"), FHD("1080p"), Auto("自动") }

/** 画质偏好 › HDR 格式 (ui-spec §2.22), ranked by the user; [SDR] also stands for no HDR. */
enum class HdrFormat(val label: String) {
    DolbyVision("杜比视界"), HDR10Plus("HDR10+"), HDR10("HDR10"), HLG("HLG"), SDR("SDR");

    companion object {
        /** The stored order (comma-separated names): unknown names dropped, missing formats appended in default order. */
        fun parse(raw: String): List<HdrFormat> {
            val named = raw.split(',').mapNotNull { n -> entries.firstOrNull { it.name == n.trim() } }.distinct()
            return named + entries.filter { it !in named }
        }
        fun store(order: List<HdrFormat>): String = parse(order.joinToString(",") { it.name }).joinToString(",") { it.name }
    }
}

/** 画质偏好 › 画幅 › IMAX. */
enum class ImaxMode(val label: String) { Prefer("优先"), Neutral("不区分"), Avoid("避开") }

/** 画质偏好 › 码率 › 同档码率. */
enum class BitrateOrder(val label: String) { High("高优先"), Low("低优先") }

/** 画质偏好 › 编码与文件 › 视频编码: the avoided codec sorts last within its tier. */
enum class CodecAvoid(val label: String) { None("不区分"), AV1("避开 AV1"), H264("避开 H.264") }

/** 画质偏好 › 排序方式: picture before server priority, or the other way round. */
enum class RankOrder(val label: String) { Quality("画质优先"), Route("线路优先") }

/** App-wide preferences shown on the settings screen. Construct off the main thread (it reads the file). */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    init {
        // The old skip_intro switch controlled both kinds. Copy it once before they become independent.
        if (!prefs.contains("skip_recap")) {
            prefs.edit(commit = true) { putBoolean("skip_recap", prefs.getBoolean("skip_intro", true)) }
        }
        // 2026-10-31 (ui-spec §2.16): the two switches became 自动跳过 · 显示按钮 · 关闭; on → 自动跳过.
        migrateSkipMode(prefs, "skip_intro", KEY_INTRO_MODE)
        migrateSkipMode(prefs, "skip_recap", KEY_RECAP_MODE)
    }

    private val backupPrefs = mutableListOf<Pref<*>>()
    private fun <T> registered(pref: Pref<T>) = pref.also { backupPrefs += it }

    private val debuggable = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0

    private fun skipMode(key: String) = registered(Pref(prefs, key, app.zmby.segments.SkipMode.Auto,
        { k, d -> getString(k, null)?.let { runCatching { app.zmby.segments.SkipMode.valueOf(it) }.getOrNull() } ?: d }, { k, v -> putString(k, v.name) }))
    private fun bool(key: String, default: Boolean) = registered(Pref(prefs, key, default, { k, d -> getBoolean(k, d) }, { k, v -> putBoolean(k, v) }))
    private inline fun <reified E : Enum<E>> enumPref(key: String, default: E) = registered(Pref(prefs, key, default,
        { k, d -> getString(k, null)?.let { n -> enumValues<E>().firstOrNull { it.name == n } } ?: d }, { k, v -> putString(k, v.name) }))
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
        Disk.write { prefs.edit { putInt(KEY_WATCHED, p) } }
    }

    /** After an episode is watched, offer the next one (播放 › 播放器). */
    val offerNextEpisode = bool("offer_next_episode", true)
    /** 连播 Up Next: the next-episode prompt counts down and plays it by itself (off: waits for a key). */
    val upNextCountdown = bool("up_next_countdown", true)
    /** 跳过片头 from the segment markers (P4): 自动跳过 · 显示按钮 · 关闭 (ui-spec §2.16). */
    val skipIntro = skipMode(KEY_INTRO_MODE)
    /** 跳过回顾, independently of the opening credits. */
    val skipRecap = skipMode(KEY_RECAP_MODE)
    /** 跳过片尾: credits and previews; ending there finishes the playback as watched. */
    val skipCredits = registered(Pref(prefs, "skip_credits", app.zmby.segments.CreditsSkip.Episodes,
        { k, d -> getString(k, null)?.let { runCatching { app.zmby.segments.CreditsSkip.valueOf(it) }.getOrNull() } ?: d }, { k, v -> putString(k, v.name) }))
    /** How credits are skipped where [skipCredits] covers the item: 自动跳过 or 显示按钮 (never 关闭; that is [skipCredits]). */
    val skipCreditsMode = registered(Pref(prefs, "skip_credits_mode", app.zmby.segments.SkipMode.Auto,
        { k, d -> getString(k, null)?.let { runCatching { app.zmby.segments.SkipMode.valueOf(it) }.getOrNull() } ?: d }, { k, v -> putString(k, v.name) },
        normalize = { if (it == app.zmby.segments.SkipMode.Off) app.zmby.segments.SkipMode.Auto else it }))
    /**
     * 播放浮层 (ui-spec §2.16): skip buttons, the next-episode card and 弹幕 drawn over the system
     * player. Off by default: it needs 「允许显示在其他应用的上层」, which the user grants in system settings.
     */
    val playbackOverlay = bool("playback_overlay", false)
    /** 弹幕 on the overlay (danmu design); needs a 弹幕服务器. */
    val danmu = bool("danmu", true)
    /** 弹幕显示方式: the overlay (default) or a subtitle for the system player. */
    val danmuMode = registered(Pref(prefs, "danmu_mode", DanmuMode.Overlay,
        { k, d -> getString(k, null)?.let { runCatching { DanmuMode.valueOf(it) }.getOrNull() } ?: d }, { k, v -> putString(k, v.name) }))
    /** The one-time 播放器字幕 guide (how to switch the player's 双字幕模式 on) has been confirmed. */
    val danmuDualGuideSeen = bool("danmu_dual_guide_seen", false)
    /** 弹幕 lines at the top of the screen (1–5). */
    private fun pixels(key: String, default: Int, range: IntRange) = registered(Pref(prefs, key, default,
        { k, d -> getInt(k, d) }, { k, v -> putInt(k, v) }, normalize = { it.coerceIn(range) }))
    val danmuFontSize = pixels("danmu_font_size", 34, 20..60)
    val danmuLineGap = pixels("danmu_line_gap", 4, 0..24)
    val danmuTopMargin = pixels("danmu_top_margin", 12, 0..80)
    val danmuRows = registered(Pref(prefs, "danmu_rows", 3, { k, d -> getInt(k, d) }, { k, v -> putInt(k, v) }, normalize = { it.coerceIn(1, 5) }))
    /** 弹幕 opacity in percent. */
    val danmuOpacity = registered(Pref(prefs, "danmu_opacity", 100, { k, d -> getInt(k, d) }, { k, v -> putInt(k, v) }, normalize = { it.coerceIn(20, 100) }))
    /** A dandanplay-compatible API (danmu design); empty = 弹幕 off. Typed by the user, never a default. */
    val danmuApiBase = string("danmu_api_base", "")
    /** The official dandanplay API's AppId and AppSecret (signed requests); empty for a server that needs none. */
    val danmuAppId = string("danmu_app_id", "")
    val danmuAppSecret = string("danmu_app_secret", "")
    /** 播放失败自动换源: a version that fails to start is replaced by the next one in default order. */
    val autoFailover = bool("auto_failover", true)
    /**
     * 在看包含下一集 (ui-spec §2.18): off shows only works stopped part-way. Keeps the key of the
     * former 「下一集」行 switch, so its value carries over (2026-10-31).
     */
    val watchingIncludesNextUp = bool(KEY_WATCHING_NEXT_UP, true)
    /** Home rows (首页与媒体库). */
    val showLatestRow = bool("home_latest", true)
    /** 首页来源: "" for 全部 (every server merged), else one server's id; browsing follows it. */
    val homeSource = string("home_source", "")
    /** Browse sort and filters per "server|library" (ui-spec §2.8: 按服务器 + 媒体库记住), one JSON map. */
    val browseQueries = string("browse_queries", "{}")
    /** What each server's browse grid supports ([BrowseFilters]), by "server|library|kind": probed, so not backed up. */
    val browseFilters = Pref(prefs, "browse_filters", "{}", { k, d -> getString(k, d) ?: d }, { k, v -> putString(k, v) })
    /** The optional 待看 row on home (favorites spec), off by default. */
    val showWatchlistRow = bool("home_watchlist", false)
    /** The home hero rotates through its titles (外观). */
    val heroRotates = bool("hero_rotates", true)
    /** IMDb trailers play behind the hero and the detail pages once loaded (设置 › 首页 › 预告片; trailer design). */
    val trailerAutoplay = bool("trailer_autoplay", true)
    /** Trailers with their sound; off plays them muted (no audio decoded, no audio focus taken). */
    val trailerSound = bool("trailer_sound", false)
    /** IMDb, Tomatometer and Popcornmeter beside the catalogue's rating on the detail pages (影视资料 › 评分). */
    val externalRatings = bool("external_ratings", true)
    /** Default tracks when nothing was picked (音频与字幕); see [TrackPreference]. */
    val audioLanguage = string("audio_language", TrackPreference.SERVER)
    val subtitleLanguage = string("subtitle_language", TrackPreference.SERVER)
    /** 特效字幕优先: among the preferred language's subtitles, those with the 「特效」 badge first. */
    val preferStyledSubtitles = bool("prefer_styled_subtitles", false)
    /** Default version (播放 › 版本选择). */
    val quality = registered(Pref(prefs, "quality", QualityPreference.Auto, { k, d -> getString(k, null)?.let { runCatching { QualityPreference.valueOf(it) }.getOrNull() } ?: d }, { k, v -> putString(k, v.name) }))
    val preferNonDisc = bool("prefer_non_disc", true)
    /** 画质偏好 (ui-spec §2.22). Off: versions below [quality] sort last and show dimmed. */
    val acceptLowerResolution = bool("accept_lower_resolution", true)
    val hdrOrder = registered(Pref(prefs, "hdr_order", HdrFormat.store(HdrFormat.entries), { k, d -> getString(k, d) ?: d }, { k, v -> putString(k, v) },
        normalize = { HdrFormat.store(HdrFormat.parse(it)) }))
    val imaxMode = enumPref("imax_mode", ImaxMode.Neutral)
    /** 码率上限 in Mbps, 0 = 不限. */
    val bitrateCapMbps = registered(Pref(prefs, "bitrate_cap_mbps", 0, { k, d -> getInt(k, d) }, { k, v -> putInt(k, v) }, normalize = { it.coerceIn(0, 1000) }))
    val bitrateOrder = enumPref("bitrate_order", BitrateOrder.High)
    val codecAvoid = enumPref("codec_avoid", CodecAvoid.None)
    val preferLossless = bool("prefer_lossless", true)
    /** Default 线路优先: server priority before picture, as zmby ranked before §2.22. */
    val rankOrder = enumPref("rank_order", RankOrder.Route)
    /** Marks the preferred version by file or source name, e.g. `2160p.*REMUX`; never filters. Empty = off. */
    val versionPattern = string("version_pattern", "")
    /** PublicMetaDB's API key (片段标记来源); typed by the user, PublicMetaDB stays off without it. */
    val publicMetaDbKey = string("publicmetadb_key", "")

    val tmdbToken = string("tmdb_read_token", "")
    val tmdbApiBase = registered(Pref(prefs, "tmdb_api_base", app.zmby.catalog.TmdbEndpoint.DEFAULT,
        { k, d -> getString(k, d) ?: d }, { k, v -> putString(k, v) },
        normalize = { app.zmby.catalog.TmdbEndpoint.normalize(it) ?: app.zmby.catalog.TmdbEndpoint.DEFAULT }))
    val metadataLanguage = string("metadata_language", "zh-CN")
    val posterLanguage = string("poster_language", "zh-CN")
    val logoLanguage = string("logo_language", "zh-CN")
    val animeAliases = bool("anime_aliases", true)
    val tmdbSearch = bool("tmdb_search", true)
    val tmdbChart = string("tmdb_chart", "Trending")
    /** 主推区 source: "Tmdb" (the chart, when a token is set) or "Server" (Emby suggestions, recommendations, latest). */
    val heroSource = string("hero_source", "Tmdb")

    private fun cacheLimit(key: String, default: Int, choices: List<Int>) = registered(Pref(prefs, key, default,
        { k, d -> getInt(k, d).takeIf { it in choices } ?: d },
        { k, v -> putInt(k, v) }, normalize = { it.takeIf { value -> value in choices } ?: default }))
    val metadataCacheMb = cacheLimit("metadata_cache_mb", CacheLimits.METADATA_DEFAULT_MB, CacheLimits.metadataMb)
    val imageCacheMb = cacheLimit("image_cache_mb", CacheLimits.IMAGES_DEFAULT_MB, CacheLimits.imagesMb)

    /** Use live values so a queued preference write cannot make the backup stale. */
    fun backupValues(): kotlinx.serialization.json.JsonObject = kotlinx.serialization.json.JsonObject(
        backupPrefs.associate { it.key to it.backupValue() } + (KEY_WATCHED to kotlinx.serialization.json.JsonPrimitive(_watchedPercent.value)))

    fun restoreValues(values: kotlinx.serialization.json.JsonObject) {
        // A backup from before 2026-10-31 has the on/off switches.
        for ((old, key) in listOf("skip_intro" to KEY_INTRO_MODE, "skip_recap" to KEY_RECAP_MODE)) {
            if (values[key] != null) continue
            val on = (values[old] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: continue
            backupPrefs.firstOrNull { it.key == key }?.restoreValue(kotlinx.serialization.json.JsonPrimitive(skipModeOf(on == "true").name))
        }
        backupPrefs.forEach { pref -> (values[pref.key] as? kotlinx.serialization.json.JsonPrimitive)?.let(pref::restoreValue) }
        (values[KEY_WATCHED] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull()?.let(::setWatchedPercent)
    }

    private companion object {
        const val KEY_INTRO_MODE = "skip_intro_mode"
        const val KEY_RECAP_MODE = "skip_recap_mode"

        fun skipModeOf(on: Boolean) = if (on) app.zmby.segments.SkipMode.Auto else app.zmby.segments.SkipMode.Off

        fun migrateSkipMode(prefs: SharedPreferences, old: String, key: String) {
            if (prefs.contains(key)) return
            // Synchronous like the copy above: the Pref built next reads the migrated value.
            prefs.edit(commit = true) { putString(key, skipModeOf(prefs.getBoolean(old, true)).name) }
        }
        const val KEY_LAN_LOG = "lan_log"
        const val KEY_WATCHED = "watched_percent"
        /** The former 「下一集」行 switch, now 在看包含下一集. */
        const val KEY_WATCHING_NEXT_UP = "home_next_up"
    }
}
