package cloud.wafflecommons.pixelbrainreader.data.local.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import java.security.SecureRandom

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
        private const val ITERATION_COUNT = 10000
    }

    /**
     * Encrypts plaintext using AES-256-GCM.
     * Generates a random salt and IV.
     * The output format is [Salt (16)] + [IV (12)] + [Encrypted Data].
     */
    fun encrypt(plaintext: String, password: CharArray): ByteArray {
        // 1. Generate Salt
        val salt = ByteArray(SALT_SIZE_BYTES)
        SecureRandom().nextBytes(salt)

        // 2. Derive Key
        val secretKey = deriveKey(password, salt)

        // 3. Generate IV
        val iv = ByteArray(IV_SIZE_BYTES)
        SecureRandom().nextBytes(iv)

        // 4. Initialize Cipher
        val cipher = Cipher.getInstance(TRANSFORMATION)
        val spec = GCMParameterSpec(128, iv)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, spec)

        // 5. Encrypt
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))

        // 6. Pack: Salt + IV + Ciphertext
        return salt + iv + ciphertext
    }

    /**
     * Decrypts the packed byte array.
     * Extracts Salt and IV, derives the key, and decrypts.
     */
    fun decrypt(fileData: ByteArray, password: CharArray): String {
        // 1. Extract Salt
        val salt = fileData.copyOfRange(0, SALT_SIZE_BYTES)
        
        // 2. Extract IV
        val iv = fileData.copyOfRange(SALT_SIZE_BYTES, SALT_SIZE_BYTES + IV_SIZE_BYTES)
        
        // 3. Extract Ciphertext (The rest)
        val ciphertext = fileData.copyOfRange(SALT_SIZE_BYTES + IV_SIZE_BYTES, fileData.size)

        // 4. Derive Key (Must match encryption key)
        val secretKey = deriveKey(password, salt)

        // 5. Decrypt
        val cipher = Cipher.getInstance(TRANSFORMATION)
        val spec = GCMParameterSpec(128, iv)
        cipher.init(Cipher.DECRYPT_MODE, secretKey, spec)

        val plaintextBytes = cipher.doFinal(ciphertext)
        return String(plaintextBytes, Charsets.UTF_8)
    }

    /**
     * Derives a 256-bit AES key from the password and salt using PBKDF2.
     */
    private fun deriveKey(password: CharArray, salt: ByteArray): SecretKey {
        val spec = PBEKeySpec(password, salt, ITERATION_COUNT, KEY_SIZE_BITS)
        val factory = SecretKeyFactory.getInstance(PBKDF2_ALGORITHM)
        val secretKeyBytes = factory.generateSecret(spec).encoded
        return SecretKeySpec(secretKeyBytes, ALGORITHM)
    }
}
