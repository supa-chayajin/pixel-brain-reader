package cloud.wafflecommons.pixelbrainreader.data.games.xbox

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.time.Instant

/**
 * The Xbox client against a fake transport (XB-C, XB-G): the answers are shaped like
 * python-xbox's recorded ones; no call ever leaves the test.
 */
class XboxClientTest {

    /** One request as the transport saw it. */
    private data class Call(
        val method: String,
        val url: String,
        val headers: Map<String, String>,
        val form: Map<String, String> = emptyMap(),
        val json: String? = null
    )

    /** Answers by URL prefix (the longest one wins) and records what was asked. */
    private class FakeTransport(private val routes: List<Pair<String, (Call) -> XboxHttpResponse>>) : XboxTransport {
        val calls = mutableListOf<Call>()

        override suspend fun get(url: String, headers: Map<String, String>): XboxHttpResponse = answer(Call("GET", url, headers))

        override suspend fun postForm(url: String, headers: Map<String, String>, form: Map<String, String>): XboxHttpResponse =
            answer(Call("FORM", url, headers, form = form))

        override suspend fun postJson(url: String, headers: Map<String, String>, json: String): XboxHttpResponse =
            answer(Call("JSON", url, headers, json = json))

        private fun answer(call: Call): XboxHttpResponse {
            calls += call
            return routes.filter { call.url.startsWith(it.first) }.maxByOrNull { it.first.length }?.second?.invoke(call)
                ?: XboxHttpResponse(404, "")
        }
    }

    private val login = "https://login.microsoftonline.com/consumers/oauth2/v2.0"
    private val userAuth = "https://user.auth.xboxlive.com/user/authenticate"
    private val xsts = "https://xsts.auth.xboxlive.com/xsts/authorize"
    private val stats = "https://userstats.xboxlive.com/batch"
    private val xuid = "2533274800000001"
    private val titlehub = "https://titlehub.xboxlive.com/users/xuid($xuid)/titles/titlehistory"
    private val achievements = "https://achievements.xboxlive.com/users/xuid($xuid)/achievements"
    private val all360 = "https://achievements.xboxlive.com/users/xuid($xuid)/titleachievements"
    private val session = XboxSession("UHS", "XSTS", xuid, expiresAt = 0L)
    private val code = XboxDeviceCode("ABCD-EFGH", "device-code-1", expiresAt = 900_000L, intervalSeconds = 5L)

    private val userToken = """{"IssueInstant":"2026-11-27T22:40:00.0000000Z","NotAfter":"2026-12-11T22:40:00.0000000Z","Token":"USER-TOKEN","DisplayClaims":{"xui":[{"uhs":"UHS"}]}}"""
    private val xstsToken =
        """{"IssueInstant":"2026-11-27T22:40:01.1234567Z","NotAfter":"2026-11-28T14:40:01.1234567Z","Token":"XSTS-TOKEN","DisplayClaims":{"xui":[{"xid":"$xuid","uhs":"UHS","agg":"Adult"}]}}"""

    private fun client(vararg routes: Pair<String, (Call) -> XboxHttpResponse>) =
        FakeTransport(routes.toList()).let { it to XboxClient(it, "client-id") }

    private fun body(call: Call): JsonObject = Json.parseToJsonElement(call.json.orEmpty()).jsonObject

    private fun JsonObject.text(key: String): String = getValue(key).jsonPrimitive.content

    /** The title ids a stats batch asked, in order. */
    private fun askedIds(call: Call): List<String> = body(call).getValue("stats").jsonArray.map { it.jsonObject.text("titleId") }

    @Test
    fun `the device code asks the consumers endpoint with the Xbox scopes`() = runTest {
        val (transport, xbox) = client(
            "$login/devicecode" to { _ ->
                XboxHttpResponse(
                    200,
                    """{"user_code":"ABCD-EFGH","device_code":"device-code-1","verification_uri":"https://www.microsoft.com/link","expires_in":900,"interval":5,"message":"Enter the code."}"""
                )
            }
        )

        val started = xbox.startDeviceCode(now = 1_000L)

        assertEquals("ABCD-EFGH", started.userCode)
        assertEquals("device-code-1", started.deviceCode)
        assertEquals(1_000L + 900_000L, started.expiresAt)
        assertEquals(5L, started.intervalSeconds)
        val call = transport.calls.single()
        assertEquals("FORM", call.method)
        assertEquals("$login/devicecode", call.url)
        assertEquals(mapOf("client_id" to "client-id", "scope" to "XboxLive.signin XboxLive.offline_access"), call.form)
        assertFalse(call.headers.containsKey("Authorization"))
        // The codes never show in a log line.
        assertFalse(started.toString().contains("device-code-1"))
        assertFalse(started.toString().contains("ABCD-EFGH"))

        // A longer life than 15 minutes, or a shorter interval than 5 s, is held to the flow's own.
        val (_, generous) = client(
            "$login/devicecode" to { _ -> XboxHttpResponse(200, """{"user_code":"ABCD-EFGH","device_code":"device-code-1","expires_in":3600,"interval":1}""") }
        )
        val capped = generous.startDeviceCode(0L)
        assertEquals(900_000L, capped.expiresAt)
        assertEquals(5L, capped.intervalSeconds)
    }

