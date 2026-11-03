package app.zmby.segments

import app.zmby.data.SegmentProvider
import app.zmby.emby.BaseItemDto
import app.zmby.emby.MediaSourceInfo
import app.zmby.emby.ticksToMs
import app.zmby.store.SegmentMarker
import kotlin.math.abs

/*
 * 片头片尾跳过 (P4; local-data spec, 片段标记提供方): segment markers from an ordered list of
 * providers. Each kind of segment is taken from the first provider in 设置 › 数据来源 › 片段标记来源
 * that has it. Pure rules here; fetching is in SegmentClients, caching in SegmentRepository.
 */

/** The four kinds of segment the providers know; [key] is what `segment_marker.kind` stores. */
enum class SegmentKind(val key: String, val label: String) {
    Intro("intro", "片头"),
    Recap("recap", "回顾"),
    Credits("credits", "片尾"),
    Preview("preview", "预告"),
    ;

    /** Intro and recap are skipped forward to their end; credits and preview run to (near) the end. */
    val atStart: Boolean get() = this == Intro || this == Recap

    companion object {
        fun of(key: String): SegmentKind? = entries.firstOrNull { it.key == key }
    }
}

/** One segment in milliseconds; [endMs] null runs to the end of the video. */
data class Segment(val kind: SegmentKind, val startMs: Long, val endMs: Long?) {
    fun contains(positionMs: Long): Boolean = positionMs >= startMs && (endMs == null || positionMs < endMs)
}

/** A provider's answer for one video: its segments and, when it says so, the runtime they were timed on. */
data class ProviderSegments(val segments: List<Segment>, val durationMs: Long? = null)

/** What a provider lookup came to; only [Found] and [Empty] are cached. */
sealed interface ProviderAnswer {
    data class Found(val result: ProviderSegments) : ProviderAnswer
    /** Asked and answered: nothing for this video. */
    data object Empty : ProviderAnswer
    /** Not answered (network, 5xx, 429, refused key): asked again later. */
    data class Failed(val reason: String, val retryAfterMs: Long? = null) : ProviderAnswer
    /** The video lacks the IDs this provider needs; nothing was sent. */
    data object NotAsked : ProviderAnswer
}

/**
 * What the external databases are asked with: external IDs only (never a server address, token or
 * path). For an episode the IDs are the series' and [season] / [episode] its numbers.
 */
data class SegmentQuery(
    val movie: Boolean,
    val tvdb: String? = null,
    val tmdb: String? = null,
    val imdb: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    /** The version being played, for providers that take it and for the duration check. */
    val durationMs: Long? = null,
) {
    val hasIds: Boolean get() = tvdb != null || tmdb != null || imdb != null
    val episodeOk: Boolean get() = movie || (season != null && episode != null && season >= 0 && episode > 0)

    companion object {
        /** The query for [item]; [series] supplies an episode's series IDs. */
        fun of(item: BaseItemDto, series: BaseItemDto?, durationMs: Long?): SegmentQuery {
            val movie = item.type != "Episode"
            val ids = if (movie) item else series
            fun id(name: String) = ids?.providerIds?.entries?.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.trim()?.takeIf { it.isNotEmpty() }
            return SegmentQuery(
                movie = movie,
                tvdb = id("Tvdb")?.takeIf { it.all(Char::isDigit) },
                tmdb = id("Tmdb")?.takeIf { it.all(Char::isDigit) },
                imdb = id("Imdb")?.lowercase()?.takeIf { it.startsWith("tt") },
                season = if (movie) null else item.parentIndexNumber,
                episode = if (movie) null else item.indexNumber,
                durationMs = durationMs,
            )
        }
    }
}

object SegmentRules {
    /** A provider timed on a version whose runtime differs by more than this (another cut) is not used. */
    const val PROVIDER_TOLERANCE_MS = 5_000L
    /** Emby markers of the same work on another server are borrowed only within this. */
    const val BORROW_TOLERANCE_MS = 2_000L
    /** A segment ending this close to (or past) the runtime is treated as running to the end. */
    private const val END_SLACK_MS = 3_000L

    /**
     * Drops what cannot belong to a video of [durationMs]: empty or reversed spans, spans starting
     * past the end. A [declared] runtime off by more than [PROVIDER_TOLERANCE_MS] drops everything.
     */
    fun valid(segments: List<Segment>, durationMs: Long?, declared: Long? = null): List<Segment> {
        if (declared != null && durationMs != null && abs(declared - durationMs) > PROVIDER_TOLERANCE_MS) return emptyList()
        return segments.mapNotNull { s ->
            if (s.startMs < 0) return@mapNotNull null
            val end = s.endMs
            if (end != null && end <= s.startMs) return@mapNotNull null
            if (durationMs != null && durationMs > 0) {
                if (s.startMs >= durationMs) return@mapNotNull null
                if (end != null && end > durationMs + PROVIDER_TOLERANCE_MS) return@mapNotNull null
                if (end != null && end >= durationMs - END_SLACK_MS) return@mapNotNull s.copy(endMs = null)
            }
            s
        }
    }

