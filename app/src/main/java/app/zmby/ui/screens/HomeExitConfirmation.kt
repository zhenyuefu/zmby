package app.zmby.ui.screens

/** Two consecutive Back presses within two seconds; callers supply a monotonic clock. */
internal class HomeExitConfirmation {
    private var firstBackAt: Long? = null

    fun onBack(now: Long): Boolean {
        val first = firstBackAt
        if (first != null && now - first in 0..2_000L) {
            reset()
            return true
        }
        firstBackAt = now
        return false
    }

    fun reset() {
        firstBackAt = null
    }
}