    @Test
    fun `polling reads pending, slow down, declined, expired and done`() = runTest {
        suspend fun pollWith(answer: String, status: Int = 400): DevicePoll {
            val (_, xbox) = client("$login/token" to { _ -> XboxHttpResponse(status, answer) })
            return xbox.pollDeviceCode(code, now = 2_000L)
        }

        assertEquals(DevicePoll.Pending, pollWith("""{"error":"authorization_pending","error_description":"AADSTS70016: pending","error_codes":[70016]}"""))
        assertEquals(DevicePoll.SlowDown, pollWith("""{"error":"slow_down"}"""))
        assertEquals(DevicePoll.Declined, pollWith("""{"error":"authorization_declined","error_codes":[70000]}"""))
        assertEquals(DevicePoll.Declined, pollWith("""{"error":"access_denied"}"""))
        assertEquals(DevicePoll.Expired, pollWith("""{"error":"expired_token","error_codes":[70019]}"""))
        assertEquals(DevicePoll.Expired, pollWith("""{"error":"invalid_grant"}"""))
        assertEquals(DevicePoll.Expired, pollWith("""{"error":"code_expired"}"""))
        val done = pollWith(
            """{"token_type":"Bearer","scope":"XboxLive.signin XboxLive.offline_access","expires_in":3600,"ext_expires_in":3600,"access_token":"ACCESS","refresh_token":"REFRESH"}""",
            status = 200
        )
        assertEquals(DevicePoll.Done(XboxTokens("ACCESS", 2_000L + 3_600_000L, "REFRESH", refreshIssuedAt = 2_000L)), done)
        assertEquals(
            "Microsoft refused the sign-in code: Connect again.",
            expect(XboxException.Kind.API) { pollWith("""{"error":"bad_verification_code"}""") }.message
        )
        // Without a refresh token the sign-in wouldn't last: refused.
        expect(XboxException.Kind.API) { pollWith("""{"access_token":"ACCESS","expires_in":3600}""", status = 200) }

        // What is sent: the device-code grant, never a secret.
        val (transport, xbox) = client("$login/token" to { _ -> XboxHttpResponse(400, """{"error":"authorization_pending"}""") })
        xbox.pollDeviceCode(code, 0L)
        assertEquals(
            mapOf("grant_type" to "urn:ietf:params:oauth:grant-type:device_code", "client_id" to "client-id", "device_code" to "device-code-1"),
            transport.calls.single().form
        )
    }

    @Test
    fun `a refused app is told by its AADSTS code, never its text`() = runTest {
        val noPublicFlows =
            """{"error":"invalid_client","error_description":"AADSTS7000218: The request body must contain client_assertion. Trace ID: trace-1","error_codes":[7000218]}"""
        val (_, a) = client("$login/devicecode" to { _ -> XboxHttpResponse(400, noPublicFlows) })
        val first = expect(XboxException.Kind.CLIENT_REJECTED) { a.startDeviceCode(0L) }.message.orEmpty()
        assertTrue(first.contains("AADSTS7000218"))
        assertTrue(first.contains("Allow public client flows"))
        assertFalse(first.contains("client_assertion"))
        assertFalse(first.contains("trace-1"))

        val unknown = """{"error":"unauthorized_client","error_description":"AADSTS700016: Application with identifier 'x' was not found.","error_codes":[700016]}"""
        val (_, b) = client("$login/devicecode" to { _ -> XboxHttpResponse(400, unknown) })
        val second = expect(XboxException.Kind.CLIENT_REJECTED) { b.startDeviceCode(0L) }.message.orEmpty()
        assertTrue(second.contains("AADSTS700016"))
        assertTrue(second.contains("xboxClientId"))
        assertFalse(second.contains("identifier"))

        val wrongAccounts = """{"error":"unauthorized_client","error_description":"The client is not enabled for consumers.","error_codes":[70002]}"""
        val (_, c) = client("$login/devicecode" to { _ -> XboxHttpResponse(400, wrongAccounts) })
        val third = expect(XboxException.Kind.CLIENT_REJECTED) { c.startDeviceCode(0L) }.message.orEmpty()
        assertTrue(third.contains("unauthorized_client"))
        assertTrue(third.contains("Personal Microsoft accounts only"))
        assertFalse(third.contains("enabled"))

        // Another code is named by its number only.
        val other = """{"error":"invalid_scope","error_description":"AADSTS70011: The scope is not valid.","error_codes":[70011]}"""
        val (_, d) = client("$login/devicecode" to { _ -> XboxHttpResponse(400, other) })
        assertEquals("Microsoft refused this build's app (AADSTS70011).", expect(XboxException.Kind.CLIENT_REJECTED) { d.startDeviceCode(0L) }.message)

        // The same rules at the poll and at the refresh.
        val (_, e) = client("$login/token" to { _ -> XboxHttpResponse(401, noPublicFlows) })
        expect(XboxException.Kind.CLIENT_REJECTED) { e.pollDeviceCode(code, 0L) }
        expect(XboxException.Kind.CLIENT_REJECTED) { e.refresh(XboxTokens("OLD", 0L, "REFRESH", 0L), 0L) }

        // No answer to read (a gateway error): the HTTP status, not a refused app.
        val (_, f) = client("$login/devicecode" to { _ -> XboxHttpResponse(503, "<html>down</html>") })
        assertEquals("The Microsoft sign-in failed (HTTP 503).", expect(XboxException.Kind.API) { f.startDeviceCode(0L) }.message)
    }

