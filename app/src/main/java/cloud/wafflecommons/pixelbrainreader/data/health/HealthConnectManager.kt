package cloud.wafflecommons.pixelbrainreader.data.health

import android.content.Context
import android.os.RemoteException
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class HealthConnectManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val healthConnectClient: HealthConnectClient? // Nullable in case SDK is not available
) {

    private val permissions = setOf(
        HealthPermission.getReadPermission(StepsRecord::class),
        HealthPermission.getReadPermission(SleepSessionRecord::class),
        HealthPermission.getReadPermission(HeartRateRecord::class)
    )

    fun getSdkStatus(): Int {
        return HealthConnectClient.getSdkStatus(context)
    }
    
    suspend fun checkPermissions(): Boolean {
        if (healthConnectClient == null) return false
        return try {
            val granted = healthConnectClient.permissionController.getGrantedPermissions()
            Log.d("HealthConnect", "Permissions Requested: ${permissions.size}")
            Log.d("HealthConnect", "Permissions Granted: ${granted.size} -> $granted")
            val hasAll = granted.containsAll(permissions)
            Log.d("HealthConnect", "Has All Permissions? $hasAll")
            return hasAll
        } catch (e: Exception) {
            Log.e("HealthConnect", "Error checking permissions", e)
            false
        }
    }
    
    suspend fun requestPermissions() {
        // This is usually launched from an Activity result contract, 
        // but we can expose the permissions set here for the UI to use.
    }
    
    fun getRequiredPermissions() = permissions

    suspend fun readSteps(start: Instant, end: Instant): Long = withContext(Dispatchers.IO) {
        val client = healthConnectClient ?: return@withContext 0L
        try {
            val response = client.aggregate(
                AggregateRequest(
                    metrics = setOf(StepsRecord.COUNT_TOTAL),
                    timeRangeFilter = TimeRangeFilter.between(start, end)
                )
            )
            response[StepsRecord.COUNT_TOTAL] ?: 0L
        } catch (e: Exception) {
            Log.e("HealthConnect", "Error reading steps", e)
            0L
        }
    }

    suspend fun readSleepDuration(start: Instant, end: Instant): Duration = withContext(Dispatchers.IO) {
        val client = healthConnectClient ?: return@withContext Duration.ZERO
        try {
            val response = client.readRecords(
                ReadRecordsRequest(
                    recordType = SleepSessionRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(start, end)
                )
            )
            
            // Sum durations of all sessions
            response.records.fold(Duration.ZERO) { acc, session ->
                val sessionDuration = Duration.between(session.startTime, session.endTime)
                acc.plus(sessionDuration)
            }
        } catch (e: Exception) {
            Log.e("HealthConnect", "Error reading sleep", e)
            Duration.ZERO
        }
    }

    suspend fun readHeartRate(start: Instant, end: Instant): List<HeartRateRecord> = withContext(Dispatchers.IO) {
        val client = healthConnectClient ?: return@withContext emptyList()
        try {
            val response = client.readRecords(
                ReadRecordsRequest(
                    recordType = HeartRateRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(start, end)
                )
            )
            response.records
        } catch (e: Exception) {
            Log.e("HealthConnect", "Error reading heart rate", e)
            emptyList()
        }
    }
}
