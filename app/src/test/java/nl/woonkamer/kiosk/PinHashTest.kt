package nl.woonkamer.kiosk
import org.junit.Assert.*
import org.junit.Test
class PinHashTest {
    @Test fun rejectsWeakFormatAndNonDigits() {
        listOf("", "12345", "1234567890123", "12345a", "１２３４５６").forEach { assertFalse(PinHash.isValid(it.toCharArray())) }
        assertTrue(PinHash.isValid("384729".toCharArray()))
        assertTrue(PinHash.isValid("384729510682".toCharArray()))
    }
    @Test fun verifierDependsOnBothPinAndSalt() {
        val salt = ByteArray(16) { it.toByte() }
        val hash = PinHash.derive("384729".toCharArray(),salt)
        assertTrue(PinHash.matches(hash,PinHash.derive("384729".toCharArray(),salt)))
        assertFalse(PinHash.matches(hash,PinHash.derive("384728".toCharArray(),salt)))
        assertFalse(PinHash.matches(hash,PinHash.derive("384729".toCharArray(),ByteArray(16) { (it+1).toByte() })))
    }
}