    @Test
    fun `a refresh keeps the new refresh token, or the old one when none comes`() = runTest {
        val old = XboxTokens("OLD", 0L, "REFRESH", refreshIssuedAt = 5L)
        val (transport, rotating) = client(
            "$login/token" to { _ -> XboxHttpResponse(200, """{"access_token":"NEW","refresh_token":"REFRESH-2","expires_in":3600}""") }
        )

        val fresh = rotating.refresh(old, now = 1_000L)

        assertEquals(XboxTokens("NEW", 1_000L + 3_600_000L, "REFRESH-2", refreshIssuedAt = 1_000L), fresh)
        assertEquals(
            mapOf("grant_type" to "refresh_token", "client_id" to "client-id", "refresh_token" to "REFRESH", "scope" to "XboxLive.signin XboxLive.offline_access"),
            transport.calls.single().form
        )
        // The tokens never show in a log line.
        assertFalse(fresh.toString().contains("NEW"))
        assertFalse(fresh.toString().contains("REFRESH"))

        val (_, same) = client("$login/token" to { _ -> XboxHttpResponse(200, """{"access_token":"NEW","expires_in":3600}""") })
        val kept = same.refresh(old, now = 1_000L)
        assertEquals("REFRESH", kept.refresh)
        assertEquals(5L, kept.refreshIssuedAt)
    }

    @Test
    fun `an expired refresh token asks to sign in again`() = runTest {
        val old = XboxTokens("OLD", 0L, "REFRESH", 0L)
        val (_, xbox) = client(
            "$login/token" to { _ -> XboxHttpResponse(400, """{"error":"invalid_grant","error_description":"AADSTS70008: expired","error_codes":[70008]}""") }
        )
        assertEquals("The Xbox sign-in has expired: Connect again.", expect(XboxException.Kind.SIGN_IN_EXPIRED) { xbox.refresh(old, 0L) }.message)

        val (_, consent) = client("$login/token" to { _ -> XboxHttpResponse(400, """{"error":"interaction_required","error_codes":[65001]}""") })
        expect(XboxException.Kind.SIGN_IN_EXPIRED) { consent.refresh(old, 0L) }

        val (_, busy) = client("$login/token" to { _ -> XboxHttpResponse(429, "") })
        expect(XboxException.Kind.RATE_LIMITED) { busy.refresh(old, 0L) }
    }

