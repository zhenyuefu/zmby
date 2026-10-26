package app.zmby.ui

import app.zmby.ui.screens.HomeExitConfirmation
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeExitConfirmationTest {
    @Test fun firstBackOnlyPromptsEvenNearDeviceBoot() {
        assertFalse(HomeExitConfirmation().onBack(100))
    }

    @Test fun secondBackWithinWindowConfirmsAndConsumesConfirmation() {
        val confirmation = HomeExitConfirmation()
        assertFalse(confirmation.onBack(10_000))
        assertTrue(confirmation.onBack(12_000))
        assertFalse(confirmation.onBack(12_100))
    }

    @Test fun expiredConfirmationStartsANewWindow() {
        val confirmation = HomeExitConfirmation()
        assertFalse(confirmation.onBack(10_000))
        assertFalse(confirmation.onBack(12_001))
        assertTrue(confirmation.onBack(12_500))
    }

    @Test fun otherInteractionOrLeavingHomeCancelsConfirmation() {
        val confirmation = HomeExitConfirmation()
        assertFalse(confirmation.onBack(10_000))
        confirmation.reset()
        assertFalse(confirmation.onBack(10_500))
    }
}
