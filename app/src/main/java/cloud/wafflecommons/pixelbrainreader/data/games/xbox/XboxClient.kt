package cloud.wafflecommons.pixelbrainreader.data.games.xbox

import android.util.Log
import cloud.wafflecommons.pixelbrainreader.data.privacy.NetPurpose
import cloud.wafflecommons.pixelbrainreader.data.privacy.PrivacyLedger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/** One HTTP exchange, as the client needs it. toString() leaves the body out: it can hold a token. */
data class XboxHttpResponse(val code: Int, val body: String, val retryAfterSeconds: Long? = null) {
    override fun toString(): String = "XboxHttpResponse(code=$code, body=${body.length} chars, retryAfterSeconds=$retryAfterSeconds)"
}

/** The HTTP side of [XboxClient], faked in the tests. Redirects are never followed. */
interface XboxTransport {
    suspend fun get(url: String, headers: Map<String, String>): XboxHttpResponse
    suspend fun postForm(url: String, headers: Map<String, String>, form: Map<String, String>): XboxHttpResponse
    suspend fun postJson(url: String, headers: Map<String, String>, json: String): XboxHttpResponse
}

/**
 * Its own OkHttp client: no logging interceptor (a debug build logs bodies there, and these carry
 * tokens), never a redirect, and the privacy ledger.
 */
class OkHttpXboxTransport : XboxTransport {

    private val client = OkHttpClient.Builder()
        // The privacy ledger (13.0, X3): hosts and bytes only, never a token, a path or the xuid in one.
        .eventListenerFactory(PrivacyLedger.events(NetPurpose.XBOX))
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        .build()

    override suspend fun get(url: String, headers: Map<String, String>): XboxHttpResponse =
        execute(request(url, headers).get().build())

    override suspend fun postForm(url: String, headers: Map<String, String>, form: Map<String, String>): XboxHttpResponse {
        val body = FormBody.Builder().apply { form.forEach { (k, v) -> add(k, v) } }.build()
        return execute(request(url, headers).post(body).build())
    }

    override suspend fun postJson(url: String, headers: Map<String, String>, json: String): XboxHttpResponse =
        execute(request(url, headers).post(json.toRequestBody(JSON_TYPE.toMediaType())).build())

    private fun request(url: String, headers: Map<String, String>): Request.Builder =
        Request.Builder().url(url).apply { headers.forEach { (k, v) -> header(k, v) } }

    private suspend fun execute(request: Request): XboxHttpResponse = withContext(Dispatchers.IO) {
        client.newCall(request).execute().use { response ->
            XboxHttpResponse(response.code, response.body?.string().orEmpty(), response.header("Retry-After")?.trim()?.toLongOrNull())
        }
    }

    private companion object {
        const val JSON_TYPE = "application/json; charset=utf-8"
    }
}

/**
 * The Xbox network, read-only (13.3, XB-C). The owner's own Microsoft app (XB1, a public client
 * from `local.properties`) signs in by device code on the consumers tenant; the Xbox user token
 * and the XSTS token, derived in memory at each sync, give the XBL3.0 header of every data call:
 * the title history, the minutes played and each title's achievements. The shapes follow
 * python-xbox's recorded answers.
 *
 * XboxLive.signin also allows writes (a console's power, its buttons), so this client enforces
 * read-only itself: GETs to titlehub and achievements only, and POSTs only to the two sign-in
 * forms and the three posts that read (the user token, XSTS, the stats batch).
 */
class XboxClient(private val transport: XboxTransport, private val clientId: String) {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    /** False when this build has no Xbox app: the Xbox row is hidden and Games works by hand. */
    val configured: Boolean get() = clientId.isNotBlank()

    // --- Sign-in ---------------------------------------------------------------------------

