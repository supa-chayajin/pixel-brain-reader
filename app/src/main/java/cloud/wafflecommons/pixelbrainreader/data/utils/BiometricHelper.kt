package cloud.wafflecommons.pixelbrainreader.data.utils

import android.content.Context
import androidx.fragment.app.FragmentActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import javax.inject.Inject
import dagger.hilt.android.qualifiers.ActivityContext
import dagger.hilt.android.scopes.ActivityScoped
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * Helper to trigger Biometric Prompts via Coroutines.
 * Currently stubbed as we need to be in an Activity context or inject it properly.
 * For now, we provide the logic to check availability.
 */
class BiometricHelper @Inject constructor(
    @ActivityContext private val context: Context
) {

    fun canAuthenticate(): Boolean {
        val biometricManager = BiometricManager.from(context)
        return biometricManager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS
    }

    /**
     * Suspending function to prompt user.
     * Note: Must be called from a UI context (Fragment/Activity).
     * Since we are in data layer, this is just a helper logic holder.
     * In a real Clean Arch, this belongs in UI layer or bridged via an Interface.
     */
    suspend fun authenticate(activity: FragmentActivity, title: String = "Unlock Vault"): Boolean = suspendCoroutine { cont ->
        val executor = ContextCompat.getMainExecutor(activity)
        
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                super.onAuthenticationSucceeded(result)
                cont.resume(true)
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                super.onAuthenticationError(errorCode, errString)
                cont.resume(false)
            }
            
            override fun onAuthenticationFailed() {
                super.onAuthenticationFailed()
                // Do not resume, let user try again
            }
        }
        
        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle("Authenticate to access secure content")
            .setNegativeButtonText("Cancel")
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .build()
            
        val biometricPrompt = BiometricPrompt(activity, executor, callback)
        biometricPrompt.authenticate(promptInfo)
    }
}