    @Test
    fun `the user token sends d= and the XSTS RETAIL for xboxlive_com`() = runTest {
        val (transport, xbox) = client(
            userAuth to { _ -> XboxHttpResponse(200, userToken) },
            xsts to { _ -> XboxHttpResponse(200, xstsToken) }
        )

        val derived = xbox.session("ACCESS")

        assertEquals("UHS", derived.userHash)
        assertEquals("XSTS-TOKEN", derived.xsts)
        assertEquals(xuid, derived.xuid)
        assertEquals(Instant.parse("2026-11-28T14:40:01.1234567Z").toEpochMilli(), derived.expiresAt)
        assertEquals("XBL3.0 x=UHS;XSTS-TOKEN", derived.authorization)
        // Neither the token nor the account's id ever shows in a log line.
        assertFalse(derived.toString().contains(xuid))
        assertFalse(derived.toString().contains("XSTS-TOKEN"))

        val (user, authorize) = transport.calls
        assertEquals("JSON", user.method)
        val userBody = body(user)
        assertEquals("http://auth.xboxlive.com", userBody.text("RelyingParty"))
        assertEquals("JWT", userBody.text("TokenType"))
        val properties = userBody.getValue("Properties").jsonObject
        assertEquals("RPS", properties.text("AuthMethod"))
        assertEquals("user.auth.xboxlive.com", properties.text("SiteName"))
        assertEquals("d=ACCESS", properties.text("RpsTicket"))
        assertEquals("1", user.headers["x-xbl-contract-version"])

        val xstsBody = body(authorize)
        assertEquals(xsts, authorize.url)
        assertEquals("http://xboxlive.com", xstsBody.text("RelyingParty"))
        val xstsProperties = xstsBody.getValue("Properties").jsonObject
        assertEquals("RETAIL", xstsProperties.text("SandboxId"))
        assertEquals(listOf("USER-TOKEN"), xstsProperties.getValue("UserTokens").jsonArray.map { it.jsonPrimitive.content })
        // The sign-in steps carry no XBL header.
        assertTrue(transport.calls.none { it.headers.containsKey("Authorization") })

        // A stale access token is refused at the first step: the caller renews it.
        val (_, stale) = client(userAuth to { _ -> XboxHttpResponse(401, "") })
        expect(XboxException.Kind.TOKEN_EXPIRED) { stale.session("ACCESS") }
        // An answer without its claims is a shape the client doesn't know.
        val (_, odd) = client(
            userAuth to { _ -> XboxHttpResponse(200, userToken) },
            xsts to { _ -> XboxHttpResponse(200, """{"Token":"XSTS-TOKEN","DisplayClaims":{"xui":[]}}""") }
        )
        expect(XboxException.Kind.API) { odd.session("ACCESS") }
    }

    @Test
    fun `XSTS errors say what the account needs`() = runTest {
        suspend fun refusedWith(xErr: Long): XboxException {
            val (_, xbox) = client(
                userAuth to { _ -> XboxHttpResponse(200, userToken) },
                xsts to { _ ->
                    XboxHttpResponse(401, """{"Identity":"0","XErr":$xErr,"Message":"","Redirect":"https://start.ui.xboxlive.com/CreateAccount"}""")
                }
            )
            return expect(null) { xbox.session("ACCESS") }
        }

        val noProfile = refusedWith(2148916233)
        assertEquals(XboxException.Kind.ACCOUNT_REFUSED, noProfile.kind)
        assertTrue(noProfile.message.orEmpty().contains("no Xbox profile"))
        val child = refusedWith(2148916238)
        assertEquals(XboxException.Kind.ACCOUNT_REFUSED, child.kind)
        assertTrue(child.message.orEmpty().contains("child account"))
        assertEquals(XboxException.Kind.SIGN_IN_EXPIRED, refusedWith(2148916262).kind)
        assertEquals(XboxException.Kind.SIGN_IN_EXPIRED, refusedWith(2148916258).kind)
        assertEquals(XboxException.Kind.API, refusedWith(2148916273).kind)
        val other = refusedWith(2148916999)
        assertEquals(XboxException.Kind.ACCOUNT_REFUSED, other.kind)
        assertTrue(other.message.orEmpty().contains("2148916999"))
    }

    @Test
    fun `title history keeps games only and says when it is full`() = runTest {
        val answer = """{"xuid":"$xuid","titles":[
            {"titleId":"1915865634","pfn":"x","name":"Ring of Ash","type":"Game","devices":["XboxOne","XboxSeries"],"mediaItemType":"Application","modernTitleId":"1915865634","displayImage":"https://store-images.s-microsoft.com/image/apps.12345","serviceConfigId":"00000000-0000-0000-0000-000072300001","achievement":{"currentAchievements":22,"totalAchievements":0,"currentGamerscore":340,"totalGamerscore":1000,"progressPercentage":34.0,"sourceVersion":2},"titleHistory":{"lastTimePlayed":"2026-11-27T21:13:45.4411936Z","visible":true,"canHide":false}},
            {"titleId":"1234567899","name":"A Video App","type":"Application","mediaItemType":"Application"},
            {"titleId":1480000001,"name":"Outlaw Trail II","type":"Game","mediaItemType":"Xbox360Game","modernTitleId":"1915865635","achievement":{"sourceVersion":1,"currentAchievements":2,"totalAchievements":12},"titleHistory":{"lastTimePlayed":"2025-10-30T20:30:20.61Z","visible":false}}
        ]}"""
        val (transport, xbox) = client(titlehub to { _ -> XboxHttpResponse(200, answer) })

        val list = xbox.titleHistory(session)

        assertEquals(listOf("1915865634", "1480000001"), list.titles.map { it.titleId })
        assertFalse(list.full)
        val ring = list.titles.first()
        assertEquals(2, ring.achievement?.sourceVersion)
        assertEquals(34.0, ring.achievement?.progressPercentage ?: 0.0, 0.0)
        assertEquals("2026-11-27T21:13:45.4411936Z", ring.titleHistory?.lastTimePlayed)
        assertEquals("00000000-0000-0000-0000-000072300001", ring.serviceConfigId)
        assertEquals(false, list.titles[1].titleHistory?.visible)
        assertEquals(12, list.titles[1].achievement?.totalAchievements)
        val call = transport.calls.single()
        assertEquals("GET", call.method)
        assertEquals("$titlehub/decoration/achievement,scid?maxItems=1000", call.url)

        // A full answer (1,000 entries) may have left older titles out.
        val many = (1..1000).joinToString(",", "{\"titles\":[", "]}") { """{"titleId":"$it","name":"Game $it","type":"Game"}""" }
        val (_, full) = client(titlehub to { _ -> XboxHttpResponse(200, many) })
        assertTrue(full.titleHistory(session).full)
    }

