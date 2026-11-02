package app.zmby.playback

import app.zmby.emby.BaseItemDto
import app.zmby.emby.MediaSourceInfo
import app.zmby.media.hdrBadge
import app.zmby.media.resolutionLabel
import java.util.Locale

/** Release identity is shared across episodes; Emby source IDs belong to individual files. */
object EpisodeContinuation {
    private val episode = Regex("(?i)s\\d{1,3}[ ._-]*e\\d{1,4}|\\b(?:ep?|episode)[ ._-]*\\d{1,4}\\b|第\\s*\\d+\\s*[集话話]|(?<![\\p{L}\\d])\\d{1,3}(?![\\p{L}\\d])")
    private fun release(value: String?): String? = value?.lowercase(Locale.ROOT)?.let {
        episode.replace(it, "#").replace(Regex("[ ._]+"), " ").trim()
    }?.takeIf { it.any(Char::isLetter) }

    fun score(previous: MediaSourceInfo, candidate: MediaSourceInfo): Int {
        fun same(a: String?, b: String?) = a != null && a == b
        fun folder(s: MediaSourceInfo) = s.path?.replace('\\', '/')?.substringBeforeLast('/', "")?.takeIf { it.isNotBlank() }
        var score = 0
        if (same(release(previous.fileName), release(candidate.fileName))) score += 128
        if (same(release(previous.name), release(candidate.name))) score += 64
        if (same(folder(previous), folder(candidate))) score += 32
        if (same(resolutionLabel(previous), resolutionLabel(candidate))) score += 8
        if (same(previous.videoStream?.codec?.lowercase(Locale.ROOT), candidate.videoStream?.codec?.lowercase(Locale.ROOT))) score += 4
        if (same(hdrBadge(previous.videoStream), hdrBadge(candidate.videoStream))) score += 2
        return score
    }

    fun source(sources: List<MediaSourceInfo>, previous: MediaSourceInfo?): MediaSourceInfo? {
        if (previous == null) return null
        return sources.filter { it.supportsDirectPlay || it.supportsDirectStream }
            .maxByOrNull { score(previous, it) }?.takeIf { score(previous, it) > 0 }
    }

    /** Adjacent files may be different releases of the current episode. Skip those first. */
    fun next(current: BaseItemDto, episodes: List<BaseItemDto>, previous: MediaSourceInfo?): BaseItemDto? {
        fun number(item: BaseItemDto): Pair<Int, Int>? = item.indexNumber?.let { (item.parentIndexNumber ?: 0) to it }
        val order = compareBy<Pair<Int, Int>> { it.first }.thenBy { it.second }
        val here = number(current)
        val candidates = if (here != null) episodes.filter { e ->
            e.id != current.id && e.locationType != "Virtual" && number(e)?.let { order.compare(it, here) > 0 } == true
        } else episodes.dropWhile { it.id != current.id }.drop(1).filter { it.locationType != "Virtual" }
        val first = candidates.minWithOrNull(compareBy(order) { number(it) ?: (Int.MAX_VALUE to Int.MAX_VALUE) }) ?: return null
        return candidates.filter { number(it) == number(first) }.maxByOrNull { item ->
            previous?.let { p -> item.mediaSources.maxOfOrNull { score(p, it) } } ?: 0
        }
    }
}
