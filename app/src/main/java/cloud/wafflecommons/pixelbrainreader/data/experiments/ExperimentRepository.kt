package cloud.wafflecommons.pixelbrainreader.data.experiments

import android.util.Log
import cloud.wafflecommons.pixelbrainreader.data.device.VaultWriteGate
import cloud.wafflecommons.pixelbrainreader.data.health.HealthInsightsRepository
import cloud.wafflecommons.pixelbrainreader.data.model.HabitStatus
import cloud.wafflecommons.pixelbrainreader.data.repository.FileRepository
import cloud.wafflecommons.pixelbrainreader.data.repository.HabitRepository
import cloud.wafflecommons.pixelbrainreader.data.sync.VaultPushScheduler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Personal experiments (L2, 12.21) in the vault: `experiments.json`, read from disk before every
 * change, an unreadable file moved aside before anything is written, nothing written on a
 * read-only device. Each experiment's standing is computed from the same days as the health
 * insights (L1) and the habit's ticks.
 */
@Singleton
class ExperimentRepository @Inject constructor(
    private val fileRepository: FileRepository,
    private val pushScheduler: VaultPushScheduler,
    private val healthInsightsRepository: HealthInsightsRepository,
    private val habitRepository: HabitRepository
) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        prettyPrint = true
        encodeDefaults = true
    }
    private val mutex = Mutex()

    /** An experiment as it stands: its phase, today's day in it, and the result so far. */
    data class Standing(
        val experiment: Experiment,
        val outcome: Experiments.Outcome?,
        val habitTitle: String?,
        val phase: Experiments.Phase?,
        val dayOfPhase: Int?,
        val result: Experiments.Result?
    )

    suspend fun read(): ExperimentFile = withContext(Dispatchers.IO) { load().file ?: ExperimentFile() }

    /** Applies [change] to the experiments on disk; what is now saved, or null when nothing could be. */
    suspend fun update(change: (ExperimentFile) -> ExperimentFile): ExperimentFile? {
        if (VaultWriteGate.refuses()) return null
        return withContext(Dispatchers.IO) {
            mutex.withLock {
                val saved = load()
                val current = saved.file ?: ExperimentFile()
                val next = change(current)
                if (next == current) return@withLock current
                if (saved.unreadable && fileRepository.moveAsideCorrupt(PATH) == null) {
                    Log.e(TAG, "Unreadable experiments file could not be kept aside; not overwriting it")
                    return@withLock null
                }
                try {
                    fileRepository.saveFileLocally(PATH, json.encodeToString(ExperimentFile.serializer(), next))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to save the experiments", e)
                    return@withLock null
                }
                pushScheduler.requestPush()
                next
            }
        }
    }

    /** Every experiment as it stands [today], newest first. */
    suspend fun standings(today: LocalDate): List<Standing> = withContext(Dispatchers.IO) {
        val experiments = read().experiments
        if (experiments.isEmpty()) return@withContext emptyList()
        val first = experiments.mapNotNull { Experiments.startOf(it) }.minOrNull()
        val lastDay = experiments.mapNotNull { Experiments.lastDay(it) }.maxOrNull()
        // Measured days end yesterday: today is still being lived.
        val last = lastDay?.let { minOf(it, today.minusDays(1)) }
        val range = if (first != null && last != null && !last.isBefore(first)) first to last else null
        val days = range?.let { (from, to) -> healthInsightsRepository.days(from, to) }.orEmpty()
        val logs = range?.let { (from, to) -> habitRepository.getLogsBetweenFlow(from, to).first() }.orEmpty()
        val titles = habitRepository.getHabitConfigs().associate { it.id to it.title }
        experiments.map { experiment ->
            val outcome = Experiments.Outcome.of(experiment.outcome)
            val done = logs[experiment.habitId].orEmpty()
                .filter { it.status == HabitStatus.COMPLETED }
                .mapNotNull { runCatching { LocalDate.parse(it.date) }.getOrNull() }
                .toSet()
            Standing(
                experiment = experiment,
                outcome = outcome,
                habitTitle = titles[experiment.habitId],
                phase = Experiments.phase(experiment, today),
                dayOfPhase = Experiments.dayOfPhase(experiment, today),
                result = outcome?.let { Experiments.result(experiment, Experiments.valuesOf(it, days), done, today) }
            )
        }.sortedByDescending { it.experiment.start }
    }

    private class Saved(val file: ExperimentFile?, val unreadable: Boolean)

    private suspend fun load(): Saved {
        val raw = try {
            fileRepository.readFile(PATH)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        if (raw.isNullOrBlank()) return Saved(null, unreadable = false)
        return try {
            Saved(json.decodeFromString(ExperimentFile.serializer(), raw), unreadable = false)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "The experiments file doesn't parse: ${e.javaClass.simpleName}")
            Saved(null, unreadable = true)
        }
    }

    companion object {
        private const val TAG = "Experiments"
        const val PATH = "10_Journal/data/experiments/experiments.json"
    }
}
