package app.zmby.ui

import app.zmby.ui.screens.WatchingCardPress
import app.zmby.ui.screens.WatchingPress
import org.junit.Assert.assertEquals
import org.junit.Test

class WatchingCardInputTest {
    @Test fun shortOkPlaysOnceOnRelease() {
        val press = WatchingCardPress(500)
        press.down(false)
        assertEquals(WatchingPress.Play, press.up(120))
        assertEquals(WatchingPress.None, press.up(120))
    }

    @Test fun heldOkOpensMenuWithoutPlayingEvenWithoutKeyRepeats() {
        val press = WatchingCardPress(500)
        press.down(false)
        assertEquals(WatchingPress.Menu, press.up(500))
        assertEquals(WatchingPress.None, press.up(600))
    }

    @Test fun repeatedOkOpensOnlyOneMenuAndNextShortPressStillPlays() {
        val press = WatchingCardPress(500)
        press.down(false)
        repeat(5) { press.down(true) }
        assertEquals(WatchingPress.Menu, press.up(450))
        press.down(false)
        assertEquals(WatchingPress.Play, press.up(100))
    }

    @Test fun losingFocusCancelsThePressAndIgnoresItsRemainingRepeatsAndRelease() {
        val press = WatchingCardPress(500)
        press.down(false)
        press.cancel()
        press.down(true)
        assertEquals(WatchingPress.None, press.up(900))
        press.down(false)
        assertEquals(WatchingPress.Play, press.up(80))
    }
}
