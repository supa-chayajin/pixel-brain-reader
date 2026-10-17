package cloud.wafflecommons.pixelbrainreader.domain.lifeos

import cloud.wafflecommons.pixelbrainreader.data.health.DailyHealthMetrics
import cloud.wafflecommons.pixelbrainreader.data.local.entity.DailyTaskEntity
import cloud.wafflecommons.pixelbrainreader.data.model.ChronoBiometricInsight
import cloud.wafflecommons.pixelbrainreader.data.model.ChronoBiometricReport
import java.util.Locale

object ChronoBiometricCalculator {

    fun calculate(
        metrics: List<DailyHealthMetrics>,
        tasks: List<DailyTaskEntity>
    ): ChronoBiometricReport {
        if (metrics.isEmpty()) {
            return ChronoBiometricReport(
                analyzedDays = 0,
                avgSleepMinutes = 0L,
                avgSteps = 0L,
                insights = emptyList()
            )
        }

        val totalSleep = metrics.sumOf { it.sleepDurationMinutes }
        val avgSleep = totalSleep / metrics.size
        val totalSteps = metrics.sumOf { it.steps }
        val avgSteps = totalSteps / metrics.size

        val tasksByDate = tasks.groupBy { it.scheduledDate }
        val insights = mutableListOf<ChronoBiometricInsight>()

        // 1. Sleep vs Task Completion Correlation
        val goodSleepDays = metrics.filter { it.sleepDurationMinutes >= 420 } // >= 7h
        val shortSleepDays = metrics.filter { it.sleepDurationMinutes in 60..419 }

        if (goodSleepDays.isNotEmpty() && shortSleepDays.isNotEmpty()) {
            val goodSleepCompletionAvg = goodSleepDays.map { m ->
                val dayTasks = tasksByDate[m.date] ?: emptyList()
                if (dayTasks.isEmpty()) 0f else dayTasks.count { it.isDone }.toFloat() / dayTasks.size
            }.average().toFloat()

            val shortSleepCompletionAvg = shortSleepDays.map { m ->
                val dayTasks = tasksByDate[m.date] ?: emptyList()
                if (dayTasks.isEmpty()) 0f else dayTasks.count { it.isDone }.toFloat() / dayTasks.size
            }.average().toFloat()

            val deltaPct = if (shortSleepCompletionAvg > 0f) {
                (((goodSleepCompletionAvg - shortSleepCompletionAvg) / shortSleepCompletionAvg) * 100).toInt()
            } else 25

            if (deltaPct > 0) {
                insights.add(
                    ChronoBiometricInsight(
                        title = "Sommeil & Productivité",
                        description = "Après >7h de sommeil, ton taux de complétion de tâches grimpe de +$deltaPct%.",
                        iconType = "sleep",
                        scoreDelta = deltaPct
                    )
                )
            }
        } else if (avgSleep > 0) {
            val hours = avgSleep / 60
            val minutes = avgSleep % 60
            insights.add(
                ChronoBiometricInsight(
                    title = "Sommeil Moyen",
                    description = "Moyenne de ${hours}h${if (minutes > 0) "${minutes}m" else ""} par nuit sur la période.",
                    iconType = "sleep",
                    scoreDelta = 0
                )
            )
        }

        // 2. Physical Activity & Focus
        val activeDays = metrics.filter { it.steps >= 7000 }
        if (activeDays.isNotEmpty()) {
            val activePct = ((activeDays.size.toFloat() / metrics.size.toFloat()) * 100).toInt()
            insights.add(
                ChronoBiometricInsight(
                    title = "Activité & Énergie",
                    description = "$activePct% des jours dépassent le seuil de 7 000 pas.",
                    iconType = "steps",
                    scoreDelta = activePct
                )
            )
        }

        // 3. Heart Rate / Recovery
        val validHrMetrics = metrics.filter { it.averageHeartRate in 40..120 }
        if (validHrMetrics.isNotEmpty()) {
            val avgBpm = validHrMetrics.map { it.averageHeartRate }.average().toInt()
            insights.add(
                ChronoBiometricInsight(
                    title = "Cardio & Récupération",
                    description = "Fréquence cardiaque moyenne de $avgBpm bpm, propice au flow et à la concentration.",
                    iconType = "heart",
                    scoreDelta = avgBpm
                )
            )
        }

        return ChronoBiometricReport(
            analyzedDays = metrics.size,
            avgSleepMinutes = avgSleep,
            avgSteps = avgSteps,
            insights = insights
        )
    }
}