    /** Step 1 of Connect: the code the owner types at microsoft.com/link. */
    suspend fun startDeviceCode(now: Long): XboxDeviceCode {
        requireConfigured()
        val response = login(DEVICE_CODE, mapOf("client_id" to clientId, "scope" to SCOPE))
        val answer = decodeOrNull(MsDeviceCodeAnswer.serializer(), response.body) ?: MsDeviceCodeAnswer()
        val userCode = answer.userCode?.trim()
        val deviceCode = answer.deviceCode
        if (response.code == 200 && !userCode.isNullOrEmpty() && !deviceCode.isNullOrBlank()) {
            val lifetime = (answer.expiresIn ?: MAX_CODE_SECONDS).coerceAtMost(MAX_CODE_SECONDS)
            val interval = (answer.interval ?: MIN_INTERVAL_SECONDS).coerceAtLeast(MIN_INTERVAL_SECONDS)
            return XboxDeviceCode(userCode, deviceCode, now + lifetime * 1000, interval)
        }
        throw clientError(answer.error, answer.errorCodes, response.code)
    }

    /** One poll of the device code: Microsoft's tokens once the owner signed in. */
    suspend fun pollDeviceCode(code: XboxDeviceCode, now: Long): DevicePoll {
        requireConfigured()
        val response = login(TOKEN, mapOf("grant_type" to DEVICE_GRANT, "client_id" to clientId, "device_code" to code.deviceCode))
        val answer = decodeOrNull(MsTokenAnswer.serializer(), response.body) ?: MsTokenAnswer()
        val access = answer.accessToken
        if (response.code == 200 && !access.isNullOrBlank()) {
            val refresh = answer.refreshToken?.takeIf { it.isNotBlank() }
                ?: throw XboxException(XboxException.Kind.API, "Microsoft gave no refresh token.")
            return DevicePoll.Done(XboxTokens(access, now + (answer.expiresIn ?: DEFAULT_ACCESS_SECONDS) * 1000, refresh, refreshIssuedAt = now))
        }
        return when (answer.error) {
            "authorization_pending" -> DevicePoll.Pending
            "slow_down" -> DevicePoll.SlowDown
            "authorization_declined", "access_denied" -> DevicePoll.Declined
            "expired_token", "invalid_grant", "code_expired" -> DevicePoll.Expired
            "bad_verification_code" -> throw XboxException(XboxException.Kind.API, "Microsoft refused the sign-in code: Connect again.")
            "invalid_client", "unauthorized_client" -> throw clientError(answer.error, answer.errorCodes, response.code)
            else -> throw XboxException(XboxException.Kind.API, "The Microsoft sign-in failed (HTTP ${response.code}).")
        }
    }

    /** A new access token; Microsoft's refresh token replaces itself at each use: the new one is kept. */
    suspend fun refresh(tokens: XboxTokens, now: Long): XboxTokens {
        requireConfigured()
        val response = login(
            TOKEN,
            mapOf("grant_type" to "refresh_token", "client_id" to clientId, "refresh_token" to tokens.refresh, "scope" to SCOPE)
        )
        val answer = decodeOrNull(MsTokenAnswer.serializer(), response.body) ?: MsTokenAnswer()
        val access = answer.accessToken
        if (response.code == 200 && !access.isNullOrBlank()) {
            val rotated = answer.refreshToken?.takeIf { it.isNotBlank() }
            return XboxTokens(
                access = access,
                accessExpiresAt = now + (answer.expiresIn ?: DEFAULT_ACCESS_SECONDS) * 1000,
                refresh = rotated ?: tokens.refresh,
                refreshIssuedAt = if (rotated != null) now else tokens.refreshIssuedAt
            )
        }
        throw when {
            // An expired or revoked sign-in, or one Microsoft wants the owner to confirm again.
            answer.error == "invalid_grant" || answer.error == "interaction_required" ->
                XboxException(XboxException.Kind.SIGN_IN_EXPIRED, "The Xbox sign-in has expired: Connect again.")
            answer.error == "invalid_client" || answer.error == "unauthorized_client" -> clientError(answer.error, answer.errorCodes, response.code)
            response.code == 429 -> XboxException(XboxException.Kind.RATE_LIMITED, "Microsoft's sign-in asks to wait (too many requests).")
            else -> XboxException(XboxException.Kind.API, "The Microsoft sign-in failed (HTTP ${response.code}).")
        }
    }

