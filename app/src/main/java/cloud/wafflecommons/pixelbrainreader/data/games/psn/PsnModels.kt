package cloud.wafflecommons.pixelbrainreader.data.games.psn

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * What the PlayStation Network answers (RFC-009 §7), read-only. Field names follow the API as the
 * psn-api and psnawp libraries document it; unknown fields are ignored.
 */
@Serializable
data class PsnTokenAnswer(
    @SerialName("access_token") val accessToken: String? = null,
    @SerialName("expires_in") val expiresIn: Long? = null,
    @SerialName("refresh_token") val refreshToken: String? = null,
    @SerialName("refresh_token_expires_in") val refreshTokenExpiresIn: Long? = null,
    val error: String? = null,
    @SerialName("error_code") val errorCode: Int? = null,
    @SerialName("error_description") val errorDescription: String? = null
)

@Serializable
data class PsnPlayedPage(
    val titles: List<PsnPlayedTitle> = emptyList(),
    val totalItemCount: Int = 0,
    val nextOffset: Int? = null
)

@Serializable
data class PsnPlayedTitle(
    val titleId: String,
    val name: String = "",
    val localizedName: String? = null,
    val imageUrl: String? = null,
    val category: String = "",
    val playCount: Int = 0,
    val concept: PsnConcept? = null,
    val firstPlayedDateTime: String? = null,
    val lastPlayedDateTime: String? = null,
    val playDuration: String? = null
)

@Serializable
data class PsnConcept(val id: Long? = null, val titleIds: List<String> = emptyList(), val name: String? = null)

@Serializable
data class PsnTrophyTitlesPage(
    val trophyTitles: List<PsnTrophyTitle> = emptyList(),
    val totalItemCount: Int = 0,
    val nextOffset: Int? = null
)

@Serializable
data class PsnTrophyTitle(
    val npServiceName: String = "trophy",
    val npCommunicationId: String,
    val trophySetVersion: String? = null,
    val trophyTitleName: String = "",
    val trophyTitlePlatform: String = "",
    val definedTrophies: PsnTrophyCounts = PsnTrophyCounts(),
    val earnedTrophies: PsnTrophyCounts = PsnTrophyCounts(),
    val progress: Int = 0,
    val lastUpdatedDateTime: String? = null
)

@Serializable
data class PsnTrophyCounts(val bronze: Int = 0, val silver: Int = 0, val gold: Int = 0, val platinum: Int = 0) {
    val total: Int get() = bronze + silver + gold + platinum
}

@Serializable
data class PsnTitleSets(val titles: List<PsnTitleSet> = emptyList())

@Serializable
data class PsnTitleSet(val npTitleId: String, val trophyTitles: List<PsnTrophyTitle> = emptyList())

@Serializable
data class PsnTrophyList(
    val trophySetVersion: String? = null,
    val trophies: List<PsnTrophy> = emptyList()
)

@Serializable
data class PsnTrophy(
    val trophyId: Int,
    val trophyHidden: Boolean = false,
    val trophyType: String = "",
    val trophyName: String? = null,
    val trophyDetail: String? = null,
    val trophyGroupId: String = "default",
    val earned: Boolean = false,
    val earnedDateTime: String? = null
)

/** The tokens a sign-in gives; the times are epoch milliseconds. Kept in encrypted storage only. */
data class PsnTokens(val access: String, val accessExpiresAt: Long, val refresh: String, val refreshExpiresAt: Long)

/** Why a PSN call failed, in words the settings page shows. */
class PsnException(val kind: Kind, message: String) : Exception(message) {
    enum class Kind {
        /** The NPSSO was refused (expired or mistyped, 4165). */
        NPSSO_REJECTED,
        /**
         * PlayStation refused the client this build carries (4102, invalid_client). A wrong secret in
         * `local.properties` does exactly this, at the token step (a wrong id fails earlier, at the
         * authorize step): 12.17's had one character too many, and it read as a Sony block until
         * 13.2.3.
         */
        CLIENT_REJECTED,
        /** The refresh token is no longer valid: paste a new NPSSO. */
        TOKEN_EXPIRED,
        RATE_LIMITED,
        NETWORK,
        API,
        /** No PSN client in this build (`local.properties`), or nothing connected. */
        NOT_CONFIGURED,

        /**
         * 13.4: links.json can't be read. The sync kept the play (sessions, trophy files, snapshot)
         * and linked, hid and created nothing; the owner fixes the file on the desktop.
         */
        LINKS_UNREADABLE
    }
}
