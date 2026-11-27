package cloud.wafflecommons.pixelbrainreader.data.games.psn

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class PsnClientTest {

    /** Answers by URL prefix and records what was asked (never a real network call). */
    private class FakeTransport(private val routes: List<Pair<String, (String) -> PsnHttpResponse>>) : PsnTransport {
        val calls = mutableListOf<Triple<String, Map<String, String>, Map<String, String>>>()

        override suspend fun get(url: String, headers: Map<String, String>): PsnHttpResponse {
            calls += Triple(url, headers, emptyMap())
            return answer(url)
        }

        override suspend fun postForm(url: String, headers: Map<String, String>, form: Map<String, String>): PsnHttpResponse {
            calls += Triple(url, headers, form)
            return answer(url)
        }

        private fun answer(url: String) = routes.firstOrNull { url.startsWith(it.first) }?.second?.invoke(url)
            ?: PsnHttpResponse(404, null, "")
    }

    private val npsso = "a".repeat(64)
    private val authorize = "https://ca.account.sony.com/api/authz/v3/oauth/authorize"
    private val token = "https://ca.account.sony.com/api/authz/v3/oauth/token"
    private val tokenOk = PsnHttpResponse(
        200, null,
        """{"access_token":"ACCESS","token_type":"bearer","expires_in":3599,"refresh_token":"REFRESH","refresh_token_expires_in":5183999,"id_token":"x"}"""
    )

    private fun client(vararg routes: Pair<String, (String) -> PsnHttpResponse>) = FakeTransport(routes.toList()).let { it to PsnClient(it, "client-id", "client-secret") }

    @Test
    fun `the NPSSO is taken alone or out of the page's answer, in a token-safe format`() {
        assertEquals(npsso, PsnClient.cleanNpsso("  $npsso \n"))
        assertEquals(npsso, PsnClient.cleanNpsso("""{ "npsso": "$npsso" }"""))
        // A format change at Sony (longer, base64url) still goes through: Sony is the judge.
        val longer = "Ab-_9".repeat(20)
        assertEquals(longer, PsnClient.cleanNpsso("""{"npsso":"$longer"}"""))
        assertNull(PsnClient.cleanNpsso("too-short"))
        assertNull(PsnClient.cleanNpsso("https://ca.account.sony.com/api/v1/ssocookie"))
        assertNull(PsnClient.cleanNpsso("""{"error":"invalid_grant","error_description":"not signed in"}"""))
        assertNull(PsnClient.cleanNpsso("two words " + npsso))
    }

    @Test
    fun `a refused paste is described, never echoed`() {
        val described = PsnClient.describeRefused("""{"error":"invalid_grant"}""")
        assertEquals("25 characters, JSON without an npsso field, with \" : _ { }", described)
        assertFalse(described.contains("invalid"))
    }

    @Test
    fun `sign-in reads the code from the redirect and keeps both lifetimes`() = runTest {
        val (transport, psn) = client(
            authorize to { _ -> PsnHttpResponse(302, "com.scee.psxandroid.scecompcall://redirect/?code=v3.CODE&cid=x", "") },
            token to { _ -> tokenOk }
        )
        val tokens = psn.signIn(npsso, now = 1_000L)
        assertEquals("ACCESS", tokens.access)
        assertEquals("REFRESH", tokens.refresh)
        assertEquals(1_000L + 3_599_000L, tokens.accessExpiresAt)
        assertEquals(1_000L + 5_183_999_000L, tokens.refreshExpiresAt)
        // The NPSSO only goes to Sony's sign-in, as a cookie; the code to the token step.
        val (authUrl, authHeaders, _) = transport.calls[0]
        assertTrue(authUrl.startsWith(authorize))
        assertEquals("npsso=$npsso", authHeaders["Cookie"])
        val (_, tokenHeaders, form) = transport.calls[1]
        assertEquals("v3.CODE", form["code"])
        assertEquals("authorization_code", form["grant_type"])
        assertTrue(tokenHeaders.getValue("Authorization").startsWith("Basic "))
        assertFalse(transport.calls.any { (_, h, f) -> npsso in f.values || h["Authorization"]?.contains(npsso) == true })
    }

    @Test
    fun `an expired NPSSO and a refused client are told apart`() = runTest {
        val (_, expired) = client(authorize to { _ -> PsnHttpResponse(302, "com.scee.psxandroid.scecompcall://redirect/?error=login_required&error_code=4165", "") })
        expect(PsnException.Kind.NPSSO_REJECTED) { expired.signIn(npsso, 0L) }

        val (_, refused) = client(
            authorize to { _ -> PsnHttpResponse(302, "com.scee.psxandroid.scecompcall://redirect/?code=v3.CODE", "") },
            token to { _ -> PsnHttpResponse(401, null, """{"error":"invalid_client","error_description":"Bad client credentials","error_code":4102}""") }
        )
        expect(PsnException.Kind.CLIENT_REJECTED) { refused.signIn(npsso, 0L) }
    }

    @Test
    fun `a refresh keeps the old refresh token when Sony sends none, and an expired one asks for a new NPSSO`() = runTest {
        val (_, psn) = client(token to { _ -> PsnHttpResponse(200, null, """{"access_token":"NEW","expires_in":3599}""") })
        val old = PsnTokens("OLD", 0L, "REFRESH", 99L)
        val fresh = psn.refresh(old, now = 10L)
        assertEquals("NEW", fresh.access)
        assertEquals("REFRESH", fresh.refresh)
        assertEquals(99L, fresh.refreshExpiresAt)

        val (_, gone) = client(token to { _ -> PsnHttpResponse(400, null, """{"error":"invalid_grant","error_code":4192}""") })
        expect(PsnException.Kind.TOKEN_EXPIRED) { gone.refresh(old, 10L) }
    }

    @Test
    fun `played games are read page by page with the access token`() = runTest {
        val first = """{"titles":[{"titleId":"PPSA1","name":"One","category":"ps5_native_game","playDuration":"PT2H","playCount":3}],"totalItemCount":2,"nextOffset":1}"""
        val second = """{"titles":[{"titleId":"CUSA2","name":"Two","category":"ps4_game","playDuration":"PT30M","unknownField":true}],"totalItemCount":2}"""
        val (transport, psn) = client(
            "https://m.np.playstation.com/api/gamelist/v2/users/me/titles" to { url ->
                PsnHttpResponse(200, null, if (url.contains("offset=0")) first else second)
            }
        )
        val games = psn.playedGames("ACCESS")
        assertEquals(listOf("PPSA1", "CUSA2"), games.map { it.titleId })
        assertEquals("PT30M", games[1].playDuration)
        assertTrue(transport.calls.all { it.second["Authorization"] == "Bearer ACCESS" && it.second["Accept-Language"] == "en-US" })
    }

    @Test
    fun `an API 401 says the access token was refused, a 429 says wait`() = runTest {
        val (_, refused) = client("https://m.np.playstation.com" to { _ -> PsnHttpResponse(401, null, "{}") })
        expect(PsnException.Kind.TOKEN_EXPIRED) { refused.trophyTitles("ACCESS") }
        val (_, busy) = client("https://m.np.playstation.com" to { _ -> PsnHttpResponse(429, null, "") })
        expect(PsnException.Kind.RATE_LIMITED) { busy.trophyTitles("ACCESS") }
    }

    @Test
    fun `without a client in the build nothing is sent`() = runTest {
        val transport = FakeTransport(emptyList())
        val psn = PsnClient(transport, "", "")
        assertFalse(psn.configured)
        expect(PsnException.Kind.NOT_CONFIGURED) { psn.signIn(npsso, 0L) }
        assertTrue(transport.calls.isEmpty())
    }

    @Test
    fun `a status survives its encoding`() {
        val paused = PsnStatus(false, 42L, PsnException.Kind.TOKEN_EXPIRED, "The sign-in has expired | paste a new NPSSO")
        assertEquals(paused, PsnStatus.decode(paused.encode()))
        assertTrue(paused.needsOwner)
        assertTrue(paused.needsNpsso)
        assertEquals(PsnStatus(true, 7L), PsnStatus.decode("ok|7"))
        assertNull(PsnStatus.decode("garbage"))
    }

    @Test
    fun `a refused client needs the owner, but not a new NPSSO`() {
        val refused = PsnStatus(false, 42L, PsnException.Kind.CLIENT_REJECTED, "PlayStation refused this build's client id or secret (4102)")
        assertTrue(refused.needsOwner)
        assertFalse(refused.needsNpsso)
        assertTrue(PsnStatus(false, 42L, PsnException.Kind.NPSSO_REJECTED, "").needsNpsso)
        assertFalse(PsnStatus(false, 42L, PsnException.Kind.NETWORK, "").needsNpsso)
        assertFalse(PsnStatus(true, 42L).needsNpsso)
    }

    private suspend fun expect(kind: PsnException.Kind, block: suspend () -> Unit) {
        try {
            block()
            fail("expected $kind")
        } catch (e: PsnException) {
            assertEquals(kind, e.kind)
        }
    }
}
