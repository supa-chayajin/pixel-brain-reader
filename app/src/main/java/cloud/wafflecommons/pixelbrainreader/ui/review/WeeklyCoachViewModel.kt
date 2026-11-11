package cloud.wafflecommons.pixelbrainreader.ui.review

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cloud.wafflecommons.pixelbrainreader.data.ai.ChatSeed
import cloud.wafflecommons.pixelbrainreader.data.ai.ChatSeedRepository
import cloud.wafflecommons.pixelbrainreader.data.device.VaultWriteGate
import cloud.wafflecommons.pixelbrainreader.data.review.ReviewWeek
import cloud.wafflecommons.pixelbrainreader.data.review.WeeklyCoach
import cloud.wafflecommons.pixelbrainreader.data.review.WeeklyReviewRepository
import cloud.wafflecommons.pixelbrainreader.data.work.CoachSeeds
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * "Ask Coach about this week" on an open weekly review (D6): the week's measured facts, gathered
 * again, go to the Coach preset as its first message; [askCoach]'s callback then opens Chat.
 */
@HiltViewModel
class WeeklyCoachViewModel @Inject constructor(
    private val reviewRepository: WeeklyReviewRepository,
    private val chatSeeds: ChatSeedRepository
) : ViewModel() {

    /** True for a weekly review the Coach can be asked about; never on a read-only device (no Chat there). */
    fun canAsk(path: String?): Boolean = path != null && !VaultWriteGate.readOnly && ReviewWeek.isReview(path)

    fun askCoach(path: String, then: () -> Unit) {
        if (!canAsk(path)) return
        val week = ReviewWeek.fromPath(path) ?: return
        viewModelScope.launch {
            val brief = WeeklyCoach.brief(week, reviewRepository.facts(week))
            chatSeeds.offer(ChatSeed(CoachSeeds.COACH_PRESET, brief))
            then()
        }
    }
}
