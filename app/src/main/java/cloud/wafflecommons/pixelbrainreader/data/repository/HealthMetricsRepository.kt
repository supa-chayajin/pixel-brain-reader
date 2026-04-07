package cloud.wafflecommons.pixelbrainreader.data.repository

import android.content.Context
import cloud.wafflecommons.pixelbrainreader.data.health.DailyHealthMetrics
import com.google.gson.Gson
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.File
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class HealthMetricsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val gson: Gson
) {
    fun getMetricsFlow(date: LocalDate): Flow<DailyHealthMetrics?> {
        return flow {
            val metricsFile = File(context.filesDir, "10_Journal/data/health/metrics/$date.json")
            if (metricsFile.exists()) {
                try {
                    val content = metricsFile.readText()
                    val metrics = gson.fromJson(content, DailyHealthMetrics::class.java)
                    emit(metrics)
                    // Note: We don't have file observation here yet, but this is reactive to date change.
                } catch (e: Exception) {
                    emit(null)
                }
            } else {
                emit(null)
            }
        }.flowOn(Dispatchers.IO)
    }

    fun getMetricsHistoryFlow(startDate: LocalDate, days: Int): Flow<List<DailyHealthMetrics>> {
        return flow {
            val history = mutableListOf<DailyHealthMetrics>()
            (0 until days).forEach { offset ->
                val date = startDate.minusDays(offset.toLong())
                val metricsFile = File(context.filesDir, "10_Journal/data/health/metrics/$date.json")
                if (metricsFile.exists()) {
                    try {
                        val content = metricsFile.readText()
                        val metrics = gson.fromJson(content, DailyHealthMetrics::class.java)
                        if (metrics != null) history.add(metrics)
                    } catch (e: Exception) { }
                }
            }
            emit(history)
        }.flowOn(Dispatchers.IO)
    }
}
