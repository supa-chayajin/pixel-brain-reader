package cloud.wafflecommons.pixelbrainreader.data.games.psn

import cloud.wafflecommons.pixelbrainreader.data.privacy.NetPurpose
import cloud.wafflecommons.pixelbrainreader.data.privacy.PrivacyLedger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Base64
import java.util.UUID
import java.util.concurrent.TimeUnit

/** One HTTP exchange, as the client needs it: the status, a redirect's target, the body. */
data class PsnHttpResponse(val code: Int, val location: String?, val body: String)

/** The HTTP side of [PsnClient], faked in the tests. Redirects are never followed. */
interface PsnTransport {
    suspend fun get(url: String, headers: Map<String, String>): PsnHttpResponse
    suspend fun postForm(url: String, headers: Map<String, String>, form: Map<String, String>): PsnHttpResponse
}

/**
 * A client of its own, without the app's logging interceptor (a debug build logs bodies there, and
 * these carry tokens), that never follows a redirect: the sign-in's code arrives in one.
 */
class OkHttpPsnTransport : PsnTransport {

    private val client = OkHttpClient.Builder()
        // The privacy ledger (13.0, X3): hosts and bytes only, never a token.
        .eventListenerFactory(PrivacyLedger.events(NetPurpose.GAMES))
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        .build()

    override suspend fun get(url: String, headers: Map<String, String>): PsnHttpResponse =
        execute(Request.Builder().url(url).apply { headers.forEach { (k, v) -> header(k, v) } }.get().build())

    override suspend fun postForm(url: String, headers: Map<String, String>, form: Map<String, String>): PsnHttpResponse {
        val body = FormBody.Builder().apply { form.forEach { (k, v) -> add(k, v) } }.build()
        return execute(Request.Builder().url(url).apply { headers.forEach { (k, v) -> header(k, v) } }.post(body).build())
    }

    private suspend fun execute(request: Request): PsnHttpResponse = withContext(Dispatchers.IO) {
        client.newCall(request).execute().use { response ->
            PsnHttpResponse(response.code, response.header("Location"), response.body?.string().orEmpty())
        }
    }
}

/**
 * The PlayStation Network, read-only (RFC-009 §7): the sign-in the PlayStation App uses (its public
 * client, from `local.properties`, D10), then the owner's played games and trophies. The request
 * shapes follow psnawp 3.1.0, the library Home Assistant's integration runs on.
 */
