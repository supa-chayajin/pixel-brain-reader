package cloud.wafflecommons.pixelbrainreader.data.work

import cloud.wafflecommons.pixelbrainreader.data.ai.LocalAiManager
import cloud.wafflecommons.pixelbrainreader.data.local.dao.FileContentDao
import cloud.wafflecommons.pixelbrainreader.data.modes.LeavePeriod
import cloud.wafflecommons.pixelbrainreader.data.modes.ModesRepository
import cloud.wafflecommons.pixelbrainreader.data.remote.GitFileAges
import cloud.wafflecommons.pixelbrainreader.data.repository.FileRepository
import cloud.wafflecommons.pixelbrainreader.data.sync.VaultPushScheduler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/** A leave that just ended, with what changed meanwhile in the work folders. */
data class LeaveReturn(val start: LocalDate, val end: LocalDate, val changed: Int, val created: Int)

/**
 * Leave mode & handover (#3): the handover draft before leaving, the briefing after. Both read
 * the vault (Room's note mirror, the mission log, git history) and write a draft to the inbox.
 */
@Singleton
class LeaveRepository @Inject constructor(
    private val fileContentDao: FileContentDao,
    private val missionLogRepository: MissionLogRepository,
    private val modesRepository: ModesRepository,
    private val gitFileAges: GitFileAges,
    private val localAiManager: LocalAiManager,
    private val fileRepository: FileRepository,
    private val pushScheduler: VaultPushScheduler
) {

    /** Writes a handover draft for [scope] (a folder) and the leave [start]..[end]; returns its path. */
    suspend fun createHandover(scope: String, start: LocalDate, end: LocalDate, withSummary: Boolean): String = withContext(Dispatchers.IO) {
        val today = LocalDate.now()
        val notes = fileContentDao.contentsUnder(scope.trimEnd('/') + "/").map { WorkNote.from(it.path, it.content) }
        val log = logBetween(today.minusDays(LOOKBACK_DAYS), today)
        val inputs = HandoverInputs(
            scope = scope.trimEnd('/'),
            start = start,
            end = end,
            generated = today,
            inProgress = notes.filter { it.column == StatusColumn.WIP }.sortedByDescending { it.updated },
            paused = notes.filter { it.column == StatusColumn.PAUSED }.sortedByDescending { it.updated },
            decisions = log.filter { it.kind == LogKind.DECISION },
            openRequests = log.filter(LeaveDocuments::isOpenRequest),
            refusals = log.filter { it.kind == LogKind.REFUSAL },
            keyDates = modesRepository.config.value.keyDates.filter { it.localDate()?.let { d -> d in start..end } == true }.sortedBy { it.date }
        )
        val summary = if (withSummary) {
            summarize(LeaveDocuments.summaryPrompt("Voici les éléments d'une passation avant une absence. Résume ce que la personne qui reprend doit savoir.", LeaveDocuments.handoverFacts(inputs)))
        } else {
            null
        }
        write(LeaveDocuments.handoverPath(inputs.scope, start), LeaveDocuments.handover(inputs.copy(summary = summary)))
    }

    /** The leave that just ended, if any, with its counts — for the Daily card and the Work hub. */
    suspend fun recentReturn(today: LocalDate = LocalDate.now()): LeaveReturn? = withContext(Dispatchers.IO) {
        val leave = LeaveDocuments.recentReturn(modesRepository.config.value.leavePeriods, today) ?: return@withContext null
        val (changed, created) = changesDuring(leave)
        LeaveReturn(LocalDate.parse(leave.start), LocalDate.parse(leave.end), changed.size, created.size)
    }

    /** Writes the "what happened" briefing for the leave that just ended; returns its path, or null if there is none. */
    suspend fun createBriefing(withSummary: Boolean, today: LocalDate = LocalDate.now()): String? = withContext(Dispatchers.IO) {
        val leave = LeaveDocuments.recentReturn(modesRepository.config.value.leavePeriods, today) ?: return@withContext null
        val start = LocalDate.parse(leave.start)
        val end = LocalDate.parse(leave.end)
        val (changed, created) = changesDuring(leave)
        val inputs = BriefingInputs(
            start = start,
            end = end,
            generated = today,
            changed = changed,
            created = created,
            logEntries = logBetween(start, end),
            upcoming = modesRepository.config.value.keyDates
                .filter { it.localDate()?.let { d -> !d.isBefore(today) && !d.isAfter(today.plusDays(14)) } == true }
                .sortedBy { it.date }
        )
        val summary = if (withSummary) {
            summarize(LeaveDocuments.summaryPrompt("Voici ce qui a changé dans le coffre pendant une absence. Résume l'essentiel pour la reprise.", LeaveDocuments.briefingFacts(inputs)))
        } else {
            null
        }
        write(LeaveDocuments.briefingPath(start, end), LeaveDocuments.briefing(inputs.copy(summary = summary)))
    }

    /** Work notes changed (last change during the leave) and created (first seen during it), from git history. */
    private suspend fun changesDuring(leave: LeavePeriod): Pair<List<WorkNote>, List<WorkNote>> {
        val zone = ZoneId.systemDefault()
        val from = LocalDate.parse(leave.start).atStartOfDay(zone).toInstant().toEpochMilli()
        val until = LocalDate.parse(leave.end).plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val workFolders = modesRepository.config.value.workFolders
        fun relevant(path: String) = path.endsWith(".md") &&
            !path.startsWith("99_") && !path.startsWith("10_Journal/") &&
            (workFolders.isEmpty() || workFolders.any { path.startsWith("$it/") })
        val history = gitFileAges.history()
        val created = history.firstSeen.filter { (path, time) -> relevant(path) && time in from until until }.keys
        val changed = history.lastChanged.filter { (path, time) -> relevant(path) && time in from until until }.keys - created
        suspend fun notes(paths: Set<String>) = paths.sorted().mapNotNull { path ->
            fileContentDao.getContent(path)?.let { WorkNote.from(path, it) }
        }
        return notes(changed) to notes(created)
    }

    private suspend fun logBetween(from: LocalDate, to: LocalDate): List<LogEntry> = try {
        missionLogRepository.entriesBetween(from, to)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        emptyList() // an encrypted log without its password: the document says the log is empty
    }

    private suspend fun summarize(prompt: String): String? =
        localAiManager.generateResponse(prompt).getOrNull()?.takeIf { it.isNotBlank() }

    /** Writes [content] at [path], or next to it when a draft is already there (never over the user's edits). */
    private suspend fun write(path: String, content: String): String {
        var target = path
        var n = 2
        while (fileRepository.fileExists(target)) {
            target = "${path.removeSuffix(".md")} ($n).md"
            n++
        }
        fileRepository.saveFileLocally(target, content)
        pushScheduler.requestPush()
        return target
    }

    private companion object {
        /** Mission-log entries included in a handover: the last month. */
        const val LOOKBACK_DAYS = 30L
    }
}
