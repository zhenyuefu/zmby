package app.zmby.data

import coil.annotation.ExperimentalCoilApi
import coil.disk.DiskCache

/** Opening a journal alone doesn't schedule Coil's trim. A zero-byte commit does, via its API. */
@OptIn(ExperimentalCoilApi::class)
fun DiskCache.enforceStartupLimit() {
    if (size <= maxSize) return
    val key = "zmby:cache-budget-maintenance"
    val editor = openEditor(key) ?: return
    try {
        fileSystem.write(editor.metadata) { }
        fileSystem.write(editor.data) { }
        editor.commit()
    } catch (e: Exception) {
        editor.abort()
        throw e
    }
    remove(key)
}
