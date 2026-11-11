package cloud.wafflecommons.pixelbrainreader.ui.review

import androidx.lifecycle.ViewModel
import cloud.wafflecommons.pixelbrainreader.data.modes.ModesRepository
import cloud.wafflecommons.pixelbrainreader.data.repository.FileRepository
import cloud.wafflecommons.pixelbrainreader.data.review.MonthlyReports
import cloud.wafflecommons.pixelbrainreader.data.review.ReviewWeek
import cloud.wafflecommons.pixelbrainreader.data.work.MissionLogRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDateTime
import javax.inject.Inject

/**
 * Where a review notification leads (12.13): the app finds the note itself, so the notification
 * carries a fixed screen key and never a path. The weekly review, or the month's report the mode
 * shows (the work one in Work mode, else the personal one); null when there is none to open.
 */
@HiltViewModel
class ReviewNoteViewModel @Inject constructor(
    private val fileRepository: FileRepository,
    private val modesRepository: ModesRepository,
    private val missionLogRepository: MissionLogRepository
) : ViewModel() {

    suspend fun resolve(kind: String?, now: LocalDateTime = LocalDateTime.now()): String? = when (kind) {
        WEEKLY -> ReviewWeek.lastDue(now).path.takeIf { fileRepository.fileExists(it) }
        MONTHLY -> {
            val month = MonthlyReports.lastDue(now)
            val visibility = modesRepository.currentVisibility()
            val work = MonthlyReports.workPath(month, visibility.workFolders).takeIf { path ->
                // An encrypted log's report opens through the private vault, never from here.
                !visibility.hideWork && !missionLogRepository.reloadSettings().encrypted && fileRepository.fileExists(path)
            }
            work ?: MonthlyReports.personalPath(month).takeIf { fileRepository.fileExists(it) }
        }
        else -> null
    }

    companion object {
        const val WEEKLY = "weekly"
        const val MONTHLY = "monthly"
    }
}
