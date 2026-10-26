package nl.woonkamer.kiosk

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey

/** No plaintext PIN. A device-bound Keystore key protects the salted PBKDF2 result. */
class AdminPinStore(context: Context) {
    private val prefs = context.getSharedPreferences("admin-pin", Context.MODE_PRIVATE)
    val isConfigured get() = prefs.contains("verifier") && prefs.contains("salt")
    fun remainingWaitMillis(now: Long = System.currentTimeMillis()) =
        (prefs.getLong("retryAt", 0) - now).coerceIn(0, 30_000)

    @Synchronized fun configure(pin: CharArray) {
        require(PinHash.isValid(pin))
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val verifier = protect(PinHash.derive(pin, salt), createKey = true)
        check(prefs.edit().putString("salt", encode(salt)).putString("verifier", encode(verifier))
            .putInt("failures", 0).putLong("retryAt", 0).commit())
    }
    @Synchronized fun verify(pin: CharArray): Boolean {
        if (!isConfigured || remainingWaitMillis() > 0) return false
        val salt = Base64.getDecoder().decode(prefs.getString("salt", null))
        val expected = Base64.getDecoder().decode(prefs.getString("verifier", null))
        val actual = protect(PinHash.derive(pin, salt), createKey = false)
        val valid = PinHash.matches(actual, expected)
        actual.fill(0)
        val failures = if (valid) 0 else (prefs.getInt("failures", 0) + 1).coerceAtMost(5)
        check(prefs.edit().putInt("failures", failures)
            .putLong("retryAt", if (failures >= 5) System.currentTimeMillis() + 30_000 else 0).commit())
        return valid
    }
    private fun protect(derived: ByteArray, createKey: Boolean): ByteArray {
        try {
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            var key = store.getKey(KEY_ALIAS, null) as? SecretKey
            if (key == null) {
                check(createKey) { "Beheersleutel ontbreekt. Gebruik de herstelroute." }
                key = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, "AndroidKeyStore").apply {
                    init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                        .setDigests(KeyProperties.DIGEST_SHA256).setKeySize(256).build())
                }.generateKey()
            }
            return Mac.getInstance("HmacSHA256").apply { init(key) }.doFinal(derived)
        } finally { derived.fill(0) }
    }
    private fun encode(value: ByteArray) = Base64.getEncoder().encodeToString(value)
    companion object { private const val KEY_ALIAS = "woonkamer.admin-pin.v1" }
}
