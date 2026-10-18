package cloud.wafflecommons.pixelbrainreader.data.work

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequest
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import cloud.wafflecommons.pixelbrainreader.data.local.dao.FileContentDao
import cloud.wafflecommons.pixelbrainreader.data.repository.FileRepository
import cloud.wafflecommons.pixelbrainreader.data.sync.VaultPushScheduler
import cloud.wafflecommons.pixelbrainreader.data.vault.FrontmatterLines
import cloud.wafflecommons.pixelbrainreader.data.vault.VaultSchema
import cloud.wafflecommons.pixelbrainreader.data.workers.ObjectiveSlotWorker
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** Objectives (#5): the tagged notes, their milestones, the log as material, and the weekly slot. */
@Singleton
class ObjectiveRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val fileContentDao: FileContentDao,
    private val missionLogRepository: MissionLogRepository,
    private val fileRepository: FileRepository,
    private val pushScheduler: VaultPushScheduler
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }
    private val writeMutex = Mutex()

    private val _settings = MutableStateFlow(ObjectiveSettings())
    val settings: StateFlow<ObjectiveSettings> = _settings.asStateFlow()

    private val _changes = MutableStateFlow(0L)
    val changes: StateFlow<Long> = _changes.asStateFlow()

    suspend fun reloadSettings(): ObjectiveSettings = withContext(Dispatchers.IO) {
        (readSettings() ?: ObjectiveSettings()).also { _settings.value = it }
    }

    /** Every objective in the vault, with the last year of the log as material, soonest milestone first. */
    suspend fun objectives(today: LocalDate = LocalDate.now()): List<Objective> = withContext(Dispatchers.IO) {
        val notes = fileContentDao.contentsUnder("")
            .filter { it.path.endsWith(".md") && !VaultSchema.isDailyNote(it.path) }
            .map { WorkNote.from(it.path, it.content) }
            .filter(ObjectiveFormat::isObjective)
        val log = try {
            missionLogRepository.entriesBetween(today.minusYears(1), today)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }
        notes.map { ObjectiveFormat.objective(it, log) }
            .sortedWith(compareBy<Objective> { it.nextDue?.due ?: LocalDate.MAX }.thenBy { it.note.title })
    }

    /** Flips a milestone in its note (read from disk) and touches `date_updated`. */
    suspend fun toggle(objective: Objective, milestone: Milestone) = withContext(Dispatchers.IO) {
        writeMutex.withLock {
            val path = objective.note.path
            val content = fileRepository.readFile(path) ?: throw IllegalStateException("The note is no longer there.")
            val toggled = ObjectiveFormat.toggle(content, milestone) ?: throw IllegalStateException("The milestone changed meanwhile; reload.")
            val touched = if (FrontmatterLines.hasFrontmatter(toggled)) {
                FrontmatterLines.set(toggled, "date_updated", VaultSchema.singleQuoted(LocalDate.now().toString()))
            } else {
                toggled
            }
            fileRepository.saveFileLocally(path, touched)
            pushScheduler.requestPush()
        }
        _changes.update { it + 1 }
    }

    suspend fun updateSettings(transform: (ObjectiveSettings) -> ObjectiveSettings) = withContext(Dispatchers.IO) {
        writeMutex.withLock {
            val base = readSettings() ?: run {
                if (fileRepository.fileExists(SETTINGS_PATH)) fileRepository.moveAsideCorrupt(SETTINGS_PATH)
                _settings.value
            }
            val updated = transform(base)
            fileRepository.saveFileLocally(SETTINGS_PATH, json.encodeToString(ObjectiveSettings.serializer(), updated) + "\n")
            _settings.value = updated
            pushScheduler.requestPush()
        }
        scheduleSlot(force = true)
    }

    /**
     * Keeps the weekly slot's nudge pinned to its start (like the daily reminders): enqueued when
     * absent, replaced on a settings change ([force]), cancelled when the slot is off.
     */
    suspend fun scheduleSlot(force: Boolean = false) {
        val settings = reloadSettings()
        val workManager = WorkManager.getInstance(context)
        if (!settings.slotEnabled) {
            workManager.cancelUniqueWork(SLOT_WORK)
            return
        }
        workManager.enqueueUniquePeriodicWork(
            SLOT_WORK,
            if (force) ExistingPeriodicWorkPolicy.UPDATE else ExistingPeriodicWorkPolicy.KEEP,
            slotRequest(settings)
        )
    }

    /** Called by the worker after it ran: next week, same time. */
    suspend fun repinSlot() {
        val settings = reloadSettings()
        if (settings.slotEnabled) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(SLOT_WORK, ExistingPeriodicWorkPolicy.UPDATE, slotRequest(settings))
        }
    }

    private fun slotRequest(settings: ObjectiveSettings): PeriodicWorkRequest =
        PeriodicWorkRequestBuilder<ObjectiveSlotWorker>(7, TimeUnit.DAYS)
            .setNextScheduleTimeOverride(
                ObjectiveFormat.nextSlot(settings, LocalDateTime.now()).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            )
            .build()

    private suspend fun readSettings(): ObjectiveSettings? {
        val raw = fileRepository.readFile(SETTINGS_PATH) ?: return null
        return runCatching { json.decodeFromString(ObjectiveSettings.serializer(), raw) }.getOrNull()
    }

    companion object {
        const val SETTINGS_PATH = "10_Journal/system/objectives.json"
        private const val SLOT_WORK = "ObjectiveSlot"
    }
}
