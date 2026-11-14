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
    const val LINKS = "$FOLDER/links.json"
    const val CONFIG = "$FOLDER/config.json"

    fun sessions(month: String): String = "$FOLDER/sessions/$month.json"
    fun trophies(set: String): String = "$FOLDER/trophies/${set.filter { it.isLetterOrDigit() || it == '_' || it == '-' }}.json"
}

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

/** PSN title → page path. [hidden] titles stay out of the "not in your library" fold. */
@Serializable
data class GameLinks(
    val links: Map<String, String> = emptyMap(),
    val hidden: List<String> = emptyList()
) {
    fun pageOf(titleId: String): String? = links[titleId]
    fun titlesOf(page: String): List<String> = links.filterValues { it == page }.keys.sorted()
    fun link(titleId: String, page: String): GameLinks = copy(links = links + (titleId to page), hidden = hidden - titleId)
    fun unlink(titleId: String): GameLinks = copy(links = links - titleId)
    fun hide(titleId: String): GameLinks = copy(links = links - titleId, hidden = (hidden + titleId).distinct())
}

/** One month of sessions. */
@Serializable
data class GameSessionsMonth(val sessions: List<GameSessionRecord> = emptyList())

/**
 * A session: [SOURCE_PSN] from the play time PSN measured between two syncs (it carries the
 * title, and its page is found through the links when shown), or [SOURCE_MANUAL] from a start and
 * a stop on the phone (it carries the page).
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
    companion object {
        const val SOURCE_PSN = "psn"
        const val SOURCE_MANUAL = "manual"
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

/** The owner's Games settings, seeded in the vault by the home session; the app only reads them. */
@Serializable
data class GamesConfig(
    /** The project whose `Handoff.md` "Ask Claude" writes to (RFC-009 §4.4). */
    @SerialName("ask_project") val askProject: String? = null
)
