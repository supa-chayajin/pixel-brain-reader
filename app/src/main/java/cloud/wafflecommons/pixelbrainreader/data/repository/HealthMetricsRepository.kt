package cloud.wafflecommons.pixelbrainreader.data.repository

import android.util.Log
import cloud.wafflecommons.pixelbrainreader.data.health.DailyHealthMetrics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class HealthMetricsRepository @Inject constructor(
    private val fileRepository: FileRepository
) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private fun pathFor(date: LocalDate): String =
        "10_Journal/data/health/metrics/$date.json"

    fun getMetricsFlow(date: LocalDate): Flow<DailyHealthMetrics?> = flow {
        emit(decodeMetricsAt(pathFor(date)))
    }.flowOn(Dispatchers.IO)

    fun getMetricsHistoryFlow(startDate: LocalDate, days: Int): Flow<List<DailyHealthMetrics>> = flow {
        val history = mutableListOf<DailyHealthMetrics>()
        (0 until days).forEach { offset ->
            val date = startDate.minusDays(offset.toLong())
            decodeMetricsAt(pathFor(date))?.let { history.add(it) }
        }
        emit(history)
    }.flowOn(Dispatchers.IO)

    private suspend fun decodeMetricsAt(path: String): DailyHealthMetrics? {
        val content = try {
            fileRepository.readFile(path)
        } catch (e: Exception) {
            Log.w("HealthMetricsRepository", "Failed to read $path: ${e.message}")
            null
        } ?: return null

        if (content.isBlank()) return null
        return try {
            json.decodeFromString<DailyHealthMetrics>(content)
        } catch (e: Exception) {
            Log.w("HealthMetricsRepository", "Failed to decode $path: ${e.message}")
            null
        }
    }
}
