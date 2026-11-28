package cloud.wafflecommons.pixelbrainreader.data.games.psn

import cloud.wafflecommons.pixelbrainreader.data.device.VaultWriteGate
import cloud.wafflecommons.pixelbrainreader.data.local.security.SecretManager
import cloud.wafflecommons.pixelbrainreader.data.repository.UserPreferencesRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The PlayStation sign-in's tokens (RFC-009 §7): refreshed when needed, and forgotten for good by a Disconnect or a log-out. */
@OptIn(ExperimentalCoroutinesApi::class)
class PsnAccountTest {

    private val now = 1_000_000L
    // Invented values: no real token ever appears in a test.
    private val tokens = PsnTokens("ACCESS", accessExpiresAt = now + 3_600_000L, refresh = "REFRESH", refreshExpiresAt = now + 60 * 86_400_000L)
    private val renewed = PsnTokens("ACCESS-2", accessExpiresAt = now + 3_600_000L, refresh = "REFRESH-2", refreshExpiresAt = now + 60 * 86_400_000L)

    /** The phone's encrypted storage as a store: what is saved is read back, a log-out clears it. */
    private var stored: PsnTokens? = tokens
    private val secrets: SecretManager = mockk(relaxUnitFun = true)
    private val preferences: UserPreferencesRepository = mockk(relaxUnitFun = true) {
        every { psnStatus } returns flowOf(null)
    }
    private val client: PsnClient = mockk {
        every { configured } returns true
    }
    private val account = PsnAccount(client, secrets, preferences)

    /** An invented NPSSO: the shape PsnClient.cleanNpsso accepts, no real value. */
    private val npsso = "npsso".repeat(13)

    @Before
    fun setUp() {
        every { secrets.getPsnTokens() } answers { stored }
        every { secrets.savePsnTokens(any()) } answers { stored = firstArg() }
        every { secrets.clearPsnTokens() } answers { stored = null }
        every { secrets.clear() } answers { stored = null }
    }

    @Test
    fun `a good access token is used as it is, one ending within a minute is refreshed and saved`() = runTest {
        assertEquals("ACCESS", account.accessToken(now))
        coVerify(exactly = 0) { client.refresh(any(), any()) }

        val late = now + 3_600_000L - 30_000L
        coEvery { client.refresh(tokens, late) } returns renewed
        assertEquals("ACCESS-2", account.accessToken(late))
        assertEquals(renewed, stored)

        // Nothing connected: said so, nothing asked.
        stored = null
        val error = runCatching { account.accessToken(now) }.exceptionOrNull() as PsnException
        assertEquals(PsnException.Kind.NOT_CONFIGURED, error.kind)
        coVerify(exactly = 1) { client.refresh(any(), any()) }
    }

    @Test
    fun `a disconnect during a refresh keeps the sign-in forgotten`() = runTest {
        val asked = CompletableDeferred<Unit>()
        val answer = CompletableDeferred<PsnTokens>()
        coEvery { client.refresh(tokens, now) } coAnswers {
            asked.complete(Unit)
            answer.await()
        }

        // The 23:40 sync refreshes the token while PlayStation takes its time…
        val refreshing = async { runCatching { account.accessToken(now, force = true) } }
        asked.await()
        // …and the owner taps Disconnect meanwhile.
        val disconnecting = launch { account.disconnect() }
        runCurrent()
        answer.complete(renewed)
        refreshing.await()
        disconnecting.join()

        assertNull(stored)
        assertFalse(account.isConnected())
        coVerify { preferences.setPsnStatus(null) }
    }

    @Test
    fun `a log-out during a refresh saves nothing`() = runTest {
        val asked = CompletableDeferred<Unit>()
        val answer = CompletableDeferred<PsnTokens>()
        coEvery { client.refresh(tokens, now) } coAnswers {
            asked.complete(Unit)
            answer.await()
        }

        val refreshing = async { runCatching { account.accessToken(now, force = true) } }
        asked.await()
        // A log-out clears the phone's secrets without going through the account.
        secrets.clear()
        answer.complete(renewed)

        val error = refreshing.await().exceptionOrNull() as PsnException
        assertEquals(PsnException.Kind.NOT_CONFIGURED, error.kind)
        assertEquals("PlayStation isn't connected.", error.message)
        assertNull(stored)
        verify(exactly = 0) { secrets.savePsnTokens(any()) }
    }

    @Test
    fun `a first sign-in marks its syncs as baselines before keeping the tokens`() = runTest {
        VaultWriteGate.readOnly = false
        // No sign-in kept: a first Connect, or one after Disconnect.
        stored = null
        coEvery { client.signIn(npsso, now) } returns tokens

        assertTrue(account.connect(npsso, now).isSuccess)

        assertEquals(tokens, stored)
        coVerifyOrder {
            preferences.setPsnBaselinePending(true)
            secrets.savePsnTokens(tokens)
        }
    }

    @Test
    fun `a new NPSSO over a sign-in still kept starts no baseline`() = runTest {
        VaultWriteGate.readOnly = false
        // The sign-in expired but its tokens are kept: the play since the last sync still counts.
        coEvery { client.signIn(npsso, now) } returns renewed

        assertTrue(account.connect(npsso, now).isSuccess)

        assertEquals(renewed, stored)
        coVerify(exactly = 0) { preferences.setPsnBaselinePending(any()) }
    }

    @Test
    fun `a sign-in clears the status line instead of writing ok`() = runTest {
        VaultWriteGate.readOnly = false
        stored = null
        coEvery { client.signIn(npsso, now) } returns tokens
        val lines = mutableListOf<String?>()
        coEvery { preferences.setPsnStatus(captureNullable(lines)) } returns Unit

        assertTrue(account.connect(npsso, now).isSuccess)

        // 13.4: no "ok" before a sync has run; the first sync's own outcome is the next line.
        assertEquals(listOf<String?>(null), lines)
        // A new NPSSO over a kept sign-in too.
        assertTrue(account.connect(npsso, now).isSuccess)
        assertEquals(listOf<String?>(null, null), lines)
    }

    @Test
    fun `the tokens never print`() {
        val printed = tokens.toString()
        assertFalse(printed.contains("ACCESS"))
        assertFalse(printed.contains("REFRESH"))
        assertTrue(printed.contains("accessExpiresAt=${tokens.accessExpiresAt}"))
    }
}