    /** The user token, then XSTS: the session every data call needs. */
    suspend fun session(accessToken: String): XboxSession {
        val userBody = buildJsonObject {
            put("RelyingParty", "http://auth.xboxlive.com")
            put("TokenType", "JWT")
            putJsonObject("Properties") {
                put("AuthMethod", "RPS")
                put("SiteName", "user.auth.xboxlive.com")
                put("RpsTicket", "d=$accessToken")
            }
        }.toString()
        val userResponse = read(USER_AUTH, SIGN_IN_HEADERS, userBody)
        when (userResponse.code) {
            200 -> Unit
            400, 401 -> throw XboxException(XboxException.Kind.TOKEN_EXPIRED, "Microsoft's access token was refused.")
            429 -> throw rateLimited()
            else -> throw XboxException(XboxException.Kind.API, "The Xbox network answered HTTP ${userResponse.code}.")
        }
        val userToken = decodeOrNull(XblTokenAnswer.serializer(), userResponse.body)?.token?.takeIf { it.isNotBlank() }
            ?: throw unexpectedShape()

        val xstsBody = buildJsonObject {
            put("RelyingParty", "http://xboxlive.com")
            put("TokenType", "JWT")
            putJsonObject("Properties") {
                putJsonArray("UserTokens") { add(userToken) }
                put("SandboxId", "RETAIL")
            }
        }.toString()
        val xstsResponse = read(XSTS, SIGN_IN_HEADERS, xstsBody)
        if (xstsResponse.code != 200) {
            // XSTS says why it refused an account in its XErr.
            decodeOrNull(XstsError.serializer(), xstsResponse.body)?.xErr?.let { throw xErr(it) }
            throw when (xstsResponse.code) {
                401 -> XboxException(XboxException.Kind.TOKEN_EXPIRED, "The Xbox network refused the sign-in token.")
                429 -> rateLimited()
                else -> XboxException(XboxException.Kind.API, "The Xbox network answered HTTP ${xstsResponse.code}.")
            }
        }
        val xsts = decodeOrNull(XblTokenAnswer.serializer(), xstsResponse.body) ?: throw unexpectedShape()
        val token = xsts.token?.takeIf { it.isNotBlank() }
        val claims = xsts.displayClaims?.xui?.firstOrNull()
        val userHash = claims?.uhs?.takeIf { it.isNotBlank() }
        val xuid = claims?.xid?.takeIf { it.isNotBlank() }
        if (token == null || userHash == null || xuid == null) throw unexpectedShape()
        // An unreadable NotAfter gives 0: the session is derived again at the next call.
        return XboxSession(userHash, token, xuid, XboxTimes.instant(xsts.notAfter)?.toEpochMilli() ?: 0L)
    }

    // --- Data ------------------------------------------------------------------------------

    /** The played games, most recent first; [XboxTitleList.full] when the answer hit its limit. */
    suspend fun titleHistory(session: XboxSession): XboxTitleList {
        val url = "$TITLEHUB/users/xuid(${encode(session.xuid)})/titles/titlehistory/decoration/achievement,scid?maxItems=$TITLE_PAGE"
        val answer = decodeData(get(url, dataHeaders(session, contract = 2)), TitleHistoryAnswer.serializer())
        val games = answer.titles.filter { it.type.equals("Game", ignoreCase = true) && it.titleId.isNotBlank() }.distinctBy { it.titleId }
        return XboxTitleList(games, full = answer.titles.size >= TITLE_PAGE)
    }

