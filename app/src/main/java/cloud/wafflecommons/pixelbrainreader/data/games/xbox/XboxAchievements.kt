package cloud.wafflecommons.pixelbrainreader.data.games.xbox

import cloud.wafflecommons.pixelbrainreader.data.games.AchievementRecord
import cloud.wafflecommons.pixelbrainreader.data.games.AchievementSetFile
import cloud.wafflecommons.pixelbrainreader.data.games.XboxTitleState
import java.time.Instant

/**
 * A title's achievements file (pure, 13.3): what Xbox gave this sync merged with the file on disk.
 * Unearned names and descriptions stay in the file only (D12: the page lists the earned ones). An
 * earned date, once written, is kept; an achievement Xbox reports earned without a real date (a 360
 * unlock made offline) is dated by the first sync that saw it earned.
 */
object XboxAchievements {

    /** The title's file: earned dates kept from [previous]; an undated unlock dated [now] the first time. */
    fun merge(
        titleId: String,
        title: String,
        sourceVersion: Int,
        fetched: List<FetchedAchievement>,
        previous: AchievementSetFile?,
        now: Instant
    ): AchievementSetFile {
        val before = previous?.achievements?.associateBy { it.id }.orEmpty()
        val records = fetched.distinctBy { it.id }
            .sortedWith(compareBy({ it.id.toIntOrNull() ?: Int.MAX_VALUE }, { it.id }))
            .map { a ->
                AchievementRecord(
                    id = a.id,
                    name = a.name,
                    description = a.description,
                    gamerscore = a.gamerscore,
                    secret = a.secret,
                    earnedAt = if (!a.earned) null else before[a.id]?.earnedAt ?: a.unlockedAt?.toString() ?: now.toString()
                )
            }
        return AchievementSetFile(titleId = titleId, title = title, sourceVersion = sourceVersion, achievements = records)
    }

    /** True when the title's achievements need reading: it has some, and its file is missing or counts other earned achievements or gamerscore than titlehub. */
    fun moved(title: XboxTitleState, file: AchievementSetFile?): Boolean {
        if (title.achievementSource <= 0) return false
        if (file == null) return true
        val earned = title.currentAchievements
        val score = title.currentGamerscore
        return (earned != null && file.earnedCount != earned) || (score != null && file.earnedGamerscore != score)
    }
}
