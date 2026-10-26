package nl.woonkamer.kiosk

import org.junit.Assert.*
import org.junit.Test

class AdminWindowTest {
    @Test fun confirmationExpiresAtThirtySeconds() {
        val session = AdminWindow(10_000)
        assertTrue(session.isValidAt(10_000))
        assertTrue(session.isValidAt(39_999))
        assertFalse(session.isValidAt(40_000))
        assertFalse(session.isValidAt(90_000))
    }
    @Test fun timeBeforeAuthenticationIsRejected() {
        assertFalse(AdminWindow(10_000).isValidAt(9_999))
    }
}
