package cloud.wafflecommons.pixelbrainreader.data.work

import cloud.wafflecommons.pixelbrainreader.data.ai.LocalAiManager
import cloud.wafflecommons.pixelbrainreader.data.local.dao.FileContentDao
import cloud.wafflecommons.pixelbrainreader.data.modes.ModesRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The evidence report's data (#2) for a period and a scope: the mission log (work only), the
 * notes moved to done, the meetings, the projects' worklogs and the objectives that moved.
 */
@Singleton
class EvidenceRepository @Inject constructor(
    private val missionLogRepository: MissionLogRepository,
    private val fileContentDao: FileContentDao,
    private val modesRepository: ModesRepository,
    private val meetingRepository: MeetingRepository,
    private val projectRepository: ProjectRepository,
    private val objectiveRepository: ObjectiveRepository,
    private val localAiManager: LocalAiManager,
    private val draftWriter: DraftWriter
) {

    suspend fun figures(period: ReportPeriod, scope: EvidenceScope = EvidenceScope.WORK): EvidenceFigures = withContext(Dispatchers.IO) {
        val workFolders = modesRepository.config.value.workFolders
        val inPeriod = period.start..period.end
        val entries = if (scope == EvidenceScope.WORK) missionLogRepository.entriesBetween(period.start, period.end) else emptyList()
        val completed = completedNotes(scope, workFolders)
            .filter { note -> note.column == StatusColumn.DONE && note.updated?.let { it in inPeriod } == true }
            .sortedBy { it.updated }
        val meetings = optional {
            meetingRepository.meetings().filter { m -> m.date?.let { it in inPeriod } == true && scope.includes(m.note.path, workFolders) }
        }
        val projects = optional {
            projectRepository.periodActivity(period.start, period.end).filter { scope.includes(it.root, workFolders) }
        }
        val objectives = optional {
            objectiveRepository.objectives(LocalDate.now())
                .filter { scope.includes(it.note.path, workFolders) }
                .mapNotNull { o ->
                    val material = o.material.count { it.at.toLocalDate() in inPeriod }
                    val updated = o.note.updated?.let { it in inPeriod } == true
                    if (material == 0 && !updated) null
                    else ObjectivePeriodProgress(o.note.title, o.note.linkName, o.done, o.milestones.size, material)
                }
        }
        EvidenceFigures(entries, completed, meetings, projects, objectives, scope)
    }

    /**
     * Where finished work is looked for: the work folders, or (personal) the projects outside them.
     * Desktop mirrors, worklogs and hand-offs are never work completed.
     */
    private suspend fun completedNotes(scope: EvidenceScope, workFolders: List<String>): List<WorkNote> = when (scope) {
        EvidenceScope.WORK -> workFolders.flatMap { folder -> fileContentDao.contentsUnder("$folder/") }
        EvidenceScope.PERSONAL -> fileContentDao.contentsUnder("${ProjectFormat.PROJECTS_FOLDER}/").filter { scope.includes(it.path, workFolders) }
    }
        .filter { it.path.endsWith(".md") && !ProjectFormat.isWorklog(it.path) && !DeskHandoffFormat.isHandoff(it.path) }
        .map { WorkNote.from(it.path, it.content) }
        .filter { !it.isMirror }

    /** A part of the report that can't be read is left out rather than failing the whole report. */
    private suspend fun <T> optional(block: suspend () -> List<T>): List<T> = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        emptyList()
    }

    /** Writes the period's report to the inbox; returns its path. */
    suspend fun write(period: ReportPeriod, withSummary: Boolean, scope: EvidenceScope = EvidenceScope.WORK): String = withContext(Dispatchers.IO) {
        val figures = figures(period, scope)
        val facts = EvidenceReport.facts(figures)
        val summary = if (withSummary && facts.isNotEmpty()) {
            val task = if (scope == EvidenceScope.WORK) {
                "Voici le journal de mission d'une période (${period.frenchLabel}), avec ses projets et ses objectifs. " +
                    "Résume les contributions marquantes et leur effet, en les regroupant par rôle tenu et par résultat concret."
            } else {
                "Voici l'activité personnelle d'une période (${period.frenchLabel}) : projets, objectifs et travail terminé. " +
                    "Résume les avancées marquantes et leur effet."
            }
            localAiManager.generateResponse(LeaveDocuments.summaryPrompt(task, facts)).getOrNull()?.takeIf { it.isNotBlank() }
        } else {
            null
        }
        draftWriter.write(EvidenceReport.path(period, scope), EvidenceReport.render(period, figures, LocalDate.now(), summary))
    }
}
