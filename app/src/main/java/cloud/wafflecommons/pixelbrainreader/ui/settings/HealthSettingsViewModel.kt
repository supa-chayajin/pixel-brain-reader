package cloud.wafflecommons.pixelbrainreader.ui.settings

import androidx.health.connect.client.HealthConnectClient
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cloud.wafflecommons.pixelbrainreader.data.device.VaultWriteGate
import cloud.wafflecommons.pixelbrainreader.data.health.HealthConnectManager
import cloud.wafflecommons.pixelbrainreader.data.usecase.SyncHealthDataUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** Health Connect on this phone: its SDK status (0 until checked) and whether every core permission is granted. */
data class HealthStatus(val sdk: Int = 0, val granted: Boolean = false)

/** Settings › Connections › Health Connect (13.4, S-b): the connection's status, its permissions, a first read. */
@HiltViewModel
class HealthSettingsViewModel @Inject constructor(
    private val healthConnectManager: HealthConnectManager,
    private val syncHealthDataUseCase: SyncHealthDataUseCase
) : ViewModel() {

    private val _status = MutableStateFlow(HealthStatus())
    val status: StateFlow<HealthStatus> = _status.asStateFlow()

    /** Re-read on each return to the page: the permissions may have changed in Health Connect itself. */
    fun check() {
        viewModelScope.launch {
            _status.value = withContext(Dispatchers.IO) {
                val sdk = runCatching { healthConnectManager.getSdkStatus() }.getOrDefault(HealthConnectClient.SDK_UNAVAILABLE)
                HealthStatus(sdk = sdk, granted = sdk == HealthConnectClient.SDK_AVAILABLE && healthConnectManager.checkPermissions())
            }
        }
    }

    /** Every permission the "Connected" check needs: asking for fewer left most figures at 0. */
    fun permissions(): Set<String> = healthConnectManager.getRequiredPermissions()

    /** Today's figures read now, once the permissions are granted (they go to the vault's health JSON). */
    fun syncNow() {
        // The phone writes the day's figures; a read-only device never does (settled decision #18).
        if (VaultWriteGate.readOnly) return
        viewModelScope.launch { syncHealthDataUseCase() }
    }
}
