package app.zmby.search

import app.zmby.emby.BaseItemDto
import app.zmby.log.ZLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** A title the app has already seen (home rows, grids, favorites, history); no extra requests. */
data class LocalTitle(val title: String, val year: Int? = null)

/** A server whose SearchTerm understands pinyin, as far as candidates are concerned. */
interface CandidateServer {
    val key: String
    suspend fun search(term: String, limit: Int): List<BaseItemDto>
}

data class Candidate(
    val title: String,
    val year: Int?,
    /** The server result this came from; picking it reuses the result instead of searching that server again. */
    val item: BaseItemDto? = null,
    val serverKey: String? = null,
    /** Order of the source server (0 = first); local titles come after servers with equal rank. */
    val serverOrder: Int = Int.MAX_VALUE,
)

/**
 * Pinyin input candidates (plan §3.2): typed letters become Chinese titles to search with. Sources,
 * merged and de-duplicated: the first [maxServers] pinyin-capable servers (Limit=[limit], after
 * [debounceMs] of no typing, cancelled by newer input, cached per input until [clear]) and titles the
 * app already has locally. Never pages through a library.
 */
class PinyinCandidates(
    private val scope: CoroutineScope,
    private val servers: () -> List<CandidateServer>,
    private val localTitles: () -> List<LocalTitle>,
    private val debounceMs: Long = 400,
    private val maxServers: Int = 3,
    private val limit: Int = 10,
) {
    private val _candidates = MutableStateFlow<List<Candidate>>(emptyList())
    val candidates: StateFlow<List<Candidate>> = _candidates.asStateFlow()

    private val cache = HashMap<String, List<Candidate>>()
    private var job: Job? = null

    fun onInput(raw: String, fetchRemote: Boolean = true) {
        job?.cancel()
        val input = Pinyin.cleanInput(raw)
        if (input.isEmpty() || Pinyin.hasHan(raw)) {
            _candidates.value = emptyList()
            return
        }
        val local = localCandidates(input)
        if (!fetchRemote) {
            // Live search already asks the servers; its hits supply the remote candidates.
            _candidates.value = rank(input, local)
            return
        }
        cache[input]?.let { cached ->
            _candidates.value = rank(input, cached + local)
            return
        }
        _candidates.value = rank(input, local)
        job = scope.launch {
            delay(debounceMs)
            val remote = fromServers(input)
            cache[input] = remote
            _candidates.value = rank(input, remote + local)
        }
    }

    /** Forget cached server answers (when leaving the search screen). */
    fun clear() {
        job?.cancel()
        cache.clear()
        _candidates.value = emptyList()
    }

    private suspend fun fromServers(input: String): List<Candidate> {
        val targets = servers().take(maxServers)
        if (targets.isEmpty()) return emptyList()
        return kotlinx.coroutines.coroutineScope {
            targets.mapIndexed { order, s ->
                async {
                    try {
                        s.search(input, limit).filter { it.type == "Movie" || it.type == "Series" }.map { item ->
                            Candidate(item.name.orEmpty(), item.productionYear, item, s.key, order)
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        ZLog.w(TAG, "candidates from ${s.key} failed: $e")
                        emptyList()
                    }
                }
            }.awaitAll().flatten()
        }
    }

    private fun localCandidates(input: String): List<Candidate> =
        localTitles().asSequence()
            .filter { Pinyin.hasHan(it.title) && PinyinTitle(it.title).matchStart(input) >= 0 }
            .map { Candidate(it.title, it.year) }
            .take(LOCAL_MAX)
            .toList()

    companion object {
        private const val TAG = "Pinyin"
        private const val LOCAL_MAX = 30
        const val SHOWN = 12

        /**
         * Ranking from plan §3.2: matches from the first character before matches in the middle,
         * initials equal to the input first, then shorter titles, then server order.
         */
        fun rank(input: String, all: List<Candidate>): List<Candidate> {
            val q = Pinyin.cleanInput(input)
            data class Scored(val c: Candidate, val start: Int, val exactInitials: Boolean)
            // One candidate per title and year; a title without a year (search history) folds into a
            // dated one, and a server's own answer (reusable) wins over a local title.
            return all
                .groupBy { Pinyin.normalize(it.title) }
                .values.flatMap { same ->
                    val dated = same.filter { it.year != null }.groupBy { it.year }.values
                    if (dated.isEmpty()) listOf(same.firstOrNull { it.item != null } ?: same.first())
                    else dated.map { y -> y.firstOrNull { it.item != null } ?: y.first() }
                }
                .map { c ->
                    val t = PinyinTitle(c.title)
                    val start = t.matchStart(q).let { if (it < 0) 1 else it } // server matched it some other way
                    Scored(c, start, t.initials == q)
                }
                .sortedWith(
                    compareBy<Scored> { if (it.start == 0) 0 else 1 }
                        .thenBy { if (it.exactInitials) 0 else 1 }
                        .thenBy { Pinyin.normalize(it.c.title).length }
                        .thenBy { it.c.serverOrder },
                )
                .map { it.c }
                .take(SHOWN)
        }
    }
}

/**
 * Decides whether a server's search understands pinyin: search the initials of one Chinese title
 * from its own latest items (Limit=5); a hit means a pinyin plugin is installed. Null when no
 * suitable title is at hand (treated as "not supported").
 */
object PinyinProbe {
    fun pickSample(items: List<BaseItemDto>): BaseItemDto? = items
        .filter { (it.type == "Movie" || it.type == "Series") && !it.name.isNullOrBlank() }
        .filter { Pinyin.hasHan(it.name!!) && Pinyin.initials(it.name).length >= 2 }
        // Titles made only of Han characters give the least ambiguous initials.
        .sortedBy { if (Pinyin.normalize(it.name!!).all(Pinyin::isHan)) 0 else 1 }
        .firstOrNull()

    suspend fun probe(items: List<BaseItemDto>, search: suspend (term: String) -> List<BaseItemDto>): Boolean? {
        val sample = pickSample(items) ?: return null
        val term = Pinyin.initials(sample.name!!)
        val hits = search(term)
        val supported = hits.any { it.id == sample.id || it.name == sample.name }
        ZLog.i("Pinyin", "probe '${sample.name}' as '$term' -> ${hits.size} hits, supported=$supported")
        return supported
    }
}
