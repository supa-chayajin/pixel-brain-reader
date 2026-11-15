package cloud.wafflecommons.pixelbrainreader.data.privatevault

import cloud.wafflecommons.pixelbrainreader.data.local.security.SecretManager
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.crypto.Cipher

/**
 * The password behind a strong biometric (12.18, V3; owner, 2026-11-15), the Keystore faked: the
 * readable 12.17 copy is wrapped at once and deleted only once a way in is proven.
 */
class VaultPasswordBindingTest {

    /** Wraps by reversing the bytes; "invalidated" plays a new enrolment. */
    private class FakeKeys : PasswordKeys {
        var ready = true
        var key = false
        var invalidated = false
        var hiccup = false

        override fun strongBiometricReady() = ready
        override fun hasKey() = key
        override fun wrap(secret: ByteArray): ByteArray {
            key = true
            return secret.reversedArray()
        }
        override fun unwrapCipher(): Cipher? {
            if (hiccup) error("Keystore busy")
            if (!key) return null
            if (invalidated) {
                key = false
                return null
            }
            return Cipher.getInstance("AES/GCM/NoPadding")
        }
        override fun unwrap(cipher: Cipher, wrapped: ByteArray): ByteArray = wrapped.reversedArray()
        override fun deleteKey() {
            key = false
        }
    }

    private val keys = FakeKeys()
    private var wrapped: ByteArray? = null
    private var legacy: String? = null
    private val secrets: SecretManager = mockk {
        every { wrappedVaultPassword() } answers { wrapped }
        every { saveWrappedVaultPassword(any()) } answers { wrapped = firstArg() }
        every { clearWrappedVaultPassword() } answers { wrapped = null }
        every { legacyVaultPassword() } answers { legacy }
        every { clearLegacyVaultPassword() } answers { legacy = null }
    }
    private val binding = VaultPasswordBinding(keys, secrets)

    private fun unlockedPassword(): String? = binding.cipherForUnlock()?.let { binding.unwrap(it)?.concatToString() }

    @Test
    fun `the 12_17 copy is wrapped at once and kept until an unlock proves the way in`() {
        legacy = "a journal password"
        binding.migrateLegacy()
        assertTrue(binding.isBound())
        assertFalse("never stored as typed", wrapped!!.contentEquals("a journal password".toByteArray()))
        assertEquals("a journal password", legacy) // still there: nothing proved the binding yet

        assertEquals("a journal password", unlockedPassword())
        binding.confirmUnlock("a journal password".toCharArray(), viaBiometric = true)
        assertNull(legacy)
        assertTrue(binding.isBound())
    }

    @Test
    fun `a typed password is bound, replacing a wrapped one another device made stale`() {
        binding.bind("the old password".toCharArray())
        binding.confirmUnlock("the new password".toCharArray(), viaBiometric = false)
        assertEquals("the new password", unlockedPassword())
    }

    @Test
    fun `a new face or fingerprint drops the wrapped copy and the password is typed once`() {
        binding.bind("a journal password".toCharArray())
        keys.invalidated = true
        assertNull(binding.cipherForUnlock())
        assertNull(wrapped)
        assertFalse(binding.isBound())

        keys.invalidated = false
        binding.confirmUnlock("a journal password".toCharArray(), viaBiometric = false)
        assertEquals("a journal password", unlockedPassword())
    }

    @Test
    fun `a Keystore hiccup keeps the wrapped copy for the next try`() {
        binding.bind("a journal password".toCharArray())
        keys.hiccup = true
        assertNull(binding.cipherForUnlock())
        assertNotNull(wrapped)
        keys.hiccup = false
        assertEquals("a journal password", unlockedPassword())
    }

    @Test
    fun `without a strong biometric nothing is kept and the password is typed`() {
        keys.ready = false
        legacy = "a journal password"
        binding.migrateLegacy()
        assertFalse(binding.isBound())
        assertNull(wrapped)
        assertNull(binding.cipherForUnlock())
        assertEquals("a journal password", legacy) // kept until the typed password proves itself

        binding.confirmUnlock("a journal password".toCharArray(), viaBiometric = false)
        assertNull(legacy)
        assertFalse(binding.isBound())
    }

    @Test
    fun `a password survives the round trip whatever its characters`() {
        val password = "Été 2026 · œuvre ✓ 日記".toCharArray()
        assertTrue(binding.bind(password))
        val cipher = binding.cipherForUnlock()!!
        assertArrayEquals(password, binding.unwrap(cipher))
    }
}
