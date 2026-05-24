package cloud.wafflecommons.pixelbrainreader.data.local.security

import android.security.keystore.KeyProperties
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import java.security.SecureRandom
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Singleton
class CryptoManager @Inject constructor() {

    companion object {
        private const val ALGORITHM = KeyProperties.KEY_ALGORITHM_AES
        private const val BLOCK_MODE = KeyProperties.BLOCK_MODE_GCM
        private const val PADDING = KeyProperties.ENCRYPTION_PADDING_NONE
        private const val TRANSFORMATION = "$ALGORITHM/$BLOCK_MODE/$PADDING"
        
        // PBKDF2 Constants
        private const val PBKDF2_ALGORITHM = "PBKDF2WithHmacSHA256"
        private const val SALT_SIZE_BYTES = 16
        private const val IV_SIZE_BYTES = 12
        private const val KEY_SIZE_BITS = 256

        // Work factor for NEW encryptions. Raised from the original 10_000 to
        // harden the private vault against offline brute-force of the password.
        // Tuned as a mobile compromise — IndexingWorker derives a key per chunk,
        // so this is a direct multiplier on indexing latency; raise further only
        // if that budget allows.
        private const val ITERATION_COUNT = 210_000

        // Iteration count used by older builds. Retained ONLY so notes encrypted
        // before the upgrade still open: decrypt() falls back to this when the
        // primary key fails GCM authentication. Do NOT remove while any legacy
        // .md.enc files may still exist in a vault.
        private const val LEGACY_ITERATION_COUNT = 10_000
    }

    /**
     * Encrypts plaintext using AES-256-GCM.
     * Generates a random salt and IV.
     * The output format is [Salt (16)] + [IV (12)] + [Encrypted Data].
     *
     * Suspending + Dispatchers.Default because PBKDF2 (ITERATION_COUNT rounds)
     * is CPU-bound. Callers no longer need to wrap in withContext themselves.
     */
    suspend fun encrypt(plaintext: String, password: CharArray): ByteArray =
        withContext(Dispatchers.Default) {
            val salt = ByteArray(SALT_SIZE_BYTES)
            SecureRandom().nextBytes(salt)

            val secretKey = deriveKey(password, salt, ITERATION_COUNT)

            val iv = ByteArray(IV_SIZE_BYTES)
            SecureRandom().nextBytes(iv)

            val cipher = Cipher.getInstance(TRANSFORMATION)
            val spec = GCMParameterSpec(128, iv)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, spec)

            val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))

            salt + iv + ciphertext
        }

    /**
     * Decrypts the packed byte array.
     * Extracts Salt and IV, derives the key, and decrypts.
     */
    suspend fun decrypt(fileData: ByteArray, password: CharArray): String =
        withContext(Dispatchers.Default) {
            val salt = fileData.copyOfRange(0, SALT_SIZE_BYTES)
            val iv = fileData.copyOfRange(SALT_SIZE_BYTES, SALT_SIZE_BYTES + IV_SIZE_BYTES)
            val ciphertext = fileData.copyOfRange(SALT_SIZE_BYTES + IV_SIZE_BYTES, fileData.size)

            // Try the current work factor first. AES-GCM is authenticated, so a
            // key derived with the wrong iteration count fails with a definitive
            // AEADBadTagException instead of returning garbage — which lets us
            // transparently fall back to the legacy count for notes written by
            // older builds. New writes always use ITERATION_COUNT, so a vault
            // migrates forward naturally as files are re-saved.
            try {
                decryptWith(salt, iv, ciphertext, password, ITERATION_COUNT)
            } catch (e: AEADBadTagException) {
                decryptWith(salt, iv, ciphertext, password, LEGACY_ITERATION_COUNT)
            }
        }

    private fun decryptWith(
        salt: ByteArray,
        iv: ByteArray,
        ciphertext: ByteArray,
        password: CharArray,
        iterations: Int
    ): String {
        val secretKey = deriveKey(password, salt, iterations)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        val spec = GCMParameterSpec(128, iv)
        cipher.init(Cipher.DECRYPT_MODE, secretKey, spec)
        val plaintextBytes = cipher.doFinal(ciphertext)
        return String(plaintextBytes, Charsets.UTF_8)
    }

    /**
     * Derives a 256-bit AES key from the password and salt using PBKDF2.
     */
    private fun deriveKey(password: CharArray, salt: ByteArray, iterations: Int): SecretKey {
        val spec = PBEKeySpec(password, salt, iterations, KEY_SIZE_BITS)
        val factory = SecretKeyFactory.getInstance(PBKDF2_ALGORITHM)
        val secretKeyBytes = factory.generateSecret(spec).encoded
        return SecretKeySpec(secretKeyBytes, ALGORITHM)
    }
}