    @Test
    fun `minutes read strings and numbers, by titleid or scid, the modern id as a fallback`() = runTest {
        val titles = listOf(
            XboxTitle(titleId = "1915865634", name = "Ring of Ash", type = "Game", modernTitleId = "1915865634"),
            XboxTitle(titleId = "1234567890", name = "Brick Wars", type = "Game", serviceConfigId = "AAAA0000-0000-0000-0000-000000000001"),
            XboxTitle(titleId = "1480000001", name = "Outlaw Trail II", type = "Game", modernTitleId = "1915865635"),
            XboxTitle(titleId = "987654321", name = "No Stats", type = "Game")
        )
        val answer = """{"groups":[],"statlistscollection":[{"arrangebyfield":"xuid","arrangebyfieldid":"$xuid","stats":[
            {"xuid":"$xuid","scid":"x","titleid":"1915865634","name":"MinutesPlayed","type":"Integer","value":"1220"},
            {"xuid":"$xuid","scid":"aaaa0000-0000-0000-0000-000000000001","name":"MinutesPlayed","type":"Integer","value":95},
            {"xuid":"$xuid","titleid":"1915865635","name":"MinutesPlayed","value":"40.0"},
            {"xuid":"$xuid","titleid":"987654321","name":"SomethingElse","value":"7"}
        ]}]}"""
        val (transport, xbox) = client(stats to { _ -> XboxHttpResponse(200, answer) })

        val minutes = xbox.minutesPlayed(session, titles)

        assertEquals(mapOf("1915865634" to 1220L, "1234567890" to 95L, "1480000001" to 40L), minutes)
        // One call: every id once, a 360 game's modern id after its own.
        val call = transport.calls.single()
        assertEquals("JSON", call.method)
        val sent = body(call)
        assertEquals("xuid", sent.text("arrangebyfield"))
        assertEquals(listOf(xuid), sent.getValue("xuids").jsonArray.map { it.jsonPrimitive.content })
        val asked = sent.getValue("stats").jsonArray.map { it.jsonObject }
        assertTrue(asked.all { it.text("name") == "MinutesPlayed" })
        assertEquals(listOf("1915865634", "1234567890", "1480000001", "1915865635", "987654321"), asked.map { it.text("titleId") })
        assertEquals("2", call.headers["x-xbl-contract-version"])
    }

    @Test
    fun `a 360 game's minutes count once, its ids asked in one call`() = runTest {
        // The 360 version names its modern id, which the history lists as a title too: the counter is the modern title's alone.
        val legacy = XboxTitle(titleId = "1480000001", name = "Outlaw Trail II", type = "Game", mediaItemType = "Xbox360Game", modernTitleId = "1915865635")
        val modern = XboxTitle(titleId = "1915865635", name = "Outlaw Trail II", type = "Game", mediaItemType = "Application")
        val onlyModern = """{"statlistscollection":[{"stats":[{"titleid":"1915865635","name":"MinutesPlayed","value":"40"}]}]}"""
        val (transport, xbox) = client(stats to { _ -> XboxHttpResponse(200, onlyModern) })

        assertEquals(mapOf("1915865635" to 40L), xbox.minutesPlayed(session, listOf(legacy, modern)))
        // Each id asked once.
        assertEquals(listOf("1480000001", "1915865635"), askedIds(transport.calls.single()))
        // Listed the other way round: the same.
        val (_, reversed) = client(stats to { _ -> XboxHttpResponse(200, onlyModern) })
        assertEquals(mapOf("1915865635" to 40L), reversed.minutesPlayed(session, listOf(modern, legacy)))
        // Two 360 titles naming one modern id the history doesn't list: the smallest id holds the
        // counter, whatever the order (it moves with play), and the other gets none.
        val twin = XboxTitle(titleId = "1480000002", name = "Outlaw Trail II Classic", type = "Game", mediaItemType = "Xbox360Game", modernTitleId = "1915865635")
        val (_, shared) = client(stats to { _ -> XboxHttpResponse(200, onlyModern) })
        assertEquals(mapOf("1480000001" to 40L), shared.minutesPlayed(session, listOf(twin, legacy)))
        val (_, sharedAgain) = client(stats to { _ -> XboxHttpResponse(200, onlyModern) })
        assertEquals(mapOf("1480000001" to 40L), sharedAgain.minutesPlayed(session, listOf(legacy, twin)))

        // 49 titles leave one place in the first call: the 360 game and its modern id go into the next one together.
        val filler = (1..49).map { XboxTitle(titleId = (1_000_000_000L + it).toString(), name = "Game $it", type = "Game") }
        val (batched, packed) = client(stats to { _ -> XboxHttpResponse(200, """{"statlistscollection":[]}""") })
        packed.minutesPlayed(session, filler + legacy)
        assertEquals(listOf(49, 2), batched.calls.map { askedIds(it).size })
        assertEquals(listOf("1480000001", "1915865635"), askedIds(batched.calls[1]))
    }

