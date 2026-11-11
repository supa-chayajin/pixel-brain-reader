package cloud.wafflecommons.pixelbrainreader.data.meetings

import cloud.wafflecommons.pixelbrainreader.data.ai.VectorSearchEngine
import cloud.wafflecommons.pixelbrainreader.data.device.ReadOnlyDeviceException
import cloud.wafflecommons.pixelbrainreader.data.device.VaultWriteGate
import cloud.wafflecommons.pixelbrainreader.data.local.dao.DailyDashboardDao
import cloud.wafflecommons.pixelbrainreader.data.local.entity.TimelineEntryEntity
import cloud.wafflecommons.pixelbrainreader.data.modes.ModesRepository
import cloud.wafflecommons.pixelbrainreader.data.repository.DailyDashboardRepository
import cloud.wafflecommons.pixelbrainreader.data.repository.FileRepository
import cloud.wafflecommons.pixelbrainreader.data.repository.VaultDiscoveryRepository
import cloud.wafflecommons.pixelbrainreader.data.system.AppClock
import cloud.wafflecommons.pixelbrainreader.data.work.ErrorLookup
import cloud.wafflecommons.pixelbrainreader.data.work.LogEntry
import cloud.wafflecommons.pixelbrainreader.data.work.MeetingRepository
import cloud.wafflecommons.pixelbrainreader.data.work.MissionLogRepository
import cloud.wafflecommons.pixelbrainreader.data.work.ProjectFormat
import cloud.wafflecommons.pixelbrainreader.data.work.ProjectRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import javax.inject.Inject
import javax.inject.Singleton

/** A meeting's prep: what the vault already holds about it. */
data class MeetingPrepResult(
    val entry: TimelineEntryEntity,
    val topic: String,
    val notes: List<ErrorLookup.Occurrence>,
    val decisions: List<LogEntry>,
    /** Work is out of the picture: the mission log and work notes were left out. */
    val workHidden: Boolean,
    /** The owner's name for Work mode, for "hidden outside … mode". */
    val workLabel: String,
    val projectRoot: String?
)

/**
 * D4: a calendar entry's prep, from the vault only. The notes are found the way "Explain this
 * error" finds past occurrences (the words OR-ed and ranked, then the vectors above the 0.35
 * floor, fused by rank); the decisions are the mission log's, newest first. Mode rules apply: work
 * notes and the log stay hidden outside Work mode. Reads only, except the minutes.
 */
@Singleton
class MeetingPrepRepository @Inject constructor(
    private val dashboardDao: DailyDashboardDao,
    private val dashboardRepository: DailyDashboardRepository,
    private val vaultDiscoveryRepository: VaultDiscoveryRepository,
    private val vectorSearchEngine: VectorSearchEngine,
    private val missionLogRepository: MissionLogRepository,
    private val projectRepository: ProjectRepository,
    private val modesRepository: ModesRepository,
    private val fileRepository: FileRepository,
    private val meetingRepository: MeetingRepository,
    private val appClock: AppClock
) {

    /** Null when the entry is gone (a sync replaced today's calendar rows). */
    suspend fun prep(entryId: String): MeetingPrepResult? = withContext(Dispatchers.IO) {
        val entry = dashboardDao.getTimelineEntry(entryId) ?: return@withContext null
        val topic = MeetingPrep.topic(entry.content)
        val visibility = modesRepository.currentVisibility()
        val words = MeetingPrep.searchWords(topic)
        val notes = if (words.isEmpty()) emptyList() else {
            val text = vaultDiscoveryRepository.fullTextHits(words.joinToString(" "), limit = TEXT_LIMIT, anyTerm = true)
                .filter { visibility.isVisible(it.path) }
                .map { ErrorLookup.Hit(it.path, it.excerpt) }
            val vectors = try {
                vectorSearchEngine.search(topic, limit = VECTOR_LIMIT)
                    .filter { visibility.isVisible(it.fileId) }
                    .map { ErrorLookup.Hit(it.fileId, it.content.take(EXCERPT_CHARS)) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emptyList() // no embeddings yet, or a read-only device: the words alone
            }
            ErrorLookup.merge(listOf(text), vectors, max = MeetingPrep.MAX_NOTES)
        }
        val decisions = if (visibility.workContextHidden || words.isEmpty()) emptyList() else {
            try {
                val today = appClock.today.value
                MeetingPrep.decisions(missionLogRepository.entriesBetween(today.minusDays(MeetingPrep.DECISION_DAYS), today), words)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emptyList() // an encrypted log while the private vault is locked
            }
        }
        val roots = try {
            projectRepository.projects().map { it.root }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }
        MeetingPrepResult(
            entry = entry,
            topic = topic,
            notes = notes,
            decisions = decisions,
            workHidden = visibility.workContextHidden,
            workLabel = visibility.workLabel,
            projectRoot = notes.firstNotNullOfOrNull { ProjectFormat.projectFor(roots, it.path) }
        )
    }

    /**
     * The meeting's note, the toolkit's own (`MeetingRepository`: its folder setting and format, so
     * it counts in Meetings & requests and its `@moi` actions reach Next actions), with the prep
     * added; then one line on the meeting's day linking to it. Returns the note's path.
     */
    suspend fun writeMinutes(prep: MeetingPrepResult): String = withContext(Dispatchers.IO) {
        if (VaultWriteGate.refuses()) throw ReadOnlyDeviceException()
        val date = prep.entry.date
        val path = meetingRepository.create(prep.topic, date, prep.entry.time, durationMinutes = null, participants = null)
        val note = fileRepository.readFile(path)
        if (note != null) fileRepository.saveFileLocally(path, MeetingPrep.withPrep(note, prep.notes.map { it.path }, prep.decisions))
        val noteTitle = path.substringAfterLast('/').removeSuffix(".md")
        // On the meeting's own day, never before the meeting itself.
        val now = LocalDateTime.now()
        val at = now.toLocalTime().withSecond(0).withNano(0).let { if (date == now.toLocalDate() && it.isAfter(prep.entry.time)) it else prep.entry.time }
        dashboardRepository.addTimelineEntry(date, "📝 Compte rendu → [[$noteTitle]]", at)
        path
    }

    private companion object {
        const val TEXT_LIMIT = 20
        const val VECTOR_LIMIT = 8
        const val EXCERPT_CHARS = 240
    }
}
