package cloud.wafflecommons.pixelbrainreader.data.work

import cloud.wafflecommons.pixelbrainreader.data.local.dao.FileContentDao
import cloud.wafflecommons.pixelbrainreader.data.modes.ModesRepository
import cloud.wafflecommons.pixelbrainreader.data.repository.FileRepository
import cloud.wafflecommons.pixelbrainreader.data.sync.VaultPushScheduler
import cloud.wafflecommons.pixelbrainreader.data.vault.FrontmatterLines
import cloud.wafflecommons.pixelbrainreader.data.vault.VaultSchema
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
import java.time.LocalTime
import javax.inject.Inject
import javax.inject.Singleton

/** Meetings & requests (#6): meeting notes, their decisions and actions, my next actions, the weekly load. */
@Singleton
class MeetingRepository @Inject constructor(
    private val fileContentDao: FileContentDao,
    private val missionLogRepository: MissionLogRepository,
    private val modesRepository: ModesRepository,
    private val fileRepository: FileRepository,
    private val pushScheduler: VaultPushScheduler
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }
    private val writeMutex = Mutex()

    private val _changes = MutableStateFlow(0L)
    val changes: StateFlow<Long> = _changes.asStateFlow()

    /** Where new meeting notes go: the setting, else a "Réunions" folder in the first work folder. */
    suspend fun folder(): String = withContext(Dispatchers.IO) {
        readSettings()?.folder?.takeIf { it.isNotBlank() } ?: MeetingFormat.defaultFolder(modesRepository.config.value.workFolders)
    }

    suspend fun setFolder(folder: String) = withContext(Dispatchers.IO) {
        writeMutex.withLock {
            fileRepository.saveFileLocally(SETTINGS_PATH, json.encodeToString(MeetingSettings.serializer(), MeetingSettings(folder.trim().trim('/'))) + "\n")
            pushScheduler.requestPush()
        }
        _changes.update { it + 1 }
    }

    /** Every meeting note in the vault (tagged `reunion` / `meeting`), most recent first. */
    suspend fun meetings(): List<Meeting> = withContext(Dispatchers.IO) {
        allNotes().filter(MeetingFormat::isMeeting).map(MeetingFormat::parse).sortedByDescending { it.date }
    }

    /** My open actions (`- [ ] @moi …`) across the vault, soonest due first. */
    suspend fun myActions(): List<ActionItem> = withContext(Dispatchers.IO) {
        fileContentDao.contentsUnder("")
            .filter { it.path.endsWith(".md") }
            .flatMap { MeetingFormat.actions(it.path, it.content) }
            .filter { it.mine && !it.done }
            .sortedWith(compareBy<ActionItem> { it.due ?: LocalDate.MAX }.thenBy { it.path })
    }

    suspend fun create(title: String, date: LocalDate, start: LocalTime?, durationMinutes: Int?, participants: String?): String = withContext(Dispatchers.IO) {
        val base = MeetingFormat.path(folder(), date, title)
        var path = base
        var n = 2
        while (fileRepository.fileExists(path)) {
            path = "${base.removeSuffix(".md")} ($n).md"
            n++
        }
        fileRepository.saveFileLocally(path, MeetingFormat.newMeeting(path, title, date, start, durationMinutes, participants, LocalDate.now()))
        pushScheduler.requestPush()
        _changes.update { it + 1 }
        path
    }

    /** Adds the meeting's decisions to the mission log (reference: the note), skipping those already there. */
    suspend fun logDecisions(meeting: Meeting): Int = withContext(Dispatchers.IO) {
        val reference = "[[${meeting.note.linkName}]]"
        val date = meeting.date ?: LocalDate.now()
        val known = missionLogRepository.entriesBetween(date.minusDays(1), LocalDate.now())
            .filter { it.reference == reference }
            .map { it.text }
            .toSet()
        val fresh = meeting.decisions.filter { it !in known }
        val time = if (date == LocalDate.now()) LocalDateTime.now().withSecond(0).withNano(0) else date.atTime(LocalTime.NOON)
        fresh.forEach { decision ->
            missionLogRepository.add(LogEntry(at = time, kind = LogKind.DECISION, text = decision, reference = reference, tags = listOf("reunion")))
        }
        fresh.size
    }

    /** Ticks [action] in its note (line read from disk) and touches `date_updated`. */
    suspend fun complete(action: ActionItem) = withContext(Dispatchers.IO) {
        writeMutex.withLock {
            val content = fileRepository.readFile(action.path) ?: throw IllegalStateException("The note is no longer there.")
            val toggled = ObjectiveFormat.toggle(content, action.asMilestone()) ?: throw IllegalStateException("The action changed meanwhile; reload.")
            val touched = if (FrontmatterLines.hasFrontmatter(toggled)) {
                FrontmatterLines.set(toggled, "date_updated", VaultSchema.singleQuoted(LocalDate.now().toString()))
            } else {
                toggled
            }
            fileRepository.saveFileLocally(action.path, touched)
            pushScheduler.requestPush()
        }
        _changes.update { it + 1 }
    }

    /** The last [weeks] weeks of meetings, requests and off-ticket time. */
    suspend fun weeklyLoad(weeks: Int = 8, today: LocalDate = LocalDate.now()): List<WeekLoad> = withContext(Dispatchers.IO) {
        val log = try {
            missionLogRepository.entriesBetween(today.minusWeeks(weeks.toLong()), today)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }
        MeetingFormat.weeklyLoad(meetings(), log, weeks, today)
    }

    private suspend fun allNotes(): List<WorkNote> =
        fileContentDao.contentsUnder("").filter { it.path.endsWith(".md") && !VaultSchema.isDailyNote(it.path) }.map { WorkNote.from(it.path, it.content) }

    private suspend fun readSettings(): MeetingSettings? {
        val raw = fileRepository.readFile(SETTINGS_PATH) ?: return null
        return runCatching { json.decodeFromString(MeetingSettings.serializer(), raw) }.getOrNull()
    }

    companion object {
        const val SETTINGS_PATH = "10_Journal/system/meetings.json"
    }
}
