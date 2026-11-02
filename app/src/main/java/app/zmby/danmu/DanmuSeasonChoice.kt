package app.zmby.danmu

import kotlinx.serialization.Serializable
import java.io.IOException

/** One explicit source/numbering decision for one canonical season. No transient ID arithmetic. */
@Serializable
internal data class DanmuSeasonChoice(val anchor: DanmuSelection, val episode: Int, val sourceEpisode: Int) {
    fun select(number: Int, targets: List<DanmuTarget>): DanmuSelection {
        val original = anchor.target
        val refreshed = targets.firstOrNull {
            if (original.url.isNotBlank()) it.url == original.url
            else original.animeId.isNotBlank() && it.animeId == original.animeId && it.episodeTitle == original.episodeTitle &&
                it.animeTitle == original.animeTitle && it.type == original.type
        } ?: throw IOException("本季已选弹幕来源失效，请重新选择")
        val wanted = number.toLong() + sourceEpisode - episode
        val platform = sourceTag(original)
        val matches = targets.filter {
            it.animeId == refreshed.animeId && it.animeTitle == refreshed.animeTitle && it.type == refreshed.type &&
                (platform == null || sourceTag(it) == platform) &&
                DanmuMatch.episodeNumber(it.episodeTitle)?.toLong() == wanted
        }.distinctBy { it.sourceKey }
        val target = matches.singleOrNull() ?: throw IOException("本季已选弹幕来源缺少对应分集或编号不明确，请重新选择")
        return anchor.copy(target = target, manual = true, season = true)
    }

    companion object {
        private val SOURCE_TAG = Regex("^\\s*【([^】]+)】")
        private fun sourceTag(target: DanmuTarget) = SOURCE_TAG.find(target.episodeTitle)?.groupValues?.get(1)
        fun of(query: DanmuQuery, selection: DanmuSelection): DanmuSeasonChoice? {
            if (query.movie || query.workId.isNullOrBlank() || query.season == null) return null
            val episode = query.episode?.takeIf { it > 0 } ?: return null
            val source = DanmuMatch.episodeNumber(selection.target.episodeTitle) ?: return null
            if (selection.target.url.isBlank() && selection.target.animeId.isBlank()) return null
            return DanmuSeasonChoice(selection.copy(manual = true, season = true), episode, source)
        }
    }
}
