package cloud.wafflecommons.pixelbrainreader.data.games.xbox

import cloud.wafflecommons.pixelbrainreader.data.device.VaultWriteGate
import cloud.wafflecommons.pixelbrainreader.data.local.security.SecretManager
import cloud.wafflecommons.pixelbrainreader.data.repository.UserPreferencesRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The Xbox sign-in: the device code's wait, the tokens kept, the session derived (XB1, XB-D). */
@OptIn(ExperimentalCoroutinesApi::class)
class XboxAccountTest {

    private val now = 1_000_000L
    private val xuid = "2533274800000001"
    private val tokens = XboxTokens("ACCESS", accessExpiresAt = now + 3_600_000L, refresh = "REFRESH", refreshIssuedAt = 0L)
    private val renewed = XboxTokens("ACCESS-2", accessExpiresAt = now + 3_600_000L, refresh = "REFRESH-2", refreshIssuedAt = now)
    private val code = XboxDeviceCode("ABCD-EFGH", "device-code-1", expiresAt = 900_000L, intervalSeconds = 5L)

    private val secrets: SecretManager = mockk(relaxUnitFun = true)
    private val preferences: UserPreferencesRepository = mockk(relaxUnitFun = true) {
        every { xboxStatus } returns flowOf(null)
    }
    private val client: XboxClient = mockk {
        every { configured } returns true
    }
    private val account = XboxAccount(client, secrets, preferences)

    private fun session(xsts: String, expiresAt: Long) = XboxSession("UHS", xsts, xuid, expiresAt)

    @Before
    fun setUp() {
        VaultWriteGate.readOnly = false
    }

    @After
    fun tearDown() {
        VaultWriteGate.readOnly = false
    }

    @Test
    fun `status encodes and decodes like PSN's`() {
        val paused = XboxStatus(false, 42L, XboxException.Kind.SIGN_IN_EXPIRED, "The Xbox sign-in has expired | Connect again")
        assertEquals("paused|SIGN_IN_EXPIRED|42|The Xbox sign-in has expired | Connect again", paused.encode())
        assertEquals(paused, XboxStatus.decode(paused.encode()))
        assertTrue(paused.needsOwner)
        assertTrue(paused.needsSignIn)
        assertEquals("ok|7", XboxStatus(true, 7L).encode())
        assertEquals(XboxStatus(true, 7L), XboxStatus.decode("ok|7"))
        assertNull(XboxStatus.decode("garbage"))
        assertNull(XboxStatus.decode(null))
        // A refused app or account needs the owner, but no new sign-in.
        val refused = XboxStatus(false, 1L, XboxException.Kind.CLIENT_REJECTED, "Microsoft refused this build's app (AADSTS7000218).")
        assertTrue(refused.needsOwner)
        assertFalse(refused.needsSignIn)
        assertTrue(XboxStatus(false, 1L, XboxException.Kind.ACCOUNT_REFUSED, "").needsOwner)
        assertFalse(XboxStatus(false, 1L, XboxException.Kind.NETWORK, "").needsOwner)
        assertFalse(XboxStatus(true, 1L).needsOwner)
        // A line break never splits the stored line.
        assertEquals("paused|API|3|two lines", XboxStatus(false, 3L, XboxException.Kind.API, "two\nlines").encode())
    }

    @Test
    fun `a read-only device can't start a sign-in`() = runTest {
        VaultWriteGate.readOnly = true

        val result = account.startSignIn(0L)

        assertEquals(XboxException.Kind.NOT_CONFIGURED, (result.exceptionOrNull() as XboxException).kind)
        coVerify(exactly = 0) { client.startDeviceCode(any()) }
        // Nor wait for one.
        assertTrue(account.awaitSignIn(code, clock = { 0L }).isFailure)
        coVerify(exactly = 0) { client.pollDeviceCode(any(), any()) }
        verify(exactly = 0) { secrets.saveXboxTokens(any()) }
    }

    @Test
    fun `a sign-in starts with Microsoft's code`() = runTest {
        coEvery { client.startDeviceCode(5L) } returns code
        assertEquals(code, account.startSignIn(5L).getOrThrow())

        coEvery { client.startDeviceCode(6L) } throws XboxException(XboxException.Kind.CLIENT_REJECTED, "Microsoft refused this build's app (AADSTS700016).")
        assertEquals(XboxException.Kind.CLIENT_REJECTED, (account.startSignIn(6L).exceptionOrNull() as XboxException).kind)
    }

    @Test
    fun `the wait polls at the interval, slows down when told, and saves the tokens`() = runTest {
        val times = mutableListOf<Long>()
        coEvery { client.pollDeviceCode(code, capture(times)) } returnsMany
            listOf(DevicePoll.Pending, DevicePoll.SlowDown, DevicePoll.Pending, DevicePoll.Done(tokens))

        val result = account.awaitSignIn(code, clock = { testScheduler.currentTime })

        assertTrue(result.isSuccess)
        // Every 5 s, then every 10 s once Microsoft asked to slow down.
        assertEquals(listOf(5_000L, 10_000L, 20_000L, 30_000L), times)
        verify { secrets.saveXboxTokens(tokens) }
        coVerify { preferences.setXboxStatus("ok|30000") }
    }