class PsnClient(
    private val transport: PsnTransport,
    private val clientId: String,
    private val clientSecret: String
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val cid = UUID.randomUUID().toString()

    /** False when this build has no PSN client: Games then works by hand. */
    val configured: Boolean get() = clientId.isNotBlank() && clientSecret.isNotBlank()

    private val basic: String
        get() = "Basic " + Base64.getEncoder().encodeToString("$clientId:$clientSecret".toByteArray(Charsets.UTF_8))

    /** Exchanges the NPSSO for tokens at once; the NPSSO itself is never kept. */
    suspend fun signIn(npsso: String, now: Long): PsnTokens {
        requireConfigured()
        val code = authorize(npsso)
        return token(
            mapOf(
                "cid" to cid, "code" to code, "grant_type" to "authorization_code",
                "redirect_uri" to REDIRECT, "scope" to SCOPE, "token_format" to "jwt"
            ),
            now, previousRefresh = null
        )
    }

    /** A new access token from the refresh token; Sony may hand a new refresh token too. */
    suspend fun refresh(tokens: PsnTokens, now: Long): PsnTokens {
        requireConfigured()
        return token(
            mapOf("refresh_token" to tokens.refresh, "grant_type" to "refresh_token", "scope" to SCOPE, "token_format" to "jwt"),
            now, previousRefresh = tokens
        )
    }

    /** Every PS4 and PS5 title played, most recent first. */
    suspend fun playedGames(access: String): List<PsnPlayedTitle> {
        val all = mutableListOf<PsnPlayedTitle>()
        var offset = 0
        repeat(MAX_PAGES) {
            val page = api(
                "$API/gamelist/v2/users/me/titles?categories=ps4_game,ps5_native_game&limit=$PAGE&offset=$offset",
                access, PsnPlayedPage.serializer()
            )
            all += page.titles
            val next = page.nextOffset
            if (page.titles.isEmpty() || next == null || next <= offset || all.size >= page.totalItemCount) return all
            offset = next
        }
        return all
    }

    suspend fun trophyTitles(access: String): List<PsnTrophyTitle> {
        val all = mutableListOf<PsnTrophyTitle>()
        var offset = 0
        repeat(MAX_PAGES) {
            val page = api("$API/trophy/v1/users/me/trophyTitles?limit=800&offset=$offset", access, PsnTrophyTitlesPage.serializer())
            all += page.trophyTitles
            val next = page.nextOffset
            if (page.trophyTitles.isEmpty() || next == null || next <= offset || all.size >= page.totalItemCount) return all
            offset = next
        }
        return all
    }

    /** Each title id's trophy sets, five ids a call (the API's limit). */
    suspend fun setsForTitles(access: String, titleIds: List<String>): Map<String, List<PsnTrophyTitle>> =
        titleIds.distinct().chunked(5).flatMap { batch ->
            val ids = URLEncoder.encode(batch.joinToString(","), "UTF-8")
            api("$API/trophy/v1/users/me/titles/trophyTitles?npTitleIds=$ids", access, PsnTitleSets.serializer()).titles
        }.associate { it.npTitleId to it.trophyTitles }

    /** A set's trophies (names, grades, hidden flags), in English. */
    suspend fun trophies(access: String, set: String, service: String): PsnTrophyList =
        api("$API/trophy/v1/npCommunicationIds/${path(set)}/trophyGroups/all/trophies?npServiceName=${path(service)}", access, PsnTrophyList.serializer())

    /** The owner's earned flags and dates for a set. */
    suspend fun earned(access: String, set: String, service: String): PsnTrophyList =
        api("$API/trophy/v1/users/me/npCommunicationIds/${path(set)}/trophyGroups/all/trophies?npServiceName=${path(service)}", access, PsnTrophyList.serializer())

    // --- Plumbing -----------------------------------------------------------------------

    private fun requireConfigured() {
        if (!configured) throw PsnException(PsnException.Kind.NOT_CONFIGURED, "No PlayStation client in this build.")
    }

    private suspend fun authorize(npsso: String): String {
        val query = linkedMapOf(
            "access_type" to "offline", "cid" to cid, "client_id" to clientId, "device_base_font_size" to "10",
            "device_profile" to "mobile", "elements_visibility" to "no_aclink", "enable_scheme_error_code" to "true",
            "no_captcha" to "true", "PlatformPrivacyWs1" to "minimal", "redirect_uri" to REDIRECT,
            "response_type" to "code", "scope" to SCOPE, "service_entity" to "urn:service-entity:psn",
            "service_logo" to "ps", "smcid" to "psapp:signin", "support_scheme" to "sneiprls",
            "turnOnTrustedBrowser" to "true", "ui" to "pr"
        ).entries.joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, "UTF-8")}" }
        val response = call {
            transport.get(
                "$AUTH/authorize?$query",
                mapOf(
                    "Cookie" to "npsso=$npsso", "X-Requested-With" to "com.scee.psxandroid",
                    "Sec-Fetch-Dest" to "document", "Sec-Fetch-Mode" to "navigate", "Sec-Fetch-Site" to "same-site", "Sec-Fetch-User" to "?1"
                )
            )
        }
        val params = queryOf(response.location)
        params["code"]?.takeIf { it.isNotBlank() }?.let { return it }
        val error = params["error_code"] ?: params["error"]
        throw if (error == "4165") {
            PsnException(PsnException.Kind.NPSSO_REJECTED, "PlayStation refused this NPSSO: it has expired or was mistyped.")
        } else {
            PsnException(PsnException.Kind.API, "PlayStation sign-in failed (HTTP ${response.code}${error?.let { ", error $it" }.orEmpty()}).")
        }
    }

    private suspend fun token(form: Map<String, String>, now: Long, previousRefresh: PsnTokens?): PsnTokens {
        val response = call {
            transport.postForm(
                "$AUTH/token",
                mapOf("Authorization" to basic, "User-Agent" to SONY_UA, "X-Psn-Correlation-Id" to cid),
                form
            )
        }
        val answer = runCatching { json.decodeFromString(PsnTokenAnswer.serializer(), response.body) }.getOrDefault(PsnTokenAnswer())
        val access = answer.accessToken
        if (response.code == 200 && !access.isNullOrBlank()) {
            val refresh = answer.refreshToken?.takeIf { it.isNotBlank() } ?: previousRefresh?.refresh
                ?: throw PsnException(PsnException.Kind.API, "PlayStation gave no refresh token.")
            val refreshExpiresAt = answer.refreshTokenExpiresIn?.let { now + it * 1000 } ?: previousRefresh?.refreshExpiresAt ?: now
            return PsnTokens(access, now + (answer.expiresIn ?: 3600) * 1000, refresh, refreshExpiresAt)
        }
        throw when {
            answer.errorCode == 4102 || answer.error == "invalid_client" ->
                PsnException(
                    PsnException.Kind.CLIENT_REJECTED,
                    "PlayStation refused this build's client id or secret (4102): check psnClientId and psnClientSecret in local.properties, then build again."
                )
            answer.error == "invalid_grant" || (previousRefresh != null && response.code in 400..401) ->
                PsnException(PsnException.Kind.TOKEN_EXPIRED, "The PlayStation sign-in has expired: paste a new NPSSO.")
            response.code == 429 -> PsnException(PsnException.Kind.RATE_LIMITED, "PlayStation asks to wait (too many requests).")
            else -> PsnException(PsnException.Kind.API, "PlayStation sign-in failed (HTTP ${response.code}${answer.errorCode?.let { ", error $it" }.orEmpty()}).")
        }
    }

    private suspend fun <T> api(url: String, access: String, serializer: KSerializer<T>): T {
        val response = call { transport.get(url, mapOf("Authorization" to "Bearer $access", "Accept-Language" to "en-US")) }
        when (response.code) {
            200 -> return try {
                json.decodeFromString(serializer, response.body)
            } catch (e: Exception) {
                throw PsnException(PsnException.Kind.API, "PlayStation answered in an unexpected shape.")
            }
            401 -> throw PsnException(PsnException.Kind.TOKEN_EXPIRED, "The PlayStation access token was refused.")
            429 -> throw PsnException(PsnException.Kind.RATE_LIMITED, "PlayStation asks to wait (too many requests).")
            else -> throw PsnException(PsnException.Kind.API, "PlayStation answered HTTP ${response.code}.")
        }
    }

    private suspend fun call(block: suspend () -> PsnHttpResponse): PsnHttpResponse = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: PsnException) {
        throw e
    } catch (e: IOException) {
        throw PsnException(PsnException.Kind.NETWORK, "No connection to PlayStation.")
    }

    private fun path(segment: String): String = URLEncoder.encode(segment, "UTF-8")

    companion object {
        private const val AUTH = "https://ca.account.sony.com/api/authz/v3/oauth"
        private const val API = "https://m.np.playstation.com/api"
        private const val SCOPE = "psn:mobile.v2.core psn:clientapp"
        private const val REDIRECT = "com.scee.psxandroid.scecompcall://redirect"
        private const val SONY_UA = "com.sony.snei.np.android.sso.share.oauth.versa.USER_AGENT"
        private const val PAGE = 200
        private const val MAX_PAGES = 10

        /**
         * The libraries describe 64 letters and digits; a token-safe set of 32 to 512 characters is
         * accepted, so a format change at Sony doesn't lock the owner out (Sony refuses a wrong one
         * anyway, 4165). A URL, an error page or a sentence still fails here.
         */
        private val NPSSO = Regex("^[A-Za-z0-9._~+/=-]{32,512}$")
        private val NPSSO_IN_JSON = Regex("\"npsso\"\\s*:\\s*\"([^\"]+)\"")

        /** The NPSSO from what the owner pasted: the value alone, or the page's whole answer. */
        fun cleanNpsso(input: String): String? {
            val trimmed = input.trim()
            val candidate = NPSSO_IN_JSON.find(trimmed)?.groupValues?.get(1)?.trim() ?: trimmed
            return candidate.takeIf { NPSSO.matches(it) }
        }

        /** What a refused paste looked like, never its content: its length, JSON or not, which characters. */
        fun describeRefused(input: String): String {
            val trimmed = input.trim()
            val json = trimmed.startsWith("{")
            val field = NPSSO_IN_JSON.containsMatchIn(trimmed)
            val odd = trimmed.filterNot { it.isLetterOrDigit() }.toSet().sorted()
                .joinToString(" ") { if (it == ' ') "space" else if (it == '\n') "a line break" else it.toString() }
            return buildString {
                append(trimmed.length).append(" characters")
                if (json) append(if (field) ", JSON with an npsso field" else ", JSON without an npsso field")
                if (odd.isNotEmpty()) append(", with ").append(odd)
            }
        }

        /** The query of a redirect target (`com.scee…://redirect/?code=v3…`), decoded. */
        internal fun queryOf(location: String?): Map<String, String> {
            val raw = location?.substringAfter('?', "")?.takeIf { it.isNotEmpty() } ?: return emptyMap()
            return raw.split('&').mapNotNull { pair ->
                val key = pair.substringBefore('=').takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                key to URLDecoder.decode(pair.substringAfter('=', ""), "UTF-8")
            }.toMap()
        }
    }
}
