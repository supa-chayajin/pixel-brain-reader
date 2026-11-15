package cloud.wafflecommons.pixelbrainreader.data.privatevault

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.util.Log
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.spec.MGF1ParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The device's [PasswordKeys]: an RSA-2048 key in the Android Keystore whose private half decrypts
 * only after a strong biometric, at each use, and which a new enrolment invalidates. Its public half
 * wraps the password without a prompt, so a change of password binds the new one silently.
 */
@Singleton
class KeystorePasswordKeys @Inject constructor(
    @param:ApplicationContext private val context: Context
) : PasswordKeys {

    override fun strongBiometricReady(): Boolean =
        BiometricManager.from(context).canAuthenticate(BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS

    override fun hasKey(): Boolean = try {
        keyStore().containsAlias(ALIAS)
    } catch (e: Exception) {
        Log.w(TAG, "Keystore unavailable: ${e.javaClass.simpleName}")
        false
    }

    override fun wrap(secret: ByteArray): ByteArray {
        val store = keyStore()
        if (!store.containsAlias(ALIAS)) createKey()
        val certificate = keyStore().getCertificate(ALIAS) ?: error("No certificate for the biometric key")
        // A plain copy of the public key: encrypting with it needs no authentication.
        val publicKey = KeyFactory.getInstance(certificate.publicKey.algorithm)
            .generatePublic(X509EncodedKeySpec(certificate.publicKey.encoded))
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, publicKey, OAEP)
        return cipher.doFinal(secret)
    }

    override fun unwrapCipher(): Cipher? {
        val key = keyStore().getKey(ALIAS, null) as? PrivateKey ?: return null
        return try {
            Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.DECRYPT_MODE, key, OAEP) }
        } catch (e: KeyPermanentlyInvalidatedException) {
            Log.i(TAG, "A new face or fingerprint invalidated the biometric key: the password is asked once")
            deleteKey()
            null
        }
    }

    override fun unwrap(cipher: Cipher, wrapped: ByteArray): ByteArray = cipher.doFinal(wrapped)

    override fun deleteKey() {
        try {
            val store = keyStore()
            if (store.containsAlias(ALIAS)) store.deleteEntry(ALIAS)
        } catch (e: Exception) {
            Log.w(TAG, "The biometric key couldn't be deleted: ${e.javaClass.simpleName}")
        }
    }

    private fun createKey() {
        val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, ANDROID_KEYSTORE)
        generator.initialize(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(KEY_SIZE)
                .setDigests(KeyProperties.DIGEST_SHA256)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_RSA_OAEP)
                .setUserAuthenticationRequired(true)
                // At each use, and a strong biometric only: never the device's PIN (D5).
                .setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
                .setInvalidatedByBiometricEnrollment(true)
                .build()
        )
        generator.generateKeyPair()
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private companion object {
        const val TAG = "PrivateVault"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "pixelbrain_private_vault_password"
        const val KEY_SIZE = 2048
        const val TRANSFORMATION = "RSA/ECB/OAEPWithSHA-256AndMGF1Padding"

        // The Keystore's OAEP takes SHA-256 for the label digest and SHA-1 for MGF1: the public-key
        // side must say so too, or what it wraps won't unwrap.
        val OAEP = OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA1, PSource.PSpecified.DEFAULT)
    }
}