    /**
     * Polyfin's merge: for each kind, the first provider in [order] that has one gives it; the others
     * fill the gaps. Within one provider the first segment of a kind is used.
     */
    fun merge(order: List<SegmentProvider>, answers: Map<SegmentProvider, List<Segment>>): Map<SegmentKind, Pair<SegmentProvider, Segment>> {
        val out = linkedMapOf<SegmentKind, Pair<SegmentProvider, Segment>>()
        for (kind in SegmentKind.entries) {
            for (p in order) {
                val s = answers[p]?.firstOrNull { it.kind == kind } ?: continue
                out[kind] = p to s
                break
            }
        }
        return out
    }

    /**
     * Emby's own markers: chapters with `MarkerType` IntroStart / IntroEnd / CreditsStart (Emby 4.8+).
     * An IntroEnd without a start begins at 0; credits run to the end.
     */
    fun fromChapters(item: BaseItemDto): List<Segment> {
        val marks = item.chapters.filter { !it.markerType.isNullOrBlank() && !it.markerType.equals("Chapter", ignoreCase = true) }
        fun at(type: String) = marks.firstOrNull { it.markerType.equals(type, ignoreCase = true) }?.let { ticksToMs(it.startPositionTicks) }
        val out = mutableListOf<Segment>()
        val introEnd = at("IntroEnd")
        if (introEnd != null && introEnd > 0) {
            val start = at("IntroStart")?.takeIf { it < introEnd } ?: 0L
            out += Segment(SegmentKind.Intro, start, introEnd)
        }
        at("CreditsStart")?.let { out += Segment(SegmentKind.Credits, it, null) }
        return out
    }

    /**
     * [item] as played in [version]: Emby lists chapters per media source, and a version's own
     * chapters (with its runtime) are the ones timed on it; without them the item stays as it is.
     */
    fun asPlayed(item: BaseItemDto, version: MediaSourceInfo?): BaseItemDto {
        val chapters = version?.chapters?.takeIf { it.isNotEmpty() } ?: return item
        return item.copy(chapters = chapters, runTimeTicks = version.runTimeTicks ?: item.runTimeTicks)
    }

    /**
     * Whether [item]'s own chapters were timed on the version being played ([durationMs]): Emby keeps
     * one set of chapters per item, for the file its runtime describes, so another cut grouped under
     * the same item (a longer edition) does not match. Unknown runtimes are taken as matching.
     */
    fun sameCut(item: BaseItemDto, durationMs: Long?): Boolean {
        val runtime = item.runtimeMs ?: return true
        return durationMs == null || durationMs <= 0 || abs(runtime - durationMs) <= BORROW_TOLERANCE_MS
    }

    /**
     * Borrowing Emby markers (local-data spec): the played item has none for this version, another
     * item of the same work has them (the played version's own item, then other servers' copies in
     * priority order), and its runtime is within [BORROW_TOLERANCE_MS]; the first that qualifies is used.
     */
    fun borrow(durationMs: Long?, others: List<BaseItemDto>): List<Segment> {
        if (durationMs == null || durationMs <= 0) return emptyList()
        for (o in others) {
            // The chapters belong to the item's own file, which its runtime describes.
            val runtime = o.runtimeMs ?: o.mediaSources.firstNotNullOfOrNull { it.runTimeTicks }?.let(::ticksToMs) ?: continue
            if (abs(runtime - durationMs) > BORROW_TOLERANCE_MS) continue
            val segs = fromChapters(o)
            if (segs.isNotEmpty()) return segs
        }
        return emptyList()
    }

    /**
     * 本地手动标记 (local-data spec): for each kind the episode's own mark ([workKey]), else its
     * season's, so a season's credits still apply to an episode that only has its own intro.
     */
    fun localMarks(rows: List<SegmentMarker>, workKey: String): List<SegmentMarker> =
        rows.filter { SegmentKind.of(it.kind) != null }.groupBy { it.kind }.values
            .map { same -> same.firstOrNull { it.workKey == workKey } ?: same.first() }

    // ---- segment_marker rows: one per (work, provider, kind); "none" says the provider had nothing.

