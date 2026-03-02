package cloud.wafflecommons.pixelbrainreader.data.usecase

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import cloud.wafflecommons.pixelbrainreader.data.health.HealthConnectManager
import com.google.gson.Gson
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate
import javax.inject.Inject

class SyncHealthDataUseCase @Inject constructor(
    private val healthConnectManager: HealthConnectManager,
    @ApplicationContext private val context: Context
) {

    suspend operator fun invoke(date: LocalDate = LocalDate.now()): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            if (healthConnectManager.checkAvailability() != HealthConnectClient.SDK_AVAILABLE) {
                return@withContext Result.failure(Exception("Health Connect SDK is not available"))
            }

            val metrics = healthConnectManager.getDailyMetrics(date)
            val json = Gson().toJson(metrics)

            val file = File(context.filesDir, "10_Journal/data/health/metrics/$date.json")
            file.parentFile?.mkdirs()
            file.writeText(json)

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
