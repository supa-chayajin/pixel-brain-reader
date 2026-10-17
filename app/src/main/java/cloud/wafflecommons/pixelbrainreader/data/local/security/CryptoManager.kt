package cloud.wafflecommons.pixelbrainreader.data.local.security

import android.security.keystore.KeyProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Arrays
import java.util.Base64
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton

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
        private const val ITERATION_COUNT = 210_000

        // Iteration count used by older builds. Retained ONLY so notes encrypted
        // before the upgrade still open: decrypt() falls back to this when the
        // primary key fails GCM authentication. Do NOT remove while any legacy
        // .md.enc files may still exist in a vault.
        private const val LEGACY_ITERATION_COUNT = 10_000

        /** Derived keys kept in memory (least recently used ones are dropped first). */
        private const val MAX_CACHED_KEYS = 128
    }

    private val secureRandom = SecureRandom()

    /** Random per-process pepper: cache entries are keyed by a peppered hash, never by the password. */
    private val pepper = ByteArray(32).also { secureRandom.nextBytes(it) }

    // PBKDF2 at ITERATION_COUNT rounds costs a few hundred milliseconds, and it used to run for EVERY
    // encryption and decryption: each private-journal autosave, each private chunk the indexer
    // encrypts, each private search hit. Keys are now derived once per (password, salt, rounds) per
    // process. Also guards [encryptionSalts].
    private val derivedKeys = object : LinkedHashMap<String, SecretKey>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, SecretKey>?): Boolean =
            size > MAX_CACHED_KEYS
    }

    /** The salt new encryptions use, per password (see [encryptionSaltFor]). Guarded by [derivedKeys]. */
    private val encryptionSalts = HashMap<String, ByteArray>()

    /**
     * Encrypts plaintext using AES-256-GCM.
     * The output format is [Salt (16)] + [IV (12)] + [Encrypted Data].
     *
     * Suspending + Dispatchers.Default because PBKDF2 (ITERATION_COUNT rounds)
     * is CPU-bound. Callers no longer need to wrap in withContext themselves.
     */
    suspend fun encrypt(plaintext: String, password: CharArray): ByteArray =
        withContext(Dispatchers.Default) {
            val salt = encryptionSaltFor(password)
            val secretKey = keyFor(password, salt, ITERATION_COUNT)

            val iv = ByteArray(IV_SIZE_BYTES)
            secureRandom.nextBytes(iv)

            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, GCMParameterSpec(128, iv))
            val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))

            salt + iv + ciphertext
        }

    /**
     * Decrypts the packed byte array.
     * Extracts Salt and IV, derives the key, and decrypts.
     */
    suspend fun decrypt(fileData: ByteArray, password: CharArray): String =
        withContext(Dispatchers.Default) {
            require(fileData.size > SALT_SIZE_BYTES + IV_SIZE_BYTES) { "Encrypted data is truncated" }
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

    /** Forgets every derived key and encryption salt (e.g. when the private vault is locked). */
    fun clearKeyCache() {
        synchronized(derivedKeys) {
            derivedKeys.clear()
            encryptionSalts.clear()
        }
    }

    private fun decryptWith(
        salt: ByteArray,
        iv: ByteArray,
        ciphertext: ByteArray,
        password: CharArray,
        iterations: Int
    ): String {
        val secretKey = keyFor(password, salt, iterations)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(128, iv))
        val plaintextBytes = cipher.doFinal(ciphertext)
        return String(plaintextBytes, Charsets.UTF_8)
    }

    /**
     * The salt new encryptions use for [password]: drawn once per password per process instead of
     * per file, so a session's writes share one key derivation. Every file stays unique through its
     * own random IV (the AES-GCM requirement), and all of the vault's files already share the one
     * password, so a shared salt gives an attacker nothing.
     */
    private fun encryptionSaltFor(password: CharArray): ByteArray {
        val id = fingerprint(password, salt = null, iterations = 0)
        synchronized(derivedKeys) {
            return encryptionSalts.getOrPut(id) { ByteArray(SALT_SIZE_BYTES).also { secureRandom.nextBytes(it) } }
        }
    }

    private fun keyFor(password: CharArray, salt: ByteArray, iterations: Int): SecretKey {
        val id = fingerprint(password, salt, iterations)
        synchronized(derivedKeys) { derivedKeys[id]?.let { return it } }
        val key = deriveKey(password, salt, iterations)
        synchronized(derivedKeys) { derivedKeys[id] = key }
        return key
    }

    /** Peppered SHA-256 of (password, salt, rounds): identifies a derivation without storing the password. */
    private fun fingerprint(password: CharArray, salt: ByteArray?, iterations: Int): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(pepper)
        val encoded = Charsets.UTF_8.encode(CharBuffer.wrap(password))
        val passwordBytes = ByteArray(encoded.remaining()).also { encoded.get(it) }
        digest.update(passwordBytes)
        Arrays.fill(passwordBytes, 0.toByte())
        if (encoded.hasArray()) Arrays.fill(encoded.array(), 0.toByte())
        salt?.let { digest.update(it) }
        digest.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(iterations).array())
        return Base64.getEncoder().encodeToString(digest.digest())
    }

    /**
     * Derives a 256-bit AES key from the password and salt using PBKDF2.
     */
    private fun deriveKey(password: CharArray, salt: ByteArray, iterations: Int): SecretKey {
        val spec = PBEKeySpec(password, salt, iterations, KEY_SIZE_BITS)
        val factory = SecretKeyFactory.getInstance(PBKDF2_ALGORITHM)
        val secretKeyBytes = factory.generateSecret(spec).encoded
        return try {
            SecretKeySpec(secretKeyBytes, ALGORITHM)
        } finally {
            secretKeyBytes.fill(0)
            spec.clearPassword()
        }
    }
}