    const val KIND_NONE = "none"
    /** `end_ms` for a segment that runs to the end. */
    private const val TO_END = -1L

    fun toRows(workKey: String, source: String, answer: ProviderSegments, now: Long): List<SegmentMarker> =
        if (answer.segments.isEmpty()) listOf(SegmentMarker(workKey, source, KIND_NONE, answer.durationMs, 0, 0, now))
        else answer.segments.distinctBy { it.kind }.map { SegmentMarker(workKey, source, it.kind.key, answer.durationMs, it.startMs, it.endMs ?: TO_END, now) }

    fun fromRows(rows: List<SegmentMarker>): ProviderSegments = ProviderSegments(
        rows.mapNotNull { r -> SegmentKind.of(r.kind)?.let { Segment(it, r.startMs, r.endMs.takeIf { e -> e >= 0 }) } },
        rows.firstNotNullOfOrNull { it.durationMs },
    )

    /** Cached answers stay this long: found ones a month, empty ones a day (Polyfin's choice). */
    const val FOUND_TTL_MS = 30L * 24 * 3600_000
    const val EMPTY_TTL_MS = 24L * 3600_000

    fun fresh(rows: List<SegmentMarker>, now: Long): Boolean {
        if (rows.isEmpty()) return false
        val at = rows.minOf { it.fetchedAt }
        val ttl = if (rows.all { it.kind == KIND_NONE }) EMPTY_TTL_MS else FOUND_TTL_MS
        return now - at < ttl
    }
}

/** 跳过片尾: never, for episodes only (the default), or for everything. */
enum class CreditsSkip(val label: String) { Off("关闭"), Episodes("剧集"), All("全部") }

/**
 * How a kind of segment is handled (ui-spec §2.16): skipped by zmby at once, offered as a 「跳过」
 * button on the playback overlay, or left alone. [Button] needs the overlay; without it (switched
 * off or not permitted) it behaves as [Auto], so nothing is lost.
 */
enum class SkipMode(val label: String) { Auto("自动跳过"), Button("显示按钮"), Off("关闭") }

/** What [SegmentSkipper] decided for one sample. */
sealed interface SkipDecision {
    val kind: SegmentKind
    val targetMs: Long

    /** zmby seeks now (自动跳过). */
    data class Seek(override val kind: SegmentKind, override val targetMs: Long) : SkipDecision

    /** The overlay offers a button that seeks to [targetMs]; it goes away once playback passes [untilMs] (null: the end). */
    data class Offer(override val kind: SegmentKind, override val targetMs: Long, val untilMs: Long?) : SkipDecision
}

/**
 * Decides, poll by poll, whether to seek past a segment (zidoo-player decision: com.zidoo.player
 * 1.3.60 ignores skip extras, so zmby seeks through the 9529 API). Rules:
 * - each segment is acted on at most once per playback, so going back to watch it is respected;
 * - only when playback ran into it: the previous sample was just before it and time moved forward
 *   normally (or, for intro and recap, the playback started inside it) — not when the user seeked
 *   into it;
 * - intro and recap jump to their end; credits and preview jump to their end, or close to the end
 *   of the video when they run to it, which ends playback as completed (Up Next takes over);
 * - a kind in [SkipMode.Button] is offered instead of skipped (the overlay asks the user).
 */
