package app.zmby.search

import app.zmby.aggregate.Hit
import app.zmby.aggregate.SearchSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class LiveSearchState(val term: String = "", val snapshot: SearchSnapshot? = null)

/** One debounced, cancellable search shared by typing and explicit submission. */
class LiveSearch(
    private val scope: CoroutineScope,
    private val search: (String, Hit?) -> Flow<SearchSnapshot>,
    private val remember: (String) -> Unit = {},
    private val debounceMs: Long = 400,
) {
    private val _state = MutableStateFlow(LiveSearchState())
    val state: StateFlow<LiveSearchState> = _state.asStateFlow()
    private var job: Job? = null

    fun submit(raw: String, reuse: Hit? = null, immediate: Boolean = false, saveHistory: Boolean = false) {
        val term = raw.trim()
        if (term == _state.value.term && !immediate) return
        job?.cancel()
        _state.value = LiveSearchState(term)
        if (term.isEmpty()) return
        job = scope.launch {
            if (!immediate) delay(debounceMs)
            search(term, reuse).collect {
                currentCoroutineContext().ensureActive()
                _state.value = LiveSearchState(term, it)
            }
            if (saveHistory) remember(term)
        }
    }

    fun refresh() = submit(_state.value.term, immediate = true)
    fun close() { job?.cancel() }
}