    @Test
    fun `an empty or failed minutes batch drops only its titles`() = runTest {
        val titles = (1..60).map { XboxTitle(titleId = (1_000_000_000L + it).toString(), name = "Game $it", type = "Game") }
        val (transport, xbox) = client(
            stats to { call ->
                if (call.json.orEmpty().contains("\"1000000001\"")) {
                    XboxHttpResponse(500, "")
                } else {
                    XboxHttpResponse(200, """{"statlistscollection":[{"stats":[{"titleid":"1000000055","name":"MinutesPlayed","value":"30"}]}]}""")
                }
            }
        )

        val minutes = xbox.minutesPlayed(session, titles)

        assertEquals(mapOf("1000000055" to 30L), minutes)
        assertEquals(listOf(50, 10), transport.calls.map { body(it).getValue("stats").jsonArray.size })

        // An empty answer is a valid one: nothing known, nothing lost.
        val (_, empty) = client(stats to { _ -> XboxHttpResponse(200, """{"groups":[],"statlistscollection":[]}""") })
        assertTrue(empty.minutesPlayed(session, titles).isEmpty())
        // Asked to wait: the next batches would be refused too, so they wait for the next sync.
        val (busyTransport, busy) = client(stats to { _ -> XboxHttpResponse(429, "") })
        assertTrue(busy.minutesPlayed(session, titles).isEmpty())
        assertEquals(1, busyTransport.calls.size)
        // A refused token is the caller's to renew.
        val (_, refused) = client(stats to { _ -> XboxHttpResponse(401, "") })
        expect(XboxException.Kind.TOKEN_EXPIRED) { refused.minutesPlayed(session, titles) }
    }

    @Test
    fun `One achievements follow the continuation token, 0001 is not earned, revoked is not earned`() = runTest {
        val first = """{"achievements":[
            {"id":"1","serviceConfigId":"x","name":"First Steps","description":"Finish the prologue.","lockedDescription":"Finish the prologue.","progressState":"Achieved","progression":{"requirements":[],"timeUnlocked":"2026-11-02T20:10:00.1234567Z"},"isSecret":false,"isRevoked":false,"rewards":[{"name":null,"description":null,"value":"10","type":"Gamerscore","valueType":"Int"}]},
            {"id":"2","name":"The Hidden Boss","description":"Beat it.","progressState":"NotStarted","progression":{"timeUnlocked":"0001-01-01T00:00:00.0000000Z"},"isSecret":true,"rewards":[{"value":"50","type":"Gamerscore"}]}
        ],"pagingInfo":{"continuationToken":"32","totalRecords":4}}"""
        val second = """{"achievements":[
            {"id":"3","name":"Lord of Ash","progressState":"Achieved","progression":{"timeUnlocked":"2026-11-01T19:00:00Z"},"isRevoked":true,"rewards":[{"type":"Gamerscore","value":"30"}]},
            {"id":"4","name":"Almost","progressState":"InProgress","progression":{"timeUnlocked":"0001-01-01T00:00:00.0000000Z"},"rewards":[{"type":"Art","value":"x"},{"type":"Gamerscore","value":"20"}]}
        ],"pagingInfo":{"continuationToken":null,"totalRecords":4}}"""
        val (transport, xbox) = client(
            achievements to { call -> XboxHttpResponse(200, if (call.url.contains("continuationToken=32")) second else first) }
        )

        val fetched = xbox.achievements(session, "1915865634", sourceVersion = 2)

        assertEquals(listOf("1", "2", "3", "4"), fetched.map { it.id })
        val (steps, boss, lord, almost) = fetched
        assertTrue(steps.earned)
        assertEquals(Instant.parse("2026-11-02T20:10:00.1234567Z"), steps.unlockedAt)
        assertEquals(10, steps.gamerscore)
        assertEquals("First Steps", steps.name)
        assertFalse(boss.earned)
        assertNull(boss.unlockedAt)
        assertTrue(boss.secret)
        assertEquals(50, boss.gamerscore)
        // Revoked: not earned.
        assertFalse(lord.earned)
        assertFalse(almost.earned)
        assertEquals(20, almost.gamerscore)
        assertEquals(
            listOf(
                "$achievements?titleId=1915865634&maxItems=1000",
                "$achievements?titleId=1915865634&maxItems=1000&continuationToken=32"
            ),
            transport.calls.map { it.url }
        )
        assertTrue(transport.calls.all { it.headers["x-xbl-contract-version"] == "2" })
    }

