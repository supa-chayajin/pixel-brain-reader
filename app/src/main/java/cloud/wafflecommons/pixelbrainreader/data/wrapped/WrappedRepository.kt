package cloud.wafflecommons.pixelbrainreader.data.wrapped

import android.util.Log
import cloud.wafflecommons.pixelbrainreader.data.games.GamesRepository
import cloud.wafflecommons.pixelbrainreader.data.gamification.GamificationRepository
import cloud.wafflecommons.pixelbrainreader.data.health.HealthInsightsRepository
import cloud.wafflecommons.pixelbrainreader.data.local.dao.FileContentDao
import cloud.wafflecommons.pixelbrainreader.data.modes.ModesRepository
import cloud.wafflecommons.pixelbrainreader.data.remote.FileHistory
import cloud.wafflecommons.pixelbrainreader.data.remote.GitFileAges
import cloud.wafflecommons.pixelbrainreader.data.repository.HabitRepository
import cloud.wafflecommons.pixelbrainreader.data.repository.MoodRepository
import cloud.wafflecommons.pixelbrainreader.data.review.WeeklyActivity
import cloud.wafflecommons.pixelbrainreader.data.vault.FrontmatterLines
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads the year so far for Wrapped (13.0, X5): each fact on its own, so one that fails leaves only
 * its card out. Reads only; nothing is written to the vault or kept on the phone.
 */
@Singleton
class WrappedRepository @Inject constructor(
    private val gitFileAges: GitFileAges,
    private val fileContentDao: FileContentDao,
    private val moodRepository: MoodRepository,
    private val habitRepository: HabitRepository,
    private val healthInsightsRepository: HealthInsightsRepository,
    private val gamesRepository: GamesRepository,
    private val gamificationRepository: GamificationRepository,
    private val modesRepository: ModesRepository
) {

    suspend fun cards(today: LocalDate, zone: ZoneId = ZoneId.systemDefault()): List<Wrapped.Card> = withContext(Dispatchers.IO) {
        val from = today.withDayOfYear(1)
        val visibility = fact("modes") { modesRepository.currentVisibility() }
        val custody = fact("custody") { modesRepository.currentConfig().custody }
        val history = fact("history") { gitFileAges.history() } ?: FileHistory()
        val counted = Wrapped.countedNotes(history, from, today, zone)
        val tags = fact("tags") {
            counted.associateWith { path -> fileContentDao.getContent(path)?.let { FrontmatterLines.getList(it, "tags") }.orEmpty() }
        }.orEmpty()
        val moods = fact("mood") {
            moodRepository.getMoodsBetween(from, today).first()
                .groupBy { it.date }
                .mapNotNull { (date, entries) -> runCatching { LocalDate.parse(date) }.getOrNull()?.let { it to entries.map { e -> e.score.toDouble() }.average() } }
                .toMap()
        }.orEmpty()
        val habits = fact("habits") { habitRepository.getHabitConfigs() }.orEmpty()
        val habitLogs = fact("habit logs") { habitRepository.getLogsBetweenFlow(from, today).first() }.orEmpty()
        // Yesterday's day and the night that ended this morning: today's steps aren't in yet.
        val health = if (today == from) emptyList() else fact("health") { healthInsightsRepository.days(from, today.minusDays(1)) }.orEmpty()
        val games = fact("games") {
            val links = gamesRepository.links()
            // A console session's page through that console's links (13.3: PSN's or Xbox's).
            gamesRepository.sessionsBetween(from, today).map { session ->
                if (session.page != null) session else session.copy(page = links.pageFor(session))
            }
        }.orEmpty()
        val profile = fact("xp") { gamificationRepository.gamificationState.first().profile }
        Wrapped.cards(
            Wrapped.Facts(
                from = from,
                to = today,
                history = history,
                zone = zone,
                hiddenWork = { path -> visibility?.let { it.hideWork && it.isWorkPath(path) } ?: false },
                tags = tags,
                moodByDay = moods,
                habits = habits,
                habitLogs = habitLogs,
                custody = custody,
                healthDays = health,
                gameSessions = games,
                level = profile?.level ?: 0,
                totalXp = profile?.let { WeeklyActivity.cumulativeXp(it.level, it.currentXp) } ?: 0,
                className = profile?.characterClass?.name?.lowercase()?.replaceFirstChar { it.uppercase() }.orEmpty()
            )
        )
    }

    private suspend fun <T> fact(name: String, read: suspend () -> T): T? = try {
        read()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "Wrapped: $name unread (${e.javaClass.simpleName})")
        null
    }

    private companion object {
        const val TAG = "WrappedRepository"
    }
}
