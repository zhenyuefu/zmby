package app.zmby.catalog

import app.zmby.aggregate.providerId
import app.zmby.data.ServerItem
import app.zmby.emby.BaseItemDto
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger

enum class EpisodeIdentityState { Pending, Matched, NotFound, Conflict, Unavailable }
data class EpisodeIdentity(val state: EpisodeIdentityState, val episodeId: String? = null)
data class EpisodeIdentityHit(val showId: String, val episodeId: String)

/** Missing-value strings are common on compatible servers, and are not external identities. */
fun episodeProviderId(item: BaseItemDto, provider: String): String? = item.providerId(provider)?.trim()?.takeIf {
    it.isNotEmpty() && it.lowercase() !in setOf("none", "null", "undefined", "unknown", "?", "0")
}

fun secondaryEpisodeIds(work: CatalogWork, item: BaseItemDto): Map<String, String> =
    listOf("imdb", "tvdb").mapNotNull { provider ->
        episodeProviderId(item, provider)?.takeUnless { it.equals(work.ids.entries.firstOrNull { it.key.equals(provider, true) }?.value, true) }
            ?.takeIf { if (provider == "imdb") it.matches(Regex("tt\\d+", RegexOption.IGNORE_CASE)) else it.toLongOrNull()?.let { n -> n > 0 } == true }
            ?.let { provider to it.lowercase() }
    }.toMap()

/** An episode lookup must belong to this show; conflicting provider answers never borrow a slot. */
suspend fun resolveEpisodeIdentity(
    work: CatalogWork,
    item: BaseItemDto,
    find: suspend (provider: String, id: String) -> List<EpisodeIdentityHit>,
): EpisodeIdentity = coroutineScope {
    val targets = mutableSetOf<String>()
    var conflict = false
    var failed = false
    val answers = secondaryEpisodeIds(work, item).map { (provider, id) -> async {
        try { find(provider, id) }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { null }
    } }
    for (answer in answers) {
        val hits = answer.await()
        if (hits == null) { failed = true; continue }
        if (hits.any { it.showId != work.ref.id }) conflict = true
        targets += hits.filter { it.showId == work.ref.id }.map { it.episodeId }
    }
    when {
        conflict || targets.size > 1 -> EpisodeIdentity(EpisodeIdentityState.Conflict)
        targets.size == 1 -> EpisodeIdentity(EpisodeIdentityState.Matched, targets.single())
        failed -> EpisodeIdentity(EpisodeIdentityState.Unavailable)
        else -> EpisodeIdentity(EpisodeIdentityState.NotFound)
    }
}

/** Publish progress without launching one coroutine per file in a large directory. */
fun episodeIdentities(
    work: CatalogWork,
    resources: List<ServerItem>,
    cached: Map<String, EpisodeIdentity> = emptyMap(),
    resolve: suspend (BaseItemDto) -> EpisodeIdentity,
): Flow<Map<String, EpisodeIdentity>> = flow {
    val needed = resources.distinctBy { it.key }.filter {
        work.ref.source == "tmdb" && secondaryEpisodeIds(work, it.item).isNotEmpty() &&
            episodeProviderId(it.item, "tmdb").let { id -> id == null || id == work.ref.id }
    }
    val groups = needed.groupBy { secondaryEpisodeIds(work, it.item) }.values.toList()
    fun completed(identity: EpisodeIdentity) = identity.state !in setOf(EpisodeIdentityState.Pending, EpisodeIdentityState.Unavailable)
    var state = groups.flatMap { refs ->
        val saved = refs.firstNotNullOfOrNull { cached[it.key]?.takeIf(::completed) }
            ?: EpisodeIdentity(EpisodeIdentityState.Pending)
        refs.map { it.key to saved }
    }.toMap()
    emit(state)
    val queue = groups.filter { state[it.first().key]?.state == EpisodeIdentityState.Pending }
    val next = AtomicInteger()
    channelFlow {
        repeat(minOf(4, queue.size)) {
            launch {
                while (true) {
                    val refs = queue.getOrNull(next.getAndIncrement()) ?: break
                    send(refs.map { it.key } to resolve(refs.first().item))
                }
            }
        }
    }.collect { (keys, identity) ->
        state = state + keys.associateWith { identity }
        emit(state)
    }
}.flowOn(Dispatchers.Default)
