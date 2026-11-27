package cloud.wafflecommons.pixelbrainreader.data.games

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The files Games keeps in the vault (RFC-009 §6), all under [GameFiles.FOLDER] and written by the
 * app only. Each change starts from the file on disk (settled decision 8).
 */
object GameFiles {
    const val FOLDER = "10_Journal/data/games"
    const val SNAPSHOT = "$FOLDER/psn.json"

    /** The last Xbox sync (13.3), written whole by each one. */
    const val XBOX_SNAPSHOT = "$FOLDER/xbox.json"
    const val LINKS = "$FOLDER/links.json"
    const val CONFIG = "$FOLDER/config.json"

    fun sessions(month: String): String = "$FOLDER/sessions/$month.json"
    fun trophies(set: String): String = "$FOLDER/trophies/${set.filter { it.isLetterOrDigit() || it == '_' || it == '-' }}.json"

    /** One Xbox title's achievements (13.3), in the achievements folder; unearned names stay there. */
    fun achievements(titleId: String): String =
        "$FOLDER/achievements/${titleId.filter { it.isLetterOrDigit() || it == '_' || it == '-' }}.json"
}

/** The consoles a sync reads (13.3). [source] is the sessions' persisted `source` value. */
enum class GameConsole(val source: String, val label: String) {
    PSN(GameSessionRecord.SOURCE_PSN, "PlayStation"),
    XBOX(GameSessionRecord.SOURCE_XBOX, "Xbox")
}

/** One console's links (13.3, Xbox): title id → page path, and the titles the owner hid from the fold. */
@Serializable
data class ConsoleLinks(val links: Map<String, String> = emptyMap(), val hidden: List<String> = emptyList())

/** The last PSN sync: one entry per played title. */
@Serializable
data class PsnSnapshot(
    val syncedAt: String = "",
    val titles: List<PsnTitleState> = emptyList()
)

@Serializable
data class PsnTitleState(
    val titleId: String,
    val name: String,
    val category: String = "",
    val playSeconds: Long = 0,
    val playCount: Int = 0,
    val firstPlayed: String? = null,
    val lastPlayed: String? = null,
    val imageUrl: String? = null,
    /** Every title id of the same game (its PS4 and PS5 versions), from PSN's concept. */
    val conceptTitleIds: List<String> = emptyList(),
    val trophySet: String? = null,
    val trophyService: String? = null,
    val trophySetVersion: String? = null,
    val trophiesUpdated: String? = null,
    val progress: Int? = null,
    val earned: Int? = null,
    val defined: Int? = null,
    /** Its trophy set was asked for: a title without trophies isn't asked again at every sync. */
    val trophyLookedUp: Boolean = false
)

/**
 * PSN title → page path. [hidden] titles stay out of the "not in your library" fold. The Xbox titles
 * (13.3) sit apart in [xbox], so PSN's keys read and write exactly as 12.17 wrote them; the file
 * leaves [xbox] out while it is empty.
 */
@Serializable
data class GameLinks(
    val links: Map<String, String> = emptyMap(),
    val hidden: List<String> = emptyList(),
    /** 13.3: the Xbox titles, apart from PSN's keys. */
    val xbox: ConsoleLinks = ConsoleLinks()
) {
    fun pageOf(titleId: String): String? = links[titleId]
    fun titlesOf(page: String): List<String> = links.filterValues { it == page }.keys.sorted()
    fun link(titleId: String, page: String): GameLinks = copy(links = links + (titleId to page), hidden = hidden - titleId)
    fun unlink(titleId: String): GameLinks = copy(links = links - titleId)
    fun hide(titleId: String): GameLinks = copy(links = links - titleId, hidden = (hidden + titleId).distinct())

    /** The page [console]'s title [titleId] is linked to; [GameConsole.PSN] is [pageOf]. */
    fun consolePage(console: GameConsole, titleId: String): String? = when (console) {
        GameConsole.PSN -> pageOf(titleId)
        GameConsole.XBOX -> xbox.links[titleId]
    }

    /** [console]'s titles linked to [page], sorted. */
    fun consoleTitles(console: GameConsole, page: String): List<String> = when (console) {
        GameConsole.PSN -> titlesOf(page)
        GameConsole.XBOX -> xbox.links.filterValues { it == page }.keys.sorted()
    }

    fun isHidden(console: GameConsole, titleId: String): Boolean = when (console) {
        GameConsole.PSN -> titleId in hidden
        GameConsole.XBOX -> titleId in xbox.hidden
    }

    /** [titleId] of [console] linked to [page]; a hidden title comes back to the library. */
    fun linkConsole(console: GameConsole, titleId: String, page: String): GameLinks = when (console) {
        GameConsole.PSN -> link(titleId, page)
        GameConsole.XBOX -> copy(xbox = xbox.copy(links = xbox.links + (titleId to page), hidden = xbox.hidden - titleId))
    }

    /** [titleId] of [console] hidden from the fold; its link goes too. */
    fun hideConsole(console: GameConsole, titleId: String): GameLinks = when (console) {
        GameConsole.PSN -> hide(titleId)
        GameConsole.XBOX -> copy(xbox = xbox.copy(links = xbox.links - titleId, hidden = (xbox.hidden + titleId).distinct()))
    }

    /** A session's page: its own (manual), else its console title's link (by its `source`). */
    fun pageFor(record: GameSessionRecord): String? =
        record.page ?: record.titleId?.let { id ->
            if (record.source == GameSessionRecord.SOURCE_XBOX) xbox.links[id] else links[id]
        }
}

