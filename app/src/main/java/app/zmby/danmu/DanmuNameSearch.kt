package app.zmby.danmu

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope

/** Start every name together, select by preference order, and cancel unused work. */
internal class DanmuNameSearch {
    suspend fun <T : Any> first(names: List<String>, lookup: suspend (String) -> T?): T? = supervisorScope {
        val jobs = names.distinct().map { name -> async {
            try { Result.success(lookup(name)) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { Result.failure(e) }
        } }
        try {
            var failure: Throwable? = null
            for (job in jobs) {
                val result = job.await()
                result.getOrNull()?.let { return@supervisorScope it }
                if (failure == null) failure = result.exceptionOrNull()
            }
            failure?.let { throw it }
            null
        } finally {
            jobs.forEach { it.cancel() }
        }
    }
}
