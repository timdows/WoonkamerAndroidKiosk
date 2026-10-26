package nl.woonkamer.kiosk

import java.security.MessageDigest
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

object PinHash {
    fun isValid(pin: CharArray) = pin.size in 6..12 && pin.all { it in '0'..'9' }
    fun derive(pin: CharArray, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(pin, salt, 210_000, 256)
        return try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded }
        finally { spec.clearPassword() }
    }
    fun matches(first: ByteArray, second: ByteArray) = MessageDigest.isEqual(first, second)
}
