package cloud.wafflecommons.pixelbrainreader.data.review

import cloud.wafflecommons.pixelbrainreader.data.work.EvidenceReport
import cloud.wafflecommons.pixelbrainreader.data.work.EvidenceScope
import cloud.wafflecommons.pixelbrainreader.data.work.ReportPeriod
import java.time.LocalDateTime
import java.time.YearMonth

/**
 * The monthly reports (D7), written once after a month's last nightly burn (owner, 2026-11-11):
 * the work report in the first work folder (hidden outside Work mode), the personal one in the
 * inbox; without work folders there is no split and the one report goes to the inbox. When the
 * mission log is encrypted, the work report is written encrypted beside it instead (pure).
 */
object MonthlyReports {

    const val FOLDER = "Rapports"

    /** The most recent month whose reports are due at [now]: the current one only from its last night. */
    fun lastDue(now: LocalDateTime): YearMonth {
        val month = YearMonth.from(now)
        return if (now.isBefore(month.atEndOfMonth().atTime(ReviewWeek.DUE_AFTER))) month.minusMonths(1) else month
    }

    /** "Rapport d'activité 2026-10.md", the Evidence screen's own name for the report. */
    fun fileName(month: YearMonth, scope: EvidenceScope): String =
        EvidenceReport.path(ReportPeriod.Month(month), scope).substringAfterLast('/')

    /** Where a plain work report goes: the first work folder's `Rapports/`, else the inbox. */
    fun workPath(month: YearMonth, workFolders: List<String>): String {
        val folder = workFolders.firstOrNull()?.trim('/')?.takeIf { it.isNotEmpty() }
            ?: return EvidenceReport.path(ReportPeriod.Month(month), EvidenceScope.WORK)
        return "$folder/$FOLDER/" + fileName(month, EvidenceScope.WORK)
    }

    /** The personal report, written only when work folders split the vault. */
    fun personalPath(month: YearMonth): String = EvidenceReport.path(ReportPeriod.Month(month), EvidenceScope.PERSONAL)
}
