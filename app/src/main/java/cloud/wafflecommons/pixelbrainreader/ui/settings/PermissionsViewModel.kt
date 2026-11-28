package cloud.wafflecommons.pixelbrainreader.ui.settings

import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.health.connect.client.HealthConnectClient
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cloud.wafflecommons.pixelbrainreader.data.health.HealthConnectManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Settings › Privacy & safety › Permissions (13.4, S11): whether each permission the app uses is
 * granted, read again each time the page comes back (the system's pages change them).
 */
@HiltViewModel
class PermissionsViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val healthConnectManager: HealthConnectManager
) : ViewModel() {

    private val _granted = MutableStateFlow<Map<String, Boolean?>>(emptyMap())

    /** [AppPermission.key] → granted; null: not on this Android (Live updates before 36.1, no Health Connect). Absent: not read yet. */
    val granted: StateFlow<Map<String, Boolean?>> = _granted.asStateFlow()

    /** What Health Connect's sheet asks for: every permission the app reads, as its Connect does. */
    fun healthPermissions(): Set<String> = healthConnectManager.getRequiredPermissions()

    fun refresh() {
        viewModelScope.launch {
            _granted.value = withContext(Dispatchers.IO) { AppPermissions.ALL.associate { it.key to grantedOf(it) } }
        }
    }

    private suspend fun grantedOf(permission: AppPermission): Boolean? = when (permission.kind) {
        PermissionKind.RUNTIME -> permission.manifest.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
        PermissionKind.PROMOTED -> promotedAllowed()
        PermissionKind.NOTIFICATION_POLICY -> notifications()?.isNotificationPolicyAccessGranted
        PermissionKind.HEALTH -> healthGranted()
        PermissionKind.ALWAYS -> true
    }

    /** Live updates: from 36.1 (the guard FocusPlatform uses); null before. */
    private fun promotedAllowed(): Boolean? =
        if (Build.VERSION.SDK_INT_FULL >= Build.VERSION_CODES_FULL.BAKLAVA_1) notifications()?.canPostPromotedNotifications() else null

    /** The Health Connect page's "Connected" check; null without Health Connect on the phone. */
    private suspend fun healthGranted(): Boolean? = try {
        if (healthConnectManager.getSdkStatus() == HealthConnectClient.SDK_AVAILABLE) healthConnectManager.checkPermissions() else null
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "Health Connect couldn't be asked: ${e.javaClass.simpleName}")
        null
    }

    private fun notifications(): NotificationManager? = context.getSystemService(NotificationManager::class.java)

    private companion object {
        const val TAG = "Permissions"
    }
}
