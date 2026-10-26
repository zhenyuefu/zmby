package app.zmby.ui.screens

import app.zmby.catalog.CatalogId
import app.zmby.catalog.CatalogWork
import app.zmby.emby.BaseItemDto
import kotlinx.coroutines.*

data class DetailMetadata(val work: CatalogWork? = null, val fallbackReady: Boolean = false, val error: String? = null, val identity: CatalogWork? = null)

/** A list's external ID starts the provider immediately; full server metadata is a fallback only. */
class DetailMetadataLoader(
    private val seed: suspend () -> BaseItemDto?,
    private val identity: suspend () -> BaseItemDto?,
    private val serverDetail: suspend () -> BaseItemDto,
    private val resolve: suspend (BaseItemDto) -> CatalogId?,
    private val cached: suspend (CatalogId) -> CatalogWork?,
    private val fetch: suspend (CatalogId) -> CatalogWork,
) {
    suspend fun load(provider: CatalogId?, hasServer: Boolean, resourceOnly: Boolean = false, publish: (DetailMetadata) -> Unit) = coroutineScope {
        suspend fun resolved(item: BaseItemDto): CatalogId? = try { resolve(item) }
        catch (e: CancellationException) { throw e } catch (_: Exception) { null }
        var local = seed()
        var ref = provider
        try {
            if (!resourceOnly && ref == null) {
                ref = local?.takeIf { it.providerIds.isNotEmpty() }?.let { resolved(it) }
                if (ref == null && hasServer && local?.providerIds.isNullOrEmpty()) {
                    val ids = try { identity() } catch(e: CancellationException) { throw e } catch(_: Exception) { null }
                    if (ids != null) {
                        local = local?.copy(providerIds = ids.providerIds) ?: ids
                        if (ids.providerIds.isNotEmpty()) ref = resolved(local!!)
                    }
                }
            }
            if (ref != null) {
                val identityWork = CatalogWork(ref, local?.name.orEmpty(), ids = (local?.providerIds.orEmpty() + (ref.source to ref.id)))
                publish(DetailMetadata(identity = identityWork))
                var work = cached(ref)
                if (work != null) publish(DetailMetadata(work))
                val delayedFallback = launch {
                    delay(350)
                    if (work == null && local != null) publish(DetailMetadata(fallbackReady = true))
                }
                try {
                    work = fetch(ref)
                    publish(DetailMetadata(work))
                    return@coroutineScope
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { if (work != null) return@coroutineScope }
                finally { delayedFallback.cancelAndJoin() }
            }
            if (hasServer) {
                publish(DetailMetadata(fallbackReady = local != null))
                val full = serverDetail()
                local = full
                publish(DetailMetadata(fallbackReady = true))
                if (!resourceOnly && ref == null) resolved(full)?.let { id ->
                    cached(id)?.let { publish(DetailMetadata(it)) }
                    publish(DetailMetadata(fetch(id)))
                }
            } else publish(DetailMetadata(error = "暂时无法获取影片资料，请重试"))
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { publish(DetailMetadata(fallbackReady = local != null, error = "暂时无法获取资料，请重试")) }
    }
}
