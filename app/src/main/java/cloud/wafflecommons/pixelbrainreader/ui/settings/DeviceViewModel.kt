package cloud.wafflecommons.pixelbrainreader.ui.settings

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cloud.wafflecommons.pixelbrainreader.data.local.security.SecretManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** The vault's repository as the Vault row shows it (13.4): still being read, saved, none saved, or unreadable. */
sealed interface DeviceVault {
    data object Reading : DeviceVault

    /** "owner/name". */
    data class Saved(val name: String) : DeviceVault

    data object None : DeviceVault

    data object Unreadable : DeviceVault
}

/** Settings › Vault & device › This device & version (13.4, S-i): the vault this device reads. */
@HiltViewModel
class DeviceViewModel @Inject constructor(
    private val secretManager: SecretManager
) : ViewModel() {

    private val _vault = MutableStateFlow<DeviceVault>(DeviceVault.Reading)

    /** The vault's repository: [DeviceVault.Reading] until the encrypted preferences answered. */
    val vault: StateFlow<DeviceVault> = _vault.asStateFlow()

    init {
        viewModelScope.launch {
            _vault.value = try {
                // Encrypted preferences: read off the main thread.
                withContext(Dispatchers.IO) {
                    val (owner, name) = secretManager.getRepoInfo()
                    if (owner.isNullOrBlank() || name.isNullOrBlank()) DeviceVault.None else DeviceVault.Saved("$owner/$name")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "The vault's repository couldn't be read: ${e.javaClass.simpleName}")
                DeviceVault.Unreadable
            }
        }
    }

    private companion object {
        const val TAG = "DeviceSettings"
    }
}
