package cloud.wafflecommons.pixelbrainreader.data.review

import android.util.Log
import cloud.wafflecommons.pixelbrainreader.data.device.VaultWriteGate
import cloud.wafflecommons.pixelbrainreader.data.modes.ModesRepository
import cloud.wafflecommons.pixelbrainreader.data.repository.FileRepository
import cloud.wafflecommons.pixelbrainreader.data.repository.UserPreferencesRepository
import cloud.wafflecommons.pixelbrainreader.data.work.DraftWriter
import cloud.wafflecommons.pixelbrainreader.data.work.EvidenceReport
import cloud.wafflecommons.pixelbrainreader.data.work.EvidenceRepository
import cloud.wafflecommons.pixelbrainreader.data.work.EvidenceScope
import cloud.wafflecommons.pixelbrainreader.data.work.MissionLogRepository
import cloud.wafflecommons.pixelbrainreader.data.work.ReportPeriod
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import javax.inject.Inject
import javax.inject.Singleton

/** A monthly report the nightly job wrote: where, and whether it is the work one. */
data class WrittenReport(val path: String, val scope: EvidenceScope)

/**
 * The monthly reports (D7), written once after a month's last nightly burn by `DailyExportWorker`:
 * the Evidence screen's reports without the AI summary (AICore refuses a background caller). A
 * report with nothing measured isn't written. Never on a read-only device (#18).
 */
@Singleton
class MonthlyReportsRepository @Inject constructor(
    private val evidenceRepository: EvidenceRepository,
    private val missionLogRepository: MissionLogRepository,
    private val modesRepository: ModesRepository,
    private val fileRepository: FileRepository,
    private val draftWriter: DraftWriter,
    private val userPrefs: UserPreferencesRepository
) {

    /** Writes the most recent due month's reports unless they were written before; what was written. */
    suspend fun writeDue(now: LocalDateTime = LocalDateTime.now()): List<WrittenReport> = withContext(Dispatchers.IO) {
        if (VaultWriteGate.readOnly) return@withContext emptyList()
        val month = MonthlyReports.lastDue(now)
        if (userPrefs.monthlyReportsWritten.first() == month.toString()) return@withContext emptyList()
        // A worker can start the process: read the modes and the log's settings from disk first.
        modesRepository.reload()
        val workFolders = modesRepository.config.value.workFolders
        val settings = missionLogRepository.reloadSettings()
        val period = ReportPeriod.Month(month)
        val today = now.toLocalDate()
        val written = mutableListOf<WrittenReport>()
        var failed = false

        // Each report on its own: an encrypted log without its stored password must not cost the
        // personal report. The month is remembered only once both went through.
        try {
            val work = evidenceRepository.figures(period, EvidenceScope.WORK)
            if (EvidenceReport.facts(work).isNotEmpty()) {
                if (settings.encrypted) {
                    val name = MonthlyReports.fileName(month, EvidenceScope.WORK).removeSuffix(".md")
                    missionLogRepository.writeEncryptedReport(name) { at -> EvidenceReport.render(period, work, today, at = at) }
                        ?.let { written += WrittenReport(it, EvidenceScope.WORK) }
                } else {
                    val path = MonthlyReports.workPath(month, workFolders)
                    if (!fileRepository.fileExists(path)) {
                        written += WrittenReport(draftWriter.write(path, EvidenceReport.render(period, work, today, at = path)), EvidenceScope.WORK)
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failed = true
            Log.w(TAG, "Work report for $month not written: ${e.javaClass.simpleName}")
        }
        if (workFolders.isNotEmpty()) {
            try {
                val personal = evidenceRepository.figures(period, EvidenceScope.PERSONAL)
                val path = MonthlyReports.personalPath(month)
                if (EvidenceReport.facts(personal).isNotEmpty() && !fileRepository.fileExists(path)) {
                    written += WrittenReport(draftWriter.write(path, EvidenceReport.render(period, personal, today, at = path)), EvidenceScope.PERSONAL)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed = true
                Log.w(TAG, "Personal report for $month not written: ${e.javaClass.simpleName}")
            }
        }
        if (!failed) userPrefs.setMonthlyReportsWritten(month.toString())
        Log.i(TAG, "Monthly reports for $month: ${written.size} written")
        written
    }

    private companion object {
        const val TAG = "MonthlyReports"
    }
}
