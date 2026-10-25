package app.zmby.aggregate

import app.zmby.data.QualityPreference
import app.zmby.emby.MediaSourceInfo
import app.zmby.media.QualityTier
import app.zmby.media.audioFormat
import app.zmby.media.defaultAudio
import app.zmby.media.hdrBadge
import app.zmby.media.isDiscImage
import app.zmby.media.qualityTier

/** What decides the default among a work's versions. */
data class VersionFacts(
    val server: String,
    /** Server priority, 0 = first. */
    val order: Int,
    val healthy: Boolean,
    val latencyMs: Long?,
    val source: MediaSourceInfo,
)

data class VersionPrefs(
    /** The version picked by hand for this work last time (server, media source id). */
    val manual: Pair<String, String>? = null,
    /** 版本偏好正则 (settings): marks the preferred version by name or path; never filters. */
    val pattern: Regex? = null,
    val quality: QualityPreference = QualityPreference.Auto,
    val preferNonDisc: Boolean = true,
) {
    companion object {
        /** A user-typed pattern, case-insensitive; an invalid one is ignored rather than failing the page. */
        fun pattern(raw: String): Regex? = raw.trim().takeIf { it.isNotEmpty() }?.let { runCatching { Regex(it, RegexOption.IGNORE_CASE) }.getOrNull() }
    }
}

/**
 * 默认版本 (aggregation spec): picked by hand > matches the preference pattern > the preferred
 * quality tier (when set) > server priority > healthy and low latency > picture and sound (resolution,
 * HDR, lossless audio) > not a disc image > bitrate.
 */
object VersionRanker {
    private val LOSSLESS = listOf("TrueHD", "DTS-HD MA", "DTS:X", "FLAC", "LPCM")

    fun comparator(prefs: VersionPrefs): Comparator<VersionFacts> =
        compareBy<VersionFacts> { if (prefs.manual != null && prefs.manual == (it.server to it.source.id)) 0 else 1 }
            .thenBy { if (prefs.pattern != null && matches(prefs.pattern, it.source)) 0 else 1 }
            .thenBy { if (wantedTier(prefs.quality) != null && qualityTier(it.source) == wantedTier(prefs.quality)) 0 else 1 }
            .thenBy { it.order }
            .thenBy { if (it.healthy) 0 else 1 }
            .thenBy { it.latencyMs ?: Long.MAX_VALUE }
            .thenBy { qualityTier(it.source).ordinal }
            .thenBy { if (hdrBadge(it.source.videoStream) != null) 0 else 1 }
            .thenBy { if (lossless(it.source)) 0 else 1 }
            .thenBy { if (prefs.preferNonDisc && isDiscImage(it.source)) 1 else 0 }
            .thenByDescending { it.source.bitrate ?: 0 }

    fun best(versions: List<VersionFacts>, prefs: VersionPrefs): VersionFacts? = versions.minWithOrNull(comparator(prefs))

    fun matches(pattern: Regex, source: MediaSourceInfo): Boolean =
        listOfNotNull(source.name, source.fileName, source.path).any { pattern.containsMatchIn(it) }

    private fun wantedTier(q: QualityPreference): QualityTier? = when (q) {
        QualityPreference.UHD -> QualityTier.UHD
        QualityPreference.FHD -> QualityTier.FHD
        QualityPreference.Auto -> null
    }

    private fun lossless(s: MediaSourceInfo): Boolean {
        val a = defaultAudio(s)?.let(::audioFormat) ?: return false
        return LOSSLESS.any { a.startsWith(it) }
    }
}
