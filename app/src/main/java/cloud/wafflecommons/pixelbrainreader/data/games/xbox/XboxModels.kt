package cloud.wafflecommons.pixelbrainreader.data.games.xbox

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Instant

// --- What Microsoft and the Xbox network answer (13.3, XB-C), read-only ------------------------
// Field names follow python-xbox's recorded answers; unknown fields are ignored and every field
// has a default, so one odd entry never fails a whole answer. Ids, and numbers that may come
// quoted, are read as strings: the client's lenient JSON reads an unquoted number into a String.

/** The device-code step's answer (login.microsoftonline.com, the consumers tenant). */
@Serializable
data class MsDeviceCodeAnswer(
    @SerialName("device_code") val deviceCode: String? = null,
    @SerialName("user_code") val userCode: String? = null,
    @SerialName("verification_uri") val verificationUri: String? = null,
    @SerialName("expires_in") val expiresIn: Long? = null,
    val interval: Long? = null,
    val error: String? = null,
    @SerialName("error_description") val errorDescription: String? = null,
    @SerialName("error_codes") val errorCodes: List<Long> = emptyList()
)

/** The token endpoint's answer: a device-code poll or a refresh. */
@Serializable
data class MsTokenAnswer(
    @SerialName("access_token") val accessToken: String? = null,
    @SerialName("refresh_token") val refreshToken: String? = null,
    @SerialName("expires_in") val expiresIn: Long? = null,
    val error: String? = null,
    @SerialName("error_description") val errorDescription: String? = null,
    @SerialName("error_codes") val errorCodes: List<Long> = emptyList()
)

/** The Xbox user token's and the XSTS token's answer. */
@Serializable
data class XblTokenAnswer(
    @SerialName("IssueInstant") val issueInstant: String? = null,
    @SerialName("NotAfter") val notAfter: String? = null,
    @SerialName("Token") val token: String? = null,
    @SerialName("DisplayClaims") val displayClaims: XblDisplayClaims? = null
)

@Serializable
data class XblDisplayClaims(val xui: List<XblUserClaims> = emptyList())

/** [uhs]: the user hash of the XBL3.0 header; [xid]: the xuid (XSTS only). Never stored. */
@Serializable
data class XblUserClaims(val uhs: String? = null, val xid: String? = null)

/** What XSTS says when it refuses an account (HTTP 401). */
@Serializable
data class XstsError(
    @SerialName("XErr") val xErr: Long? = null,
    @SerialName("Message") val message: String? = null
)

/** titlehub's title history (contract 2): one answer, no paging. */
@Serializable
data class TitleHistoryAnswer(val titles: List<XboxTitle> = emptyList())

/**
 * One title of the history. [type] tells games from apps; [mediaItemType] is `Application` for
 * Xbox One and Series games, `Xbox360Game` for back-compat ones, listed under their 360 id with a
 * [modernTitleId]. [serviceConfigId] comes with the `scid` decoration.
 */
@Serializable
data class XboxTitle(
    val titleId: String = "",
    val name: String = "",
    val type: String = "",
    val mediaItemType: String = "",
    val modernTitleId: String? = null,
    val displayImage: String? = null,
    val serviceConfigId: String? = null,
    val achievement: XboxTitleAchievement? = null,
    val titleHistory: XboxTitleHistory? = null
)

/** titlehub's achievement summary. Its totals read 0 for Xbox One titles: the achievements call has them. */
@Serializable
data class XboxTitleAchievement(
    val currentAchievements: Int = 0,
    val totalAchievements: Int = 0,
    val currentGamerscore: Int = 0,
    val totalGamerscore: Int = 0,
    val progressPercentage: Double = 0.0,
    /** 2 for Xbox One and Series, 1 for Xbox 360, 0 without achievements. */
    val sourceVersion: Int = 0
)

/** [visible] is false when the owner hid the game on their profile (it was still played). */
@Serializable
data class XboxTitleHistory(val lastTimePlayed: String? = null, val visible: Boolean = true)

/** userstats' batch answer; a title without the stat is simply missing. */
@Serializable
data class StatsAnswer(val statlistscollection: List<StatList> = emptyList())

@Serializable
data class StatList(val stats: List<StatEntry> = emptyList())

@Serializable
data class StatEntry(
    val titleid: String? = null,
    val scid: String? = null,
    val name: String = "",
    val value: String? = null
)

/** An Xbox One or Series title's achievements (contract 2), one page. */
@Serializable
data class AchievementsAnswer(
    val achievements: List<XboxAchievement> = emptyList(),
    val pagingInfo: PagingInfo? = null
)

