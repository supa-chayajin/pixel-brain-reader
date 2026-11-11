package cloud.wafflecommons.pixelbrainreader.data.review

import android.content.Context
import cloud.wafflecommons.pixelbrainreader.data.modes.ModesRepository
import cloud.wafflecommons.pixelbrainreader.data.notifications.NotificationHelper
import cloud.wafflecommons.pixelbrainreader.data.repository.UserPreferencesRepository
import cloud.wafflecommons.pixelbrainreader.data.work.EvidenceScope
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import java.time.YearMonth
import javax.inject.Inject
import javax.inject.Singleton

/** When a written review is announced (12.13, owner 2026-11-11: opt-in, quiet), pure. */
object ReviewNotifications {

    /**
     * The monthly reports are announced when a personal report was written, or a plain work report
     * in Work mode (a work notification). Without work folders there is no split: the one report
     * counts as personal. An encrypted log's report opens through the private vault only.
     */
    fun announceMonthly(reports: List<WrittenReport>, workMode: Boolean, hasWorkFolders: Boolean): Boolean =
        reports.any { report ->
            when {
                report.scope == EvidenceScope.PERSONAL -> true
                report.path.endsWith(".enc") -> false
                !hasWorkFolders -> true
                else -> workMode
            }
        }
}

/** Posts the opt-in notification once the nightly job wrote a review (Settings → Reminders, off by default). */
@Singleton
class ReviewNotifier @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val userPrefs: UserPreferencesRepository,
    private val modesRepository: ModesRepository
) {

    suspend fun weeklyWritten(path: String) {
        if (!userPrefs.reviewNotifications.first()) return
        val week = ReviewWeek.fromPath(path) ?: return
        NotificationHelper.postWeeklyReview(context, week.week)
    }

    suspend fun monthlyWritten(month: YearMonth, reports: List<WrittenReport>) {
        if (reports.isEmpty() || !userPrefs.reviewNotifications.first()) return
        val visibility = modesRepository.currentVisibility()
        if (ReviewNotifications.announceMonthly(reports, !visibility.muteWorkNotifications, visibility.workFolders.isNotEmpty())) {
            NotificationHelper.postMonthlyReports(context, month)
        }
    }
}
