package cloud.wafflecommons.pixelbrainreader.ui.daily

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cloud.wafflecommons.pixelbrainreader.data.games.GameArt
import cloud.wafflecommons.pixelbrainreader.data.games.GameArtRules
import cloud.wafflecommons.pixelbrainreader.data.games.GameDayLine
import cloud.wafflecommons.pixelbrainreader.data.games.GameTimeline
import cloud.wafflecommons.pixelbrainreader.data.games.GamesRepository
import cloud.wafflecommons.pixelbrainreader.data.games.pictures.GamePictureSource
import cloud.wafflecommons.pixelbrainreader.data.local.entity.TimelineEntryEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate
import javax.inject.Inject

/**
 * One game line on Daily's timeline (13.4, P1): the row shown among the board's entries, and the
 * game's picture drawn beside it on screen. The burned note keeps its text line (settled decision #7).
 */
data class DailyGameRow(val entry: TimelineEntryEntity, val art: GameArt)

/**
 * [day]'s game lines as Daily's rows (pure): one per game, at its start, with its length; each with
 * its page's picture from [arts] (by page path), the initials when it has none.
 */
internal fun dailyGameRows(day: LocalDate, lines: List<GameDayLine>, arts: Map<String, GameArt>): List<DailyGameRow> =
    lines.map { line ->
        DailyGameRow(
            entry = TimelineEntryEntity(
                id = GAME_ROW_PREFIX + line.page.path,
                date = day,
                time = line.start,
                content = "🎮 ${line.page.title} · ${GameTimeline.duration(line.minutes)}"
            ),
            art = arts[line.page.path] ?: GameArtRules.initialsOnly(line.page.title)
        )
    }

/**
 * The rows the page of [date] shows (pure, 13.4, the audit's daily-game-rows-other-day): [date]'s
 * own only, so the day before's line never shows on another page while the new day's are read.
 */
internal fun gameRowsOn(rows: List<DailyGameRow>, date: LocalDate): List<DailyGameRow> = rows.filter { it.entry.date == date }

private const val GAME_ROW_PREFIX = "game:"

/**
 * The day's game lines on Daily's timeline (12.17, RFC-009 §4.3): rows shown among the board's
 * entries, never stored in the board (the burn writes them into the note from the games data). A tap
 * opens the game's page. Kept apart so DailyNoteViewModel stays as it is. 13.4: each row carries its
 * game's picture, and a new day's read replaces the one still running (swiping through days queues
 * no reads).
 */
@HiltViewModel
class DailyGamesViewModel @Inject constructor(
    private val games: GamesRepository,
    private val pictures: GamePictureSource
) : ViewModel() {

    private val date = MutableStateFlow<LocalDate?>(null)

    @OptIn(ExperimentalCoroutinesApi::class)
    val rows: StateFlow<List<DailyGameRow>> = combine(date.filterNotNull(), games.changes) { day, _ -> day }
        .mapLatest { day ->
            try {
                val lines = games.dayLines(day)
                if (lines.isEmpty()) {
                    emptyList()
                } else {
                    dailyGameRows(day, lines, pictures.artFor(lines.map { it.page }.distinctBy { it.path }))
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
        fun isGameRow(entry: TimelineEntryEntity): Boolean = entry.id.startsWith(GAME_ROW_PREFIX)

        fun pageOf(entry: TimelineEntryEntity): String = entry.id.removePrefix(GAME_ROW_PREFIX)
    }
}