    @Test
    fun `360 achievements merge the full list with the earned one, 1753 is earned without a date`() = runTest {
        val full = """{"achievements":[
            {"id":1,"titleId":1480000001,"name":"Old Hero","sequence":1,"unlockedOnline":false,"unlocked":false,"isSecret":false,"gamerscore":20,"description":"Beat chapter one.","timeUnlocked":"2002-11-15T00:00:00Z","isRevoked":false},
            {"id":2,"name":"Offline Feat","unlocked":false,"gamerscore":30,"timeUnlocked":"2002-11-15T00:00:00Z"},
            {"id":3,"name":"Secret Ending","unlocked":false,"isSecret":true,"gamerscore":50,"timeUnlocked":"2002-11-15T00:00:00Z"}
        ],"pagingInfo":{"continuationToken":null,"totalRecords":3}}"""
        val earned = """{"achievements":[
            {"id":1,"name":"Old Hero","unlockedOnline":true,"unlocked":true,"gamerscore":20,"timeUnlocked":"2026-10-30T18:00:00Z"},
            {"id":2,"name":"Offline Feat","unlockedOnline":false,"unlocked":true,"gamerscore":30,"timeUnlocked":"1753-01-01T00:00:00Z"},
            {"id":9,"name":"Bonus","unlocked":true,"gamerscore":5,"timeUnlocked":"2026-10-31T18:00:00Z"}
        ],"pagingInfo":{"continuationToken":null}}"""
        val (transport, xbox) = client(
            all360 to { _ -> XboxHttpResponse(200, full) },
            achievements to { _ -> XboxHttpResponse(200, earned) }
        )

        val fetched = xbox.achievements(session, "1480000001", sourceVersion = 1).associateBy { it.id }

        assertEquals(setOf("1", "2", "3", "9"), fetched.keys)
        assertTrue(fetched.getValue("1").earned)
        assertEquals(Instant.parse("2026-10-30T18:00:00Z"), fetched.getValue("1").unlockedAt)
        assertEquals("Beat chapter one.", fetched.getValue("1").description)
        // Unlocked offline: earned, without a date (the first sync that sees it dates it).
        assertTrue(fetched.getValue("2").earned)
        assertNull(fetched.getValue("2").unlockedAt)
        assertFalse(fetched.getValue("3").earned)
        assertTrue(fetched.getValue("3").secret)
        assertNull(fetched.getValue("3").unlockedAt)
        // An earned id the full list lacks still counts.
        assertTrue(fetched.getValue("9").earned)
        assertEquals(5, fetched.getValue("9").gamerscore)
        assertEquals(
            listOf("$all360?titleId=1480000001&maxItems=1000", "$achievements?titleId=1480000001&maxItems=1000"),
            transport.calls.map { it.url }
        )
        assertTrue(transport.calls.all { it.headers["x-xbl-contract-version"] == "1" })

        // A title without achievements asks nothing.
        val (silent, none) = client()
        assertTrue(none.achievements(session, "1234567890", sourceVersion = 0).isEmpty())
        assertTrue(silent.calls.isEmpty())
    }

    @Test
    fun `data calls carry XBL3_0, the contract and en-US`() = runTest {
        val (transport, xbox) = client(
            titlehub to { _ -> XboxHttpResponse(200, """{"titles":[]}""") },
            stats to { _ -> XboxHttpResponse(200, """{"statlistscollection":[]}""") },
            achievements to { _ -> XboxHttpResponse(200, """{"achievements":[]}""") }
        )

        xbox.titleHistory(session)
        xbox.minutesPlayed(session, listOf(XboxTitle(titleId = "1915865634", type = "Game")))
        xbox.achievements(session, "1915865634", sourceVersion = 2)

        assertEquals(3, transport.calls.size)
        transport.calls.forEach { call ->
            assertEquals("XBL3.0 x=UHS;XSTS", call.headers["Authorization"])
            assertEquals("2", call.headers["x-xbl-contract-version"])
            assertEquals("en-US", call.headers["Accept-Language"])
            assertEquals("application/json", call.headers["Accept"])
        }
    }