    /**
     * MinutesPlayed per title id, ≤ 50 stats a call; a failed call drops only its titles. A title
     * asks its own id and its modern id (a 360 game's minutes may sit under the latter) in the same
     * call, so a failed call never makes it switch counters for a sync; the answer is read by title
     * id, else by service config id. A modern id's counter goes to a title only when the history
     * lists no title of that id, and to one title only (the smallest id when several name it): one
     * game's minutes are never counted twice.
     */
    suspend fun minutesPlayed(session: XboxSession, titles: List<XboxTitle>): Map<String, Long> {
        // Each title's ids together, every id asked once.
        val asked = HashSet<String>()
        val groups = titles.map { t ->
            listOfNotNull(t.titleId.takeIf { it.isNotBlank() }, t.modernTitleId?.takeIf { it.isNotBlank() && it != t.titleId })
                .filter { asked.add(it) }
        }.filter { it.isNotEmpty() }
        if (groups.isEmpty()) return emptyMap()
        val byScid = titles.mapNotNull { t -> t.serviceConfigId?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }?.let { it to t.titleId } }.toMap()
        val values = HashMap<String, Long>()
        for (chunk in batches(groups)) {
            val body = buildJsonObject {
                put("arrangebyfield", "xuid")
                putJsonArray("xuids") { add(session.xuid) }
                putJsonArray("stats") {
                    chunk.forEach { id ->
                        addJsonObject {
                            put("name", MINUTES_PLAYED)
                            put("titleId", id)
                        }
                    }
                }
            }.toString()
            val answer = try {
                decodeData(read(USERSTATS_BATCH, dataHeaders(session, contract = 2), body), StatsAnswer.serializer())
            } catch (e: XboxException) {
                when (e.kind) {
                    XboxException.Kind.TOKEN_EXPIRED -> throw e
                    // The next batches would meet the same wait or the same missing network: their titles keep their minutes.
                    XboxException.Kind.RATE_LIMITED, XboxException.Kind.NETWORK -> {
                        Log.i(TAG, "Xbox minutes: ${e.kind}, the remaining batches wait for the next sync")
                        break
                    }
                    else -> {
                        Log.i(TAG, "Xbox minutes skipped for ${chunk.size} titles: ${e.kind}")
                        continue
                    }
                }
            }
            answer.statlistscollection.asSequence().flatMap { it.stats.asSequence() }
                .filter { it.name.equals(MINUTES_PLAYED, ignoreCase = true) }
                .forEach { stat ->
                    val id = stat.titleid?.trim()?.takeIf { it.isNotEmpty() }
                        ?: stat.scid?.trim()?.lowercase()?.let(byScid::get)
                        ?: return@forEach
                    val raw = stat.value?.trim() ?: return@forEach
                    val minutes = (raw.toLongOrNull() ?: raw.toDoubleOrNull()?.toLong())?.takeIf { it >= 0 } ?: return@forEach
                    values[id] = minutes
                }
        }
        val listed = titles.mapTo(HashSet()) { it.titleId }
        // A modern id several titles name goes to one of them, the same at every sync: the smallest
        // id, never the history's order, which moves with play (a counter that changed hands would
        // leave its old count frozen on the title it left).
        val holder = HashMap<String, String>()
        titles.forEach { t ->
            val modern = t.modernTitleId?.takeIf { it.isNotBlank() && it != t.titleId } ?: return@forEach
            val held = holder[modern]
            if (held == null || t.titleId < held) holder[modern] = t.titleId
        }
        return titles.mapNotNull { t ->
            val own = values[t.titleId]
            // The modern id's counter only when no title of the history is that id (the counter is
            // that title's) and this title holds it.
            val modern = t.modernTitleId?.takeIf { it != t.titleId && it !in listed && holder[it] == t.titleId }?.let(values::get)
            (own ?: modern)?.let { t.titleId to it }
        }.toMap()
    }

    /** [groups] (a title's ids) packed in calls of at most [STATS_BATCH] stats, a group never split between two. */
    private fun batches(groups: List<List<String>>): List<List<String>> {
        val out = mutableListOf<MutableList<String>>()
        for (group in groups) {
            val last = out.lastOrNull()
            if (last != null && last.size + group.size <= STATS_BATCH) last.addAll(group) else out.add(group.toMutableList())
        }
        return out
    }

    /** [sourceVersion] 2: contract 2, paged; 1: the 360 pair merged; 0: nothing, no call. */
    suspend fun achievements(session: XboxSession, titleId: String, sourceVersion: Int): List<FetchedAchievement> = when {
        sourceVersion >= 2 -> modernAchievements(session, titleId)
        sourceVersion == 1 -> legacyAchievements(session, titleId)
        else -> emptyList()
    }

    /** An Xbox One or Series title: one list, earned when `Achieved` and not revoked; 0001-01-01 is no date. */
    private suspend fun modernAchievements(session: XboxSession, titleId: String): List<FetchedAchievement> =
        paged(session, "achievements", titleId, contract = 2, AchievementsAnswer.serializer(), { it.achievements }, { it.pagingInfo })
            .filter { it.id.isNotBlank() }
            .distinctBy { it.id }
            .map { a ->
                FetchedAchievement(
                    id = a.id,
                    name = a.name,
                    description = a.description,
                    gamerscore = a.rewards.firstOrNull { it.type.equals("Gamerscore", ignoreCase = true) }?.value?.let(::number) ?: 0,
                    secret = a.isSecret,
                    earned = a.progressState.equals("Achieved", ignoreCase = true) && !a.isRevoked,
                    unlockedAt = XboxTimes.realUnlock(a.progression?.timeUnlocked)
                )
            }

    /**
     * An Xbox 360 title: the full list (every entry reads locked, placeholder-dated) merged on id
     * with the earned list (an offline unlock dated 1753-01-01). Names, descriptions, gamerscore and
     * the secret flag come from the full list; an earned id it lacks still counts.
     */
    private suspend fun legacyAchievements(session: XboxSession, titleId: String): List<FetchedAchievement> {
        val all = paged(session, "titleachievements", titleId, contract = 1, Achievements360Answer.serializer(), { it.achievements }, { it.pagingInfo })
        val earned = paged(session, "achievements", titleId, contract = 1, Achievements360Answer.serializer(), { it.achievements }, { it.pagingInfo })
        val full = all.filter { it.id.isNotBlank() }.associateBy { it.id }
        val mine = earned.filter { it.id.isNotBlank() }.associateBy { it.id }
        return (full.keys + mine.keys).mapNotNull { id ->
            val definition = full[id]
            val own = mine[id]
            val source = definition ?: own ?: return@mapNotNull null
            val isEarned = own != null && own.unlocked && !own.isRevoked
            FetchedAchievement(
                id = id,
                name = source.name.ifBlank { own?.name.orEmpty() },
                description = source.description.ifBlank { own?.description.orEmpty() },
                gamerscore = source.gamerscore,
                secret = source.isSecret,
                earned = isEarned,
                unlockedAt = if (isEarned) XboxTimes.realUnlock(own?.timeUnlocked) else null
            )
        }
    }

    /** An achievements route's pages, followed through the continuation token (at most [MAX_PAGES]). */
    private suspend fun <A, T> paged(
        session: XboxSession,
        route: String,
        titleId: String,
        contract: Int,
        serializer: KSerializer<A>,
        items: (A) -> List<T>,
        paging: (A) -> PagingInfo?
    ): List<T> {
        val all = mutableListOf<T>()
        var token: String? = null
        var pages = 0
        while (pages < MAX_PAGES) {
            pages++
            val url = "$ACHIEVEMENTS/users/xuid(${encode(session.xuid)})/$route?titleId=${encode(titleId)}&maxItems=$TITLE_PAGE" +
                (token?.let { "&continuationToken=${encode(it)}" } ?: "")
            val answer = decodeData(get(url, dataHeaders(session, contract)), serializer)
            val found = items(answer)
            all += found
            val next = paging(answer)?.continuationToken?.takeIf { it.isNotBlank() }
            if (next == null || next == token || found.isEmpty()) break
            token = next
        }
        return all
    }

    // --- Plumbing ----------------------------------------------------------------------------

    private fun requireConfigured() {
        if (!configured) throw XboxException(XboxException.Kind.NOT_CONFIGURED, "No Xbox app in this build.")
    }

    /** A GET: titlehub and achievements only. */
    private suspend fun get(url: String, headers: Map<String, String>): XboxHttpResponse {
        require(READ_ROOTS.any { url.startsWith(it) }) { "The Xbox client only reads titlehub and achievements." }
        return call { transport.get(url, headers) }
    }

    /** A POST that reads: the user token, XSTS and the stats batch only. */
    private suspend fun read(url: String, headers: Map<String, String>, body: String): XboxHttpResponse {
        require(url in READ_POSTS) { "The Xbox client only posts its three reads." }
        return call { transport.postJson(url, headers, body) }
    }

    /** A sign-in form: the device code and the token endpoint only. */
    private suspend fun login(url: String, form: Map<String, String>): XboxHttpResponse {
        require(url in LOGIN_FORMS) { "The Xbox client only posts its sign-in forms." }
        return call { transport.postForm(url, LOGIN_HEADERS, form) }
    }

    private suspend fun call(block: suspend () -> XboxHttpResponse): XboxHttpResponse = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: IOException) {
        throw XboxException(XboxException.Kind.NETWORK, "No connection to the Xbox network.")
    }

    private fun dataHeaders(session: XboxSession, contract: Int): Map<String, String> = mapOf(
        "Authorization" to session.authorization,
        CONTRACT to contract.toString(),
        "Accept-Language" to "en-US",
        "Accept" to "application/json"
    )

    /** A data call's answer: 401 is a refused token (renewed once by the caller), 429 a wait. */
    private fun <T> decodeData(response: XboxHttpResponse, serializer: KSerializer<T>): T = when (response.code) {
        200 -> decodeOrNull(serializer, response.body) ?: throw unexpectedShape()
        401 -> throw XboxException(XboxException.Kind.TOKEN_EXPIRED, "The Xbox network refused the sign-in token.")
        429 -> throw rateLimited()
        else -> throw XboxException(XboxException.Kind.API, "The Xbox network answered HTTP ${response.code}.")
    }

    private fun <T> decodeOrNull(serializer: KSerializer<T>, body: String): T? {
        if (body.isBlank()) return null
        return try {
            json.decodeFromString(serializer, body)
        } catch (e: IllegalArgumentException) {
            // kotlinx.serialization's errors (SerializationException) are IllegalArgumentExceptions.
            null
        }
    }

    /** Microsoft refused the app itself: told by its AADSTS code, never by the answer's free text. */
    private fun clientError(error: String?, codes: List<Long>, httpCode: Int): XboxException {
        val code = codes.firstOrNull()
        val message = when {
            code == 7000218L ->
                "Microsoft refused this build's app (AADSTS7000218): turn on Allow public client flows in its registration (Authentication), then Connect again."
            code == 700016L ->
                "Microsoft doesn't know this build's app (AADSTS700016): check xboxClientId in local.properties, then build again."
            error == "unauthorized_client" ->
                "Microsoft refused this build's app (unauthorized_client): register it for Personal Microsoft accounts only."
            code != null -> "Microsoft refused this build's app (AADSTS$code)."
            else -> return XboxException(XboxException.Kind.API, "The Microsoft sign-in failed (HTTP $httpCode).")
        }
        return XboxException(XboxException.Kind.CLIENT_REJECTED, message)
    }

    /** What XSTS's XErr says the account needs. */
    private fun xErr(code: Long): XboxException = when (code) {
        2148916233L -> XboxException(
            XboxException.Kind.ACCOUNT_REFUSED,
            "This Microsoft account has no Xbox profile yet: sign in once at xbox.com or on the console."
        )
        2148916238L -> XboxException(XboxException.Kind.ACCOUNT_REFUSED, "A child account can't sign in to this app: use the adult account.")
        2148916235L -> XboxException(XboxException.Kind.ACCOUNT_REFUSED, "The Xbox network isn't offered in this account's country.")
        2148916227L -> XboxException(XboxException.Kind.ACCOUNT_REFUSED, "This account is banned from the Xbox network.")
        2148916229L, 2148916237L -> XboxException(XboxException.Kind.ACCOUNT_REFUSED, "The account's family settings block the sign-in.")
        2148916234L, 2148916236L -> XboxException(
            XboxException.Kind.ACCOUNT_REFUSED,
            "Sign in once at xbox.com to accept the terms or pass the age check."
        )
        2148916258L, 2148916262L -> XboxException(XboxException.Kind.SIGN_IN_EXPIRED, "The Xbox sign-in has expired: Connect again.")
        2148916273L, 2148916274L -> XboxException(XboxException.Kind.API, "The Xbox sign-in service is down: try again later.")
        else -> XboxException(XboxException.Kind.ACCOUNT_REFUSED, "The Xbox network refused the sign-in (error $code).")
    }

    private fun rateLimited() = XboxException(XboxException.Kind.RATE_LIMITED, "The Xbox network asks to wait (too many requests).")

    private fun unexpectedShape() = XboxException(XboxException.Kind.API, "The Xbox network answered in an unexpected shape.")

    private fun number(raw: String): Int? = raw.trim().let { it.toIntOrNull() ?: it.toDoubleOrNull()?.toInt() }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    companion object {
        /** The plain link page: the screen opens it and shows the code, never the pre-filled `?otc=` link. */
        const val LINK_PAGE = "https://www.microsoft.com/link"
        const val SCOPE = "XboxLive.signin XboxLive.offline_access"

        /** Title history's one page (no paging): a full one may miss older titles. Also the achievements' page size. */
        const val TITLE_PAGE = 1000
        const val STATS_BATCH = 50

        private const val TAG = "XboxClient"
        private const val LOGIN = "https://login.microsoftonline.com/consumers/oauth2/v2.0"
        private const val DEVICE_CODE = "$LOGIN/devicecode"
        private const val TOKEN = "$LOGIN/token"
        private const val DEVICE_GRANT = "urn:ietf:params:oauth:grant-type:device_code"
        private const val USER_AUTH = "https://user.auth.xboxlive.com/user/authenticate"
        private const val XSTS = "https://xsts.auth.xboxlive.com/xsts/authorize"
        private const val TITLEHUB = "https://titlehub.xboxlive.com"
        private const val USERSTATS_BATCH = "https://userstats.xboxlive.com/batch"
        private const val ACHIEVEMENTS = "https://achievements.xboxlive.com"
        private const val CONTRACT = "x-xbl-contract-version"
        private const val MINUTES_PLAYED = "MinutesPlayed"
        private const val MAX_PAGES = 10
        private const val MAX_CODE_SECONDS = 900L
        private const val MIN_INTERVAL_SECONDS = 5L
        private const val DEFAULT_ACCESS_SECONDS = 3600L

        private val READ_ROOTS = listOf("$TITLEHUB/", "$ACHIEVEMENTS/")
        private val READ_POSTS = setOf(USER_AUTH, XSTS, USERSTATS_BATCH)
        private val LOGIN_FORMS = setOf(DEVICE_CODE, TOKEN)
        private val LOGIN_HEADERS = mapOf("Accept" to "application/json")

        /** The two sign-in steps: contract 1, and no XBL header. */
        private val SIGN_IN_HEADERS = mapOf(CONTRACT to "1", "Accept" to "application/json")
    }
}
