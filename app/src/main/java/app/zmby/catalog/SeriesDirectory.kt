package app.zmby.catalog

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** One whole-series directory, loaded a season at a time; failures never mean an empty season. */
data class SeriesDirectory(
    val seasons: Set<Int> = emptySet(),
    val pages: Map<Int, List<CatalogEpisode>> = emptyMap(),
    val failed: Set<Int> = emptySet(),
) {
    val unresolved: Set<Int> get() = seasons - pages.keys
}

/** Publish the selected season first, then load the rest with at most three requests in flight. */
fun seriesDirectory(
    seasons: List<Int>,
    selected: Int,
    cached: Map<Int, List<CatalogEpisode>> = emptyMap(),
    load: suspend (Int) -> List<CatalogEpisode>,
): Flow<SeriesDirectory> = flow {
    val required = (listOf(selected) + seasons).filter { it >= 0 }.toSet()
    var state = SeriesDirectory(required, cached.filterKeys { it in required })
    emit(state)
    val todo = required - state.pages.keys
    val limit = Semaphore(3)
    coroutineScope {
        val requests = todo.map { season -> season to async {
            limit.withPermit {
                try { Result.success(load(season)) }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { Result.failure(e) }
            }
        } }
        for ((season, request) in requests) {
            val result = request.await()
            state = result.fold(
                onSuccess = { state.copy(pages = state.pages + (season to it)) },
                onFailure = { state.copy(failed = state.failed + season) },
            )
            emit(state)
        }
    }
}