    @Test
    fun `a network error keeps waiting, an expired code stops`() = runTest {
        var polls = 0
        coEvery { client.pollDeviceCode(code, any()) } coAnswers {
            if (polls++ == 0) throw XboxException(XboxException.Kind.NETWORK, "No connection to the Xbox network.") else DevicePoll.Expired
        }

        val expired = account.awaitSignIn(code, clock = { testScheduler.currentTime }).exceptionOrNull() as XboxException

        assertEquals(XboxException.Kind.SIGN_IN_EXPIRED, expired.kind)
        assertEquals("The code expired: Connect again.", expired.message)
        assertEquals(2, polls)
        verify(exactly = 0) { secrets.saveXboxTokens(any()) }

        // A code that runs out while the owner never signs in.
        coEvery { client.pollDeviceCode(any(), any()) } returns DevicePoll.Pending
        val short = code.copy(expiresAt = testScheduler.currentTime + 12_000L)
        assertEquals(
            XboxException.Kind.SIGN_IN_EXPIRED,
            (account.awaitSignIn(short, clock = { testScheduler.currentTime }).exceptionOrNull() as XboxException).kind
        )
        coVerify(exactly = 3) { client.pollDeviceCode(short, any()) }

        // Declined at microsoft.com/link: the account refused, said in words.
        coEvery { client.pollDeviceCode(any(), any()) } returns DevicePoll.Declined
        val declined = account.awaitSignIn(code.copy(expiresAt = testScheduler.currentTime + 60_000L), clock = { testScheduler.currentTime })
            .exceptionOrNull() as XboxException
        assertEquals(XboxException.Kind.ACCOUNT_REFUSED, declined.kind)
        assertEquals("The sign-in was declined at microsoft.com/link.", declined.message)
    }

    @Test
    fun `the session renews an access token about to end and saves the rotated refresh token before using it`() = runTest {
        val ending = tokens.copy(accessExpiresAt = now + 30_000L)
        every { secrets.getXboxTokens() } returns ending
        coEvery { client.refresh(ending, now) } returns renewed
        val fresh = session("XSTS", now + 8 * 3_600_000L)
        coEvery { client.session("ACCESS-2") } returns fresh

        assertEquals(fresh, account.session(now))

        coVerifyOrder {
            client.refresh(ending, now)
            secrets.saveXboxTokens(renewed)
            client.session("ACCESS-2")
        }
        coVerify(exactly = 0) { client.session("ACCESS") }
    }

    @Test
    fun `the session is reused until 5 minutes before it ends, and force renews it`() = runTest {
        every { secrets.getXboxTokens() } returns tokens
        val first = session("XSTS-1", now + 3_600_000L)
        val second = session("XSTS-2", now + 7_200_000L)
        coEvery { client.session("ACCESS") } returnsMany listOf(first, second)

        assertEquals(first, account.session(now))
        // Reused, without a call, while more than 5 minutes are left.
        assertEquals(first, account.session(now + 3_600_000L - 5 * 60_000L - 1))
        coVerify(exactly = 1) { client.session(any()) }
        // 5 minutes before its end it is derived again; the access token is still good: no refresh.
        assertEquals(second, account.session(now + 3_600_000L - 5 * 60_000L))
        coVerify(exactly = 0) { client.refresh(any(), any()) }

        // Force renews it all: the access token too.
        val third = session("XSTS-3", now + 8 * 3_600_000L)
        coEvery { client.refresh(tokens, now) } returns renewed
        coEvery { client.session("ACCESS-2") } returns third
        assertEquals(third, account.session(now, force = true))
        verify { secrets.saveXboxTokens(renewed) }
    }

    @Test
    fun `a refused access token is renewed once, then it asks to sign in again`() = runTest {
        every { secrets.getXboxTokens() } returns tokens
        coEvery { client.refresh(tokens, now) } returns renewed
        coEvery { client.session("ACCESS") } throws XboxException(XboxException.Kind.TOKEN_EXPIRED, "Microsoft's access token was refused.")
        val good = session("XSTS", now + 8 * 3_600_000L)
        coEvery { client.session("ACCESS-2") } returns good

        assertEquals(good, account.session(now))
        coVerify(exactly = 1) { client.refresh(any(), any()) }
        verify { secrets.saveXboxTokens(renewed) }

        // Refused again after the renewal: the sign-in has ended.
        val other = XboxAccount(client, secrets, preferences)
        coEvery { client.session(any()) } throws XboxException(XboxException.Kind.TOKEN_EXPIRED, "Microsoft's access token was refused.")
        val error = runCatching { other.session(now) }.exceptionOrNull() as XboxException
        assertEquals(XboxException.Kind.SIGN_IN_EXPIRED, error.kind)
        coVerify(exactly = 2) { client.refresh(any(), any()) }
    }

    @Test
    fun `disconnect forgets the tokens and the session`() = runTest {
        every { secrets.getXboxTokens() } returns tokens
        coEvery { client.session("ACCESS") } returns session("XSTS", now + 8 * 3_600_000L)
        account.session(now)
        assertTrue(account.isConnected())

        account.disconnect()

        verify { secrets.clearXboxTokens() }
        coVerify { preferences.setXboxStatus(null) }
        every { secrets.getXboxTokens() } returns null
        assertFalse(account.isConnected())
        // The session in memory went with them: nothing is asked without a sign-in.
        val error = runCatching { account.session(now) }.exceptionOrNull() as XboxException
        assertEquals(XboxException.Kind.NOT_CONFIGURED, error.kind)
    }
}
