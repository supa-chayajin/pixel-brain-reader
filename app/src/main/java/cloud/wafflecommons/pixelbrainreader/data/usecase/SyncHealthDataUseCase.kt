package cloud.wafflecommons.pixelbrainreader.data.usecase

import android.content.Context
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import cloud.wafflecommons.pixelbrainreader.data.health.HealthConnectManager
import cloud.wafflecommons.pixelbrainreader.data.repository.FileRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.time.LocalDate
import javax.inject.Inject

class SyncHealthDataUseCase @Inject constructor(
    private val healthConnectManager: HealthConnectManager,
    private val fileRepository: FileRepository,
    private val widgetUpdateManager: cloud.wafflecommons.pixelbrainreader.widget.ui.WidgetUpdateManager,
    @ApplicationContext private val context: Context
) {

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    suspend operator fun invoke(date: LocalDate = LocalDate.now()): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            if (healthConnectManager.checkAvailability() != HealthConnectClient.SDK_AVAILABLE) {
                return@withContext Result.failure(Exception("Health Connect SDK is not available"))
            }

            // 1) One-shot heal: move any pre-existing files written outside the vault
            // into the git-tracked vault root so the next push commits them.
            migrateLegacyMetricsIntoVault()

            // 2) Pull today's metrics from Health Connect and persist into the vault.
            val metrics = healthConnectManager.getDailyMetrics(date)
            val encoded = json.encodeToString(metrics)
            val vaultRelPath = "10_Journal/data/health/metrics/$date.json"
            fileRepository.saveFileLocally(vaultRelPath, encoded)

            // Health drives the snapshot-backed Health/Companion widgets — rebuild after new metrics.
            runCatching { widgetUpdateManager.scheduleSnapshotUpdate() }

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Legacy data lived at `filesDir/10_Journal/...` — outside the JGit vault root
     * (`filesDir/vault/...`). The export ran but git never staged the files.
     * Move them into the vault on the next invocation so the user doesn't lose
     * locally-cached history once we re-root the write path. Idempotent: once the
     * legacy directory is empty (and deleted) subsequent calls are no-ops.
     */
    private suspend fun migrateLegacyMetricsIntoVault() {
        val legacyDir = File(context.filesDir, "10_Journal/data/health/metrics")
        if (!legacyDir.exists() || !legacyDir.isDirectory) return

        val files = legacyDir.listFiles { f -> f.isFile && f.name.endsWith(".json") }
            ?: emptyArray()

        if (files.isEmpty()) {
            runCatching { legacyDir.deleteRecursively() }
            return
        }

        var migrated = 0
        files.forEach { file ->
            try {
                val content = file.readText()
                if (content.isNotBlank()) {
                    fileRepository.saveFileLocally(
                        "10_Journal/data/health/metrics/${file.name}",
                        content
                    )
                }
                file.delete()
                migrated++
            } catch (e: Exception) {
                Log.w("SyncHealthDataUseCase", "Failed to migrate ${file.name}: ${e.message}")
            }
        }

        Log.i("SyncHealthDataUseCase", "Migrated $migrated legacy health metric file(s) into vault")
        runCatching { legacyDir.deleteRecursively() }
    }
}
