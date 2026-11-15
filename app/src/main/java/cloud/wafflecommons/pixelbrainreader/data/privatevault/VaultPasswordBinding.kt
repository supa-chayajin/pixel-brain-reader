package cloud.wafflecommons.pixelbrainreader.data.privatevault

import android.util.Log
import cloud.wafflecommons.pixelbrainreader.data.local.security.SecretManager
import kotlinx.coroutines.CancellationException
import java.nio.ByteBuffer
import java.nio.CharBuffer
import javax.crypto.Cipher
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The Keystore side of [VaultPasswordBinding], behind an interface so the binding's rules run in
 * JVM tests ([KeystorePasswordKeys] is the device's).
 */
interface PasswordKeys {
    /** A strong (Class 3) biometric is enrolled: the only kind Android lets open the key. */
    fun strongBiometricReady(): Boolean

    fun hasKey(): Boolean

    /** Wraps [secret] with the key's public half, creating the key first if needed. Needs no prompt. */
    fun wrap(secret: ByteArray): ByteArray

    /** A cipher for BiometricPrompt to authorize, or null: no key, or one a new enrolment invalidated (then deleted). */
    fun unwrapCipher(): Cipher?

    /** Unwraps with a [cipher] the prompt authorized. */
    fun unwrap(cipher: Cipher, wrapped: ByteArray): ByteArray

    fun deleteKey()
}

/**
 * The private journal's password behind a strong biometric (12.18, V3; owner, 2026-11-15). The phone
 * keeps it only wrapped by a Keystore key that a strong face or fingerprint opens, one prompt at a
 * time (D5: the face first when the phone rates it strong, then the fingerprint, then the typed
 * password; never the phone's PIN). Nothing in the background can read it.
 *
 * Until 12.17 the password sat readable in the encrypted preferences. [migrateLegacy] wraps that
 * copy, and [confirmUnlock] deletes it at the first unlock that proves a way in (the biometric that
 * opened the wrapped copy, or the typed password), so a forgotten password with a working
 * fingerprint never locks anyone out.
 */
@Singleton
class VaultPasswordBinding @Inject constructor(
    private val keys: PasswordKeys,
    private val secrets: SecretManager
) {
    /** The device can bind the password: a strong biometric is enrolled. */
    fun available(): Boolean = keys.strongBiometricReady()

    fun isBound(): Boolean = secrets.wrappedVaultPassword() != null && keys.hasKey()

    /** Wraps [password] (no prompt). False without a strong biometric, and then nothing is kept. */
    @Synchronized
    fun bind(password: CharArray): Boolean {
        if (!available()) {
            forget()
            return false
        }
        val bytes = utf8(password)
        return try {
            secrets.saveWrappedVaultPassword(keys.wrap(bytes))
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "The password couldn't be bound: ${e.javaClass.simpleName}")
            false
        } finally {
            bytes.fill(0)
        }
    }

    /**
     * The cipher BiometricPrompt opens, or null: nothing bound, or a key a new face or fingerprint
     * invalidated (the wrapped copy is dropped: the password is typed once, then bound again).
     */
    fun cipherForUnlock(): Cipher? {
        migrateLegacy()
        if (secrets.wrappedVaultPassword() == null) return null
        val cipher = try {
            keys.unwrapCipher()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // A Keystore hiccup: the wrapped copy stays for the next try, the password is typed now.
            Log.w(TAG, "The biometric key couldn't be opened: ${e.javaClass.simpleName}")
            return null
        }
        // No key, or one a new enrolment invalidated: the wrapped copy can never open again.
        if (cipher == null) secrets.clearWrappedVaultPassword()
        return cipher
    }

    /** The password, from a [cipher] the prompt authorized. Null when it can't be read. The caller wipes it. */
    fun unwrap(cipher: Cipher): CharArray? {
        val wrapped = secrets.wrappedVaultPassword() ?: return null
        return try {
            val bytes = keys.unwrap(cipher, wrapped)
            try {
                chars(bytes)
            } finally {
                bytes.fill(0)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "The wrapped password couldn't be read: ${e.javaClass.simpleName}")
            null
        }
    }

    /**
     * After an unlock the notes verified: a typed password is bound (it may be newer than the wrapped
     * one: a change made on another device), then the 12.17 readable copy is deleted.
     */
    fun confirmUnlock(password: CharArray, viaBiometric: Boolean) {
        if (!viaBiometric) bind(password)
        secrets.clearLegacyVaultPassword()
    }

    /** Wraps the 12.17 readable copy (no prompt); [confirmUnlock] deletes it. Cheap once done. */
    @Synchronized
    fun migrateLegacy() {
        if (isBound()) return
        val legacy = secrets.legacyVaultPassword()?.takeIf { it.isNotEmpty() } ?: return
        val chars = legacy.toCharArray()
        try {
            if (bind(chars)) Log.i(TAG, "The stored password is now bound to the biometric key")
        } finally {
            chars.fill('\u0000')
        }
    }

    /** Drops the wrapped copy and its key (a logout, a device without a strong biometric). */
    fun forget() {
        secrets.clearWrappedVaultPassword()
        keys.deleteKey()
    }

    private fun utf8(password: CharArray): ByteArray {
        val buffer = Charsets.UTF_8.encode(CharBuffer.wrap(password))
        val bytes = ByteArray(buffer.remaining()).also { buffer.get(it) }
        if (buffer.hasArray()) buffer.array().fill(0)
        return bytes
    }

    private fun chars(bytes: ByteArray): CharArray {
        val buffer = Charsets.UTF_8.decode(ByteBuffer.wrap(bytes))
        val chars = CharArray(buffer.remaining()).also { buffer.get(it) }
        if (buffer.hasArray()) buffer.array().fill('\u0000')
        return chars
    }

    private companion object {
        const val TAG = "PrivateVault"
    }
}
