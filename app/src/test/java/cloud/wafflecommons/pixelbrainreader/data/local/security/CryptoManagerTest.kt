package cloud.wafflecommons.pixelbrainreader.data.local.security

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.fail
import org.junit.Test
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * JVM unit tests for [CryptoManager]. The AES/GCM + PBKDF2 primitives are plain
 * JCE (available off-device), and the only Android dependency — KeyProperties's
 * algorithm/mode/padding strings — are compile-time constants inlined by the
 * compiler, so this runs without an instrumented device.
 *
 * The critical case is [decrypt opens data written with the legacy 10k iteration
 * count]: it guards the V7 PBKDF2 work-factor upgrade against silently bricking
 * private notes that older builds wrote with 10_000 iterations.
 */
class CryptoManagerTest {

    private val crypto = CryptoManager()

    @Test
    fun `encrypt then decrypt round-trips plaintext`() = runBlocking {
        val plaintext = "Confidential journal entry — café, 2026. 🔒 multi-byte ✓"
        val password = "correct horse battery staple".toCharArray()

        val blob = crypto.encrypt(plaintext, password.copyOf())
        val recovered = crypto.decrypt(blob, password.copyOf())

        assertEquals(plaintext, recovered)
    }

    @Test
    fun `decrypt opens data written with the legacy 10k iteration count`() = runBlocking {
        // Reproduce the OLD on-disk format byte-for-byte: [salt(16)][iv(12)][ct],
        // key derived with the pre-upgrade 10_000 iterations. decrypt() must
        // transparently fall back and recover it without any format marker.
        val plaintext = "legacy note from an older build"
        val password = "vault-pass".toCharArray()
        val legacyBlob = legacyEncrypt(plaintext, password, iterations = 10_000)

        val recovered = crypto.decrypt(legacyBlob, password.copyOf())

        assertEquals(plaintext, recovered)
    }

    @Test
    fun `each encryption uses a fresh salt and IV`() = runBlocking {
        val password = "pw".toCharArray()
        val a = crypto.encrypt("same text", password.copyOf())
        val b = crypto.encrypt("same text", password.copyOf())

        // Different salt+IV => different ciphertext for identical input.
        assertNotEquals(a.toList(), b.toList())
    }

    @Test
    fun `wrong password fails to decrypt`() {
        val blob = runBlocking { crypto.encrypt("secret", "right".toCharArray()) }

        try {
            runBlocking { crypto.decrypt(blob, "wrong".toCharArray()) }
            fail("Expected decryption with the wrong password to throw (GCM auth must fail)")
        } catch (expected: Exception) {
            // expected — both the current and legacy keys fail GCM authentication
        }
    }

    /**
     * Mirrors [CryptoManager]'s legacy output format so we can prove
     * backward-compatibility without exposing internals from the production class.
     */
    private fun legacyEncrypt(plaintext: String, password: CharArray, iterations: Int): ByteArray {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val keyBytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(PBEKeySpec(password, salt, iterations, 256)).encoded
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(128, iv))
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return salt + iv + ciphertext
    }
}
