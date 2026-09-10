package cloud.wafflecommons.pixelbrainreader.data.local.security

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** The private vault's encryption: correctness, legacy compatibility and the key-cache contract. */
class CryptoManagerTest {

    private val password = "correct horse battery staple".toCharArray()

    @Test
    fun `encrypted notes decrypt with the same password`() = runBlocking {
        val crypto = CryptoManager()

        val encrypted = crypto.encrypt("Cher journal, aujourd'hui…", password)

        assertEquals("Cher journal, aujourd'hui…", crypto.decrypt(encrypted, password))
    }

    @Test
    fun `a wrong password never decrypts`() = runBlocking {
        val crypto = CryptoManager()
        val encrypted = crypto.encrypt("secret", password)

        assertTrue(runCatching { crypto.decrypt(encrypted, "wrong password".toCharArray()) }.isFailure)
    }

    @Test
    fun `notes written with the legacy work factor still open`() = runBlocking {
        val salt = ByteArray(16) { it.toByte() }
        val iv = ByteArray(12) { (it + 1).toByte() }
        val keyBytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(PBEKeySpec(password, salt, 10_000, 256)).encoded
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(128, iv))
        val legacyFile = salt + iv + cipher.doFinal("ancienne note".toByteArray())

        assertEquals("ancienne note", CryptoManager().decrypt(legacyFile, password))
    }

    @Test
    fun `one password shares its salt across files while every file keeps its own IV`() = runBlocking {
        val crypto = CryptoManager()

        val first = crypto.encrypt("a", password)
        val second = crypto.encrypt("b", password)
        val otherPassword = crypto.encrypt("c", "another password".toCharArray())

        assertArrayEquals(first.copyOfRange(0, 16), second.copyOfRange(0, 16))
        assertFalse(first.copyOfRange(16, 28).contentEquals(second.copyOfRange(16, 28)))
        assertFalse(first.copyOfRange(0, 16).contentEquals(otherPassword.copyOfRange(0, 16)))
    }

    @Test
    fun `files still decrypt after the key cache is cleared`() = runBlocking {
        val crypto = CryptoManager()
        val encrypted = crypto.encrypt("kept", password)

        crypto.clearKeyCache()

        assertEquals("kept", crypto.decrypt(encrypted, password))
    }
}