class SegmentSkipper(
    private val segments: List<Segment>,
    private val modes: Map<SegmentKind, SkipMode>,
) {
    constructor(segments: List<Segment>, skipIntro: Boolean, skipRecap: Boolean, skipEnd: Boolean) : this(
        segments,
        mapOf(
            SegmentKind.Intro to auto(skipIntro), SegmentKind.Recap to auto(skipRecap),
            SegmentKind.Credits to auto(skipEnd), SegmentKind.Preview to auto(skipEnd),
        ),
    )

    private val done = mutableSetOf<SegmentKind>()

    /** The segment of [kind], if any (the overlay's next-episode card starts with the credits). */
    fun segment(kind: SegmentKind): Segment? = segments.firstOrNull { it.kind == kind }

    fun mode(kind: SegmentKind): SkipMode = modes[kind] ?: SkipMode.Off

    /** The position to seek to at once after a sample at [positionMs] (previous sample [previousMs]), or null. */
    fun onSample(previousMs: Long?, positionMs: Long, durationMs: Long): Long? =
        (decide(previousMs, positionMs, durationMs) as? SkipDecision.Seek)?.targetMs

    /** What to do after a sample at [positionMs] (previous sample [previousMs]), or null. */
    fun decide(previousMs: Long?, positionMs: Long, durationMs: Long): SkipDecision? {
        for (s in segments) {
            if (s.kind in done) continue
            val mode = mode(s.kind)
            if (mode == SkipMode.Off) continue
            if (!s.contains(positionMs)) continue
            // A playback that starts inside the intro skips it; one resumed inside the credits does not.
            val ranInto = (previousMs == null && s.kind.atStart) ||
                (previousMs != null && previousMs <= positionMs && positionMs - previousMs <= NATURAL_STEP_MS && previousMs >= s.startMs - NATURAL_STEP_MS)
            done += s.kind
            if (!ranInto) continue
            val target = when {
                s.endMs != null && (durationMs <= 0 || s.endMs < durationMs - END_GUARD_MS) -> s.endMs
                durationMs > 0 -> (durationMs - END_GUARD_MS).coerceAtLeast(positionMs)
                else -> continue
            }
            if (target - positionMs < MIN_JUMP_MS) continue
            return if (mode == SkipMode.Button) SkipDecision.Offer(s.kind, target, s.endMs) else SkipDecision.Seek(s.kind, target)
        }
        return null
    }

    companion object {
        /** Two polls a second apart, with slack for a slow API answer. */
        const val NATURAL_STEP_MS = 5_000L
        /** Seeking this far before the end lets the player finish by itself (end_by=playback_completion). */
        const val END_GUARD_MS = 1_500L
        /** Not worth a seek. */
        const val MIN_JUMP_MS = 2_000L

        private fun auto(on: Boolean) = if (on) SkipMode.Auto else SkipMode.Off

        fun of(segments: Map<SegmentKind, Pair<SegmentProvider, Segment>>, episode: Boolean, intro: Boolean, recap: Boolean, credits: CreditsSkip): SegmentSkipper =
            of(segments, episode, auto(intro), auto(recap), credits, SkipMode.Auto, buttons = false)

        /**
         * The skipper for one playback. [creditsMode] applies where [credits] covers the item; with
         * [buttons] false (no overlay) every [SkipMode.Button] becomes [SkipMode.Auto].
         */
        fun of(
            segments: Map<SegmentKind, Pair<SegmentProvider, Segment>>, episode: Boolean,
            intro: SkipMode, recap: SkipMode, credits: CreditsSkip, creditsMode: SkipMode, buttons: Boolean,
        ): SegmentSkipper {
            fun eff(m: SkipMode) = if (m == SkipMode.Button && !buttons) SkipMode.Auto else m
            val end = if (credits == CreditsSkip.All || (credits == CreditsSkip.Episodes && episode)) eff(creditsMode) else SkipMode.Off
            return SegmentSkipper(
                segments.values.map { it.second }.sortedBy { it.startMs },
                mapOf(SegmentKind.Intro to eff(intro), SegmentKind.Recap to eff(recap), SegmentKind.Credits to end, SegmentKind.Preview to end),
            )
        }
    }
}

/** Editing 本地手动标记 with the remote (detail page). */
object SegmentMarks {
    const val STEP_MS = 10_000L
    /** Held left or right: a minute per repeat. */
    const val FAST_STEP_MS = 60_000L
    /** Where an unset intro end starts: most openings end within the first minute and a half. */
    const val INTRO_DEFAULT_MS = 90_000L
    /** Where an unset credits start begins, before the end of the video. */
    const val CREDITS_DEFAULT_BEFORE_END_MS = 180_000L

    /**
     * What the editor opens with: the season's marks under 本季全部 when the episode has none of its
     * own, else the episode's (the season's then show as inherited).
     */
    fun opening(saved: SavedMarks): ManualMarks =
        if (saved.episode.isEmpty && !saved.season.isEmpty) saved.season else saved.episode

    /**
     * Switching 本集 | 本季全部 keeps what was typed: into the season an unset kind takes the season's
     * mark (saving does not erase it); back to the episode a kind equal to the season's inherits it again.
     */
    fun rescope(draft: ManualMarks, toSeason: Boolean, season: ManualMarks): ManualMarks =
        if (toSeason) ManualMarks(draft.introEndMs ?: season.introEndMs, draft.creditsStartMs ?: season.creditsStartMs, season = true)
        else ManualMarks(draft.introEndMs.takeIf { it != season.introEndMs }, draft.creditsStartMs.takeIf { it != season.creditsStartMs }, season = false)

    /** The first press on an unset time shows [default]; later presses move it by [deltaMs] within the video. */
    fun adjust(value: Long?, default: Long, deltaMs: Long, runtimeMs: Long?): Long {
        val next = if (value == null) default else value + deltaMs
        val max = runtimeMs?.takeIf { it > 0 } ?: Long.MAX_VALUE
        return next.coerceIn(0, max)
    }
}
