package cloud.wafflecommons.pixelbrainreader.data.repository

import android.content.Context
import android.util.Log
import cloud.wafflecommons.pixelbrainreader.data.gamification.GamificationRepository
import cloud.wafflecommons.pixelbrainreader.data.health.HealthConnectManager
import cloud.wafflecommons.pixelbrainreader.widget.data.SnapshotMood
import cloud.wafflecommons.pixelbrainreader.widget.data.SnapshotPoint
import cloud.wafflecommons.pixelbrainreader.widget.data.WidgetDataSnapshot
import cloud.wafflecommons.pixelbrainreader.widget.ui.WidgetUpdateManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.flow.firstOrNull
import java.time.temporal.ChronoUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WidgetRepository @Inject constructor(
    private val healthConnectManager: HealthConnectManager,
    private val gamificationRepository: GamificationRepository,
    private val moodRepository: MoodRepository,
    private val widgetUpdateManager: WidgetUpdateManager
) {
    private val snapshotFileName = "widget_snapshot.json"
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    // This repository is deprecated in favor of WidgetSnapshotManager
    // Keeping simple file accessor for now if needed.

    fun getSnapshotFile(context: Context): File {
        return File(context.filesDir, snapshotFileName)
    }
}