/** One month of sessions. */
@Serializable
data class GameSessionsMonth(val sessions: List<GameSessionRecord> = emptyList())

/**
 * A session: [SOURCE_PSN] from the play time PSN measured between two syncs, [SOURCE_XBOX] (13.3)
 * from the minutes Xbox measured between two syncs (both carry the console's title, and the page is
 * found through that console's links when shown), or [SOURCE_MANUAL] from a start and a stop on the
 * phone (it carries the page). The `source` values are persisted: never rename one.
 */
@Serializable
data class GameSessionRecord(
    val id: String,
    /** The day it started, `yyyy-MM-dd` (D11). */
    val date: String,
    /** `HH:mm`, local time. */
    val start: String? = null,
    val minutes: Int,
    val source: String,
    val titleId: String? = null,
    val page: String? = null
) {
    /** Measured by a console (XB5): PSN or Xbox. */
    val measured: Boolean get() = source == SOURCE_PSN || source == SOURCE_XBOX

    companion object {
        const val SOURCE_PSN = "psn"
        const val SOURCE_MANUAL = "manual"
        const val SOURCE_XBOX = "xbox"
    }
}

/** A trophy set, as PSN lists it, with the owner's earned dates. Unearned names stay in this file. */
@Serializable
data class TrophySetFile(
    val set: String,
    val service: String,
    val version: String? = null,
    val title: String = "",
    val trophies: List<TrophyRecord> = emptyList()
) {
    val earnedCount: Int get() = trophies.count { it.earnedAt != null }
}

@Serializable
data class TrophyRecord(
    val id: Int,
    val name: String = "",
    val detail: String = "",
    /** `bronze`, `silver`, `gold` or `platinum`. */
    val grade: String = "",
    val hidden: Boolean = false,
    val group: String = "default",
    /** ISO instant, or null while not earned. */
    val earnedAt: String? = null
)

/**
 * The last Xbox sync (13.3): one entry per title of the title history, written whole by each sync.
 * [complete] is false when the history came back full (older titles may be missing). Never the
 * account's ids or tokens.
 */
@Serializable
data class XboxSnapshot(
    val syncedAt: String = "",
    val complete: Boolean = true,
    val titles: List<XboxTitleState> = emptyList()
)

/**
 * One Xbox title as the last sync saw it. [minutesPlayed]: the cumulative `MinutesPlayed` stat,
 * null when unknown (never 0 for unknown). [lastPlayed] is an `Instant` string. [achievementSource]:
 * 2 for Xbox One and Series, 1 for Xbox 360, 0 without achievements. [visible] is false when the
 * owner hid the game on their Xbox profile.
 */
@Serializable
data class XboxTitleState(
    val titleId: String,
    val name: String,
    val mediaItemType: String = "",
    val achievementSource: Int = 0,
    val modernTitleId: String? = null,
    val minutesPlayed: Long? = null,
    val lastPlayed: String? = null,
    val imageUrl: String? = null,
    val currentAchievements: Int? = null,
    val currentGamerscore: Int? = null,
    val totalAchievements: Int? = null,
    val totalGamerscore: Int? = null,
    val progress: Int? = null,
    val visible: Boolean = true
)

/** One Xbox title's achievements, with the owner's earned dates (13.3). Unearned names stay in this file. */
@Serializable
data class AchievementSetFile(
    val titleId: String,
    val title: String = "",
    val sourceVersion: Int = 2,
    val achievements: List<AchievementRecord> = emptyList()
) {
    val earnedCount: Int get() = achievements.count { it.earnedAt != null }
    val totalGamerscore: Int get() = achievements.sumOf { it.gamerscore }
    val earnedGamerscore: Int get() = achievements.filter { it.earnedAt != null }.sumOf { it.gamerscore }
}

@Serializable
data class AchievementRecord(
    /** Microsoft's id, as a string. */
    val id: String,
    val name: String = "",
    val description: String = "",
    val gamerscore: Int = 0,
    val secret: Boolean = false,
    /** ISO instant, or null while not earned. */
    val earnedAt: String? = null
)

/** The owner's Games settings, seeded in the vault by the home session; the app only reads them. */
@Serializable
data class GamesConfig(
    /** The project whose `Handoff.md` "Ask Claude" writes to (RFC-009 §4.4). */
    @SerialName("ask_project") val askProject: String? = null
)
