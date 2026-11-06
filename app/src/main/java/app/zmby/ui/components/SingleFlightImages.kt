package app.zmby.ui.components

import coil.intercept.Interceptor
import coil.request.ImageResult
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Lets only one request per image URL run at a time (performance plan RN ⑧). The sharp backdrop,
 * its blurred copy and the hero's prefetch are different requests for the same URL; started together,
 * each missed the disk cache and downloaded the image again. Now the later ones wait for the first,
 * then read the file it cached. Requests for other URLs are not held up.
 */
class SingleFlightImages : Interceptor {
    private class Slot { val mutex = Mutex(); var users = 0 }
    private val slots = HashMap<String, Slot>()

    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val url = (chain.request.data as? String)?.takeIf { it.startsWith("http") } ?: return chain.proceed(chain.request)
        val slot = synchronized(slots) { slots.getOrPut(url) { Slot() }.also { it.users++ } }
        try {
            return slot.mutex.withLock { chain.proceed(chain.request) }
        } finally {
            synchronized(slots) { if (--slot.users == 0) slots.remove(url) }
        }
    }
}
