package cloud.wafflecommons.pixelbrainreader.data.work

import cloud.wafflecommons.pixelbrainreader.data.ai.LocalAiManager
import cloud.wafflecommons.pixelbrainreader.data.local.dao.FileContentDao
import cloud.wafflecommons.pixelbrainreader.data.modes.ModesRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/** The evidence report's data (#2): the period's mission log and the work completed in the work folders. */
@Singleton
class EvidenceRepository @Inject constructor(
    private val missionLogRepository: MissionLogRepository,
    private val fileContentDao: FileContentDao,
    private val modesRepository: ModesRepository,
    private val localAiManager: LocalAiManager,
    private val draftWriter: DraftWriter
) {

    suspend fun figures(period: ReportPeriod): EvidenceFigures = withContext(Dispatchers.IO) {
        val entries = missionLogRepository.entriesBetween(period.start, period.end)
        val completed = modesRepository.config.value.workFolders
            .flatMap { folder -> fileContentDao.contentsUnder("$folder/") }
            .map { WorkNote.from(it.path, it.content) }
            .filter { note -> note.column == StatusColumn.DONE && note.updated?.let { it in period.start..period.end } == true }
            .sortedBy { it.updated }
        EvidenceFigures(entries, completed)
    }

    /** Writes the period's report to the inbox; returns its path. */
    suspend fun write(period: ReportPeriod, withSummary: Boolean): String = withContext(Dispatchers.IO) {
        val figures = figures(period)
        val summary = if (withSummary && figures.entries.isNotEmpty()) {
            val prompt = LeaveDocuments.summaryPrompt(
                "Voici le journal de mission d'une période (${period.frenchLabel}). Résume les contributions marquantes et leur effet.",
                EvidenceReport.facts(figures)
            )
            localAiManager.generateResponse(prompt).getOrNull()?.takeIf { it.isNotBlank() }
        } else {
            null
        }
        draftWriter.write(EvidenceReport.path(period), EvidenceReport.render(period, figures, LocalDate.now(), summary))
    }
}