/** A modern achievement: [progressState] `Achieved`, `InProgress` or `NotStarted`. */
@Serializable
data class XboxAchievement(
    val id: String = "",
    val name: String = "",
    val description: String = "",
    val progressState: String = "",
    val progression: Progression? = null,
    val isSecret: Boolean = false,
    val isRevoked: Boolean = false,
    val rewards: List<Reward> = emptyList()
)

/** [timeUnlocked] reads 0001-01-01 while the achievement is locked. */
@Serializable
data class Progression(val timeUnlocked: String? = null)

@Serializable
data class Reward(val type: String = "", val value: String? = null)

@Serializable
data class PagingInfo(val continuationToken: String? = null, val totalRecords: Int? = null)

/** An Xbox 360 title's achievements (contract 1): the full list, or the earned ones only. */
@Serializable
data class Achievements360Answer(
    val achievements: List<Xbox360Achievement> = emptyList(),
    val pagingInfo: PagingInfo? = null
)

/**
 * A 360 achievement. The full list reads [unlocked] false and a placeholder [timeUnlocked] even for
 * earned ones; the earned list dates an offline unlock 1753-01-01.
 */
@Serializable
data class Xbox360Achievement(
    val id: String = "",
    val name: String = "",
    val description: String = "",
    val unlocked: Boolean = false,
    val isSecret: Boolean = false,
    val isRevoked: Boolean = false,
    val gamerscore: Int = 0,
    val timeUnlocked: String? = null
)

// --- The domain side ---------------------------------------------------------------------------

/** A device-code sign-in in progress. [deviceCode] never leaves memory; toString() hides it. */
data class XboxDeviceCode(val userCode: String, val deviceCode: String, val expiresAt: Long, val intervalSeconds: Long) {
    override fun toString(): String = "XboxDeviceCode(expiresAt=$expiresAt, intervalSeconds=$intervalSeconds)"
}

/** Microsoft's tokens, in encrypted storage only (SecretManager); epoch ms. toString() hides them. */
data class XboxTokens(val access: String, val accessExpiresAt: Long, val refresh: String, val refreshIssuedAt: Long) {
    override fun toString(): String = "XboxTokens(accessExpiresAt=$accessExpiresAt, refreshIssuedAt=$refreshIssuedAt)"
}

/** What a data call needs, kept in memory only: the XBL3.0 header's parts and the xuid (never stored, never logged). */
data class XboxSession(val userHash: String, val xsts: String, val xuid: String, val expiresAt: Long) {
    val authorization: String get() = "XBL3.0 x=$userHash;$xsts"

    override fun toString(): String = "XboxSession(expiresAt=$expiresAt)"
}

/** One device-code poll's outcome. */
sealed interface DevicePoll {
    /** The owner hasn't signed in yet: keep polling. */
    data object Pending : DevicePoll

    /** Microsoft asks to poll less often. */
    data object SlowDown : DevicePoll

    data class Done(val tokens: XboxTokens) : DevicePoll

    /** The owner declined at microsoft.com/link. */
    data object Declined : DevicePoll

    /** The code expired before the owner signed in. */
    data object Expired : DevicePoll
}

/** The games of title history, and whether the answer was full (1,000: older titles may be missing). */
data class XboxTitleList(val titles: List<XboxTitle>, val full: Boolean)

/** One achievement as Xbox gave it this sync, before the merge with its file. */
data class FetchedAchievement(
    val id: String,
    val name: String,
    val description: String,
    val gamerscore: Int,
    val secret: Boolean,
    val earned: Boolean,
    val unlockedAt: Instant?
)

/** Why an Xbox call failed, in words the settings page shows. Kind names are persisted (status): never rename one. */
class XboxException(val kind: Kind, message: String) : Exception(message) {
    enum class Kind {
        /** No Xbox app in this build (`local.properties`), nothing connected, or a read-only device. */
        NOT_CONFIGURED,

        /** Microsoft refused the app this build carries (its registration needs a change). */
        CLIENT_REJECTED,

        /** The sign-in has ended (the refresh token expired or was refused): Connect again. */
        SIGN_IN_EXPIRED,

        /** The account can't use the Xbox network here (no profile, a child account, a ban…). */
        ACCOUNT_REFUSED,

        /** A token was refused: renewed once, then the sign-in has ended. */
        TOKEN_EXPIRED,
        RATE_LIMITED,
        NETWORK,
        API,

        /**
         * 13.4: links.json can't be read. The sync kept the play (sessions, achievement files,
         * snapshot) and linked, hid and created nothing; the owner fixes the file on the desktop.
         */
        LINKS_UNREADABLE
    }
}