    @Test
    fun `401 is TOKEN_EXPIRED, 429 RATE_LIMITED`() = runTest {
        val (_, refused) = client("https://titlehub.xboxlive.com" to { _ -> XboxHttpResponse(401, "") })
        expect(XboxException.Kind.TOKEN_EXPIRED) { refused.titleHistory(session) }
        val (_, busy) = client("https://achievements.xboxlive.com" to { _ -> XboxHttpResponse(429, "", retryAfterSeconds = 30) })
        expect(XboxException.Kind.RATE_LIMITED) { busy.achievements(session, "1915865634", 2) }
        val (_, broken) = client("https://titlehub.xboxlive.com" to { _ -> XboxHttpResponse(200, "<html>maintenance</html>") })
        assertEquals(
            "The Xbox network answered in an unexpected shape.",
            expect(XboxException.Kind.API) { broken.titleHistory(session) }.message
        )
        val (_, down) = client("https://titlehub.xboxlive.com" to { _ -> XboxHttpResponse(503, "") })
        assertEquals("The Xbox network answered HTTP 503.", expect(XboxException.Kind.API) { down.titleHistory(session) }.message)
        val (_, offline) = client("https://titlehub.xboxlive.com" to { _ -> throw IOException("no route") })
        assertEquals("No connection to the Xbox network.", expect(XboxException.Kind.NETWORK) { offline.titleHistory(session) }.message)
    }

    @Test
    fun `the client only reads - GETs and three read POSTs`() = runTest {
        val (transport, xbox) = client(
            "$login/devicecode" to { _ -> XboxHttpResponse(200, """{"user_code":"ABCD-EFGH","device_code":"device-code-1"}""") },
            "$login/token" to { _ -> XboxHttpResponse(200, """{"access_token":"ACCESS","refresh_token":"REFRESH","expires_in":3600}""") },
            userAuth to { _ -> XboxHttpResponse(200, userToken) },
            xsts to { _ -> XboxHttpResponse(200, xstsToken) },
            titlehub to { _ -> XboxHttpResponse(200, """{"titles":[{"titleId":"1915865634","name":"Ring of Ash","type":"Game"}]}""") },
            stats to { _ -> XboxHttpResponse(200, """{"statlistscollection":[]}""") },
            achievements to { _ -> XboxHttpResponse(200, """{"achievements":[]}""") },
            all360 to { _ -> XboxHttpResponse(200, """{"achievements":[]}""") }
        )

        val started = xbox.startDeviceCode(0L)
        val tokens = (xbox.pollDeviceCode(started, 0L) as DevicePoll.Done).tokens
        xbox.refresh(tokens, 0L)
        val derived = xbox.session(tokens.access)
        val list = xbox.titleHistory(derived)
        xbox.minutesPlayed(derived, list.titles)
        xbox.achievements(derived, "1915865634", sourceVersion = 2)
        xbox.achievements(derived, "1480000001", sourceVersion = 1)

        val gets = transport.calls.filter { it.method == "GET" }.map { it.url }
        assertEquals(4, gets.size)
        assertTrue(gets.all { it.startsWith("https://titlehub.xboxlive.com/") || it.startsWith("https://achievements.xboxlive.com/") })
        assertEquals(setOf(userAuth, xsts, stats), transport.calls.filter { it.method == "JSON" }.map { it.url }.toSet())
        assertEquals(setOf("$login/devicecode", "$login/token"), transport.calls.filter { it.method == "FORM" }.map { it.url }.toSet())
        assertEquals(setOf("GET", "JSON", "FORM"), transport.calls.map { it.method }.toSet())
    }

    @Test
    fun `without an app in the build nothing is sent`() = runTest {
        val transport = FakeTransport(emptyList())
        val xbox = XboxClient(transport, "")

        assertFalse(xbox.configured)
        assertEquals("No Xbox app in this build.", expect(XboxException.Kind.NOT_CONFIGURED) { xbox.startDeviceCode(0L) }.message)
        expect(XboxException.Kind.NOT_CONFIGURED) { xbox.pollDeviceCode(code, 0L) }
        expect(XboxException.Kind.NOT_CONFIGURED) { xbox.refresh(XboxTokens("OLD", 0L, "REFRESH", 0L), 0L) }
        assertTrue(transport.calls.isEmpty())
    }

    /** [block]'s XboxException, of [kind] when one is given. */
    private suspend fun expect(kind: XboxException.Kind?, block: suspend () -> Any?): XboxException {
        val error = try {
            block()
            null
        } catch (e: XboxException) {
            e
        } ?: throw AssertionError("expected an XboxException ${kind ?: ""}")
        if (kind != null) assertEquals(kind, error.kind)
        return error
    }
}
