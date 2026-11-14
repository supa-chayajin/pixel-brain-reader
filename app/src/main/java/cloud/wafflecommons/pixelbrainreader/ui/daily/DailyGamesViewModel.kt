package cloud.wafflecommons.pixelbrainreader.ui.daily

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cloud.wafflecommons.pixelbrainreader.data.games.GameTimeline
import cloud.wafflecommons.pixelbrainreader.data.games.GamesRepository
import cloud.wafflecommons.pixelbrainreader.data.local.entity.TimelineEntryEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate
import javax.inject.Inject

/**
 * The day's game lines on Daily's timeline (12.17, RFC-009 §4.3): rows shown among the board's
 * entries, never stored in the board (the burn writes them into the note from the games data). A tap
 * opens the game's page. Kept apart so DailyNoteViewModel stays as it is.
 */
@HiltViewModel
class DailyGamesViewModel @Inject constructor(private val games: GamesRepository) : ViewModel() {

    private val date = MutableStateFlow<LocalDate?>(null)

    val rows: StateFlow<List<TimelineEntryEntity>> = combine(date.filterNotNull(), games.changes) { day, _ -> day }
        .map { day ->
            try {
                games.dayLines(day).map { line ->
                    TimelineEntryEntity(
                        id = ROW_PREFIX + line.page.path,
                        date = day,
                        time = line.start,
                        content = "🎮 ${line.page.title} · ${GameTimeline.duration(line.minutes)}"
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emptyList()
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** The page Daily shows; its game lines follow. */
    fun show(day: LocalDate) {
        date.value = day
    }

    companion object {
        private const val ROW_PREFIX = "game:"

        fun isGameRow(entry: TimelineEntryEntity): Boolean = entry.id.startsWith(ROW_PREFIX)

        fun pageOf(entry: TimelineEntryEntity): String = entry.id.removePrefix(ROW_PREFIX)
    }
}
