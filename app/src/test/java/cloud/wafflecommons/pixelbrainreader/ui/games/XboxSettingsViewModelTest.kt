package cloud.wafflecommons.pixelbrainreader.ui.games

import cloud.wafflecommons.pixelbrainreader.data.device.VaultWriteGate
import cloud.wafflecommons.pixelbrainreader.data.games.xbox.XboxAccount
import cloud.wafflecommons.pixelbrainreader.data.games.xbox.XboxDeviceCode
import cloud.wafflecommons.pixelbrainreader.data.games.xbox.XboxException
import cloud.wafflecommons.pixelbrainreader.data.games.xbox.XboxStatus
import cloud.wafflecommons.pixelbrainreader.data.games.xbox.XboxSync
import cloud.wafflecommons.pixelbrainreader.data.games.xbox.XboxSyncSummary
import cloud.wafflecommons.pixelbrainreader.data.games.xbox.XboxTokens
import cloud.wafflecommons.pixelbrainreader.data.workers.GamesSyncScheduler
import cloud.wafflecommons.pixelbrainreader.ui.cancelAndAwait
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Settings › Xbox (13.3, XB-E): Connect by device code, the first sync, Cancel and Disconnect, over a mocked account. */
@OptIn(ExperimentalCoroutinesApi::class)
class XboxSettingsViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private val statuses = MutableStateFlow<XboxStatus?>(null)
    private val account: XboxAccount = mockk()
    private val xboxSync: XboxSync = mockk()
    private val scheduler: GamesSyncScheduler = mockk()
    private var viewModel: XboxSettingsViewModel? = null

    // Invented values: no real code or token ever appears in a test.
    private val code = XboxDeviceCode(userCode = "ABCD1234", deviceCode = "fake-device-code", expiresAt = 1_800_000_000_000L, intervalSeconds = 5)
    private val tokens = XboxTokens(access = "fake-access", accessExpiresAt = 1_800_003_600_000L, refresh = "fake-refresh", refreshIssuedAt = 1_800_000_000_000L)

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        VaultWriteGate.readOnly = false
        every { account.configured } returns true
        every { account.status } returns statuses
        coEvery { account.tokens() } returns null
        coEvery { account.isConnected() } returns false
        coEvery { account.disconnect() } just runs
        coEvery { scheduler.update() } just runs
    }

    @After
    fun tearDown() {
        viewModel?.cancelAndAwait(testDispatcher.scheduler)
        Dispatchers.resetMain()
        VaultWriteGate.readOnly = false
    }

    private fun viewModel() = XboxSettingsViewModel(account, xboxSync, scheduler).also { viewModel = it }

    @Test
    fun `connect shows the code, then runs the first sync and schedules`() = runTest(testDispatcher) {
        val signedIn = CompletableDeferred<Result<Unit>>()
        coEvery { account.startSignIn(any()) } returns Result.success(code)
        coEvery { account.awaitSignIn(any(), any()) } coAnswers { signedIn.await() }
        val firstSync = XboxSyncSummary(titles = 12, sessions = 0, achievementSets = 3, pages = 3, pagesCreated = 2)
        val synced = CompletableDeferred<Result<XboxSyncSummary>>()
        coEvery { xboxSync.sync(any(), any(), any()) } coAnswers { synced.await() }
        val vm = viewModel()
        advanceUntilIdle()

        vm.connect()
        advanceUntilIdle()

        // Waiting at microsoft.com/link: the code shows, nothing scheduled or synced yet.
        assertEquals(PendingCode("ABCD1234", code.expiresAt), vm.state.value.code)
        assertTrue(vm.state.value.busy)
        coVerify(exactly = 0) { scheduler.update() }
        coVerify(exactly = 0) { xboxSync.sync(any(), any(), any()) }

        coEvery { account.tokens() } returns tokens
        signedIn.complete(Result.success(Unit))
        advanceUntilIdle()

        // Microsoft answered: Connected at once, while the first sync reads the games (no Connect card meanwhile).
        val reading = vm.state.value
        assertNull(reading.code)
        assertTrue(reading.connected)
        assertTrue(reading.busy)
        assertEquals("Signed in. The first sync is reading your Xbox games…", reading.message)
        // No sign-in was kept before this Connect: the first sync is a baseline.
        coVerify(exactly = 1) { xboxSync.sync(any(), any(), true) }

        synced.complete(Result.success(firstSync))
        advanceUntilIdle()

        val state = vm.state.value
        assertNull(state.code)
        assertFalse(state.busy)
        assertTrue(state.connected)
        assertEquals("Connected: 12 games found. Play time counts from now on. 2 game pages created.", state.message)
        // The sign-in lasts 90 days from its last renewal.
        assertEquals(tokens.refreshIssuedAt + 90L * 86_400_000L, state.validUntil)
        coVerifyOrder {
            account.isConnected()
            account.startSignIn(any())
            account.awaitSignIn(code, any())
            scheduler.update()
            xboxSync.sync(any(), any(), true)
        }
    }

    @Test
    fun `signing in again over an expired sign-in keeps the play since the last sync`() = runTest(testDispatcher) {
        // The sign-in expired, its tokens still kept: Sign in again renews it, it is no new account.
        coEvery { account.tokens() } returns tokens
        coEvery { account.isConnected() } returns true
        statuses.value = XboxStatus(ok = false, atMillis = 1L, kind = XboxException.Kind.SIGN_IN_EXPIRED, message = "The Xbox sign-in has expired: Connect again.")
        coEvery { account.startSignIn(any()) } returns Result.success(code)
        coEvery { account.awaitSignIn(any(), any()) } returns Result.success(Unit)
        val summary = XboxSyncSummary(titles = 12, sessions = 2, achievementSets = 3, pages = 3, pagesCreated = 0)
        coEvery { xboxSync.sync(any(), any(), any()) } returns Result.success(summary)
        val vm = viewModel()
        advanceUntilIdle()

        vm.connect()
        advanceUntilIdle()

        coVerify(exactly = 1) { xboxSync.sync(any(), any(), false) }
        coVerify(exactly = 0) { xboxSync.sync(any(), any(), true) }
        assertEquals("Signed in again. Synced 12 games: 2 new sessions, 3 achievement lists updated.", vm.state.value.message)
        assertTrue(vm.state.value.connected)
        assertFalse(vm.state.value.busy)
    }

    @Test
    fun `a declined sign-in says so and schedules nothing`() = runTest(testDispatcher) {
        coEvery { account.startSignIn(any()) } returns Result.success(code)
        val declined = XboxException(XboxException.Kind.ACCOUNT_REFUSED, "The sign-in was declined at microsoft.com/link.")
        coEvery { account.awaitSignIn(any(), any()) } returns Result.failure(declined)
        val vm = viewModel()
        advanceUntilIdle()

        vm.connect()
        advanceUntilIdle()

        assertEquals("The sign-in was declined at microsoft.com/link.", vm.state.value.message)
        assertNull(vm.state.value.code)
        assertFalse(vm.state.value.busy)
        assertFalse(vm.state.value.connected)
        coVerify(exactly = 0) { scheduler.update() }
        coVerify(exactly = 0) { xboxSync.sync(any(), any(), any()) }
    }

    @Test
    fun `a refused code request says why and shows no code`() = runTest(testDispatcher) {
        val refused = XboxException(XboxException.Kind.CLIENT_REJECTED, "Microsoft refused this build's app (AADSTS7000218).")
        coEvery { account.startSignIn(any()) } returns Result.failure(refused)
        val vm = viewModel()
        advanceUntilIdle()

        vm.connect()
        advanceUntilIdle()

        assertEquals("Microsoft refused this build's app (AADSTS7000218).", vm.state.value.message)
        assertNull(vm.state.value.code)
        assertFalse(vm.state.value.busy)
        coVerify(exactly = 0) { account.awaitSignIn(any(), any()) }
        coVerify(exactly = 0) { scheduler.update() }
    }

    @Test
    fun `cancel stops the wait`() = runTest(testDispatcher) {
        coEvery { account.startSignIn(any()) } returns Result.success(code)
        coEvery { account.awaitSignIn(any(), any()) } coAnswers { awaitCancellation() }
        val vm = viewModel()
        advanceUntilIdle()
        vm.connect()
        advanceUntilIdle()
        assertEquals("ABCD1234", vm.state.value.code?.userCode)

        vm.cancelConnect()
        advanceUntilIdle()

        assertNull(vm.state.value.code)
        assertFalse(vm.state.value.busy)
        assertFalse(vm.state.value.connected)
        coVerify(exactly = 0) { xboxSync.sync(any(), any(), any()) }
        // Nothing was saved before the cancel: nothing to schedule.
        coVerify(exactly = 0) { scheduler.update() }

        // Connect can start again.
        vm.connect()
        advanceUntilIdle()
        assertEquals("ABCD1234", vm.state.value.code?.userCode)
        coVerify(exactly = 2) { account.startSignIn(any()) }
    }

    @Test
    fun `sync now says what it read`() = runTest(testDispatcher) {
        coEvery { account.tokens() } returns tokens
        val summary = XboxSyncSummary(titles = 12, sessions = 2, achievementSets = 3, pages = 4, pagesCreated = 1)
        coEvery { xboxSync.sync(any(), any(), any()) } returns Result.success(summary)
        val vm = viewModel()
        advanceUntilIdle()

        vm.syncNow()
        advanceUntilIdle()

        assertEquals("Synced 12 games: 2 new sessions, 3 achievement lists updated. 1 game page created.", vm.state.value.message)
        assertFalse(vm.state.value.busy)

        val expired = XboxException(XboxException.Kind.SIGN_IN_EXPIRED, "The Xbox sign-in has expired: Connect again.")
        coEvery { xboxSync.sync(any(), any(), any()) } returns Result.failure(expired)
        vm.syncNow()
        advanceUntilIdle()

        assertEquals("The Xbox sign-in has expired: Connect again.", vm.state.value.message)
    }

    @Test
    fun `disconnect updates the schedule`() = runTest(testDispatcher) {
        coEvery { account.tokens() } returns tokens
        val vm = viewModel()
        advanceUntilIdle()
        assertTrue(vm.state.value.connected)

        coEvery { account.tokens() } returns null
        vm.disconnect()
        advanceUntilIdle()

        // The sign-in goes first; the scheduler then keeps the slots only while PlayStation is connected.
        coVerifyOrder {
            account.disconnect()
            scheduler.update()
        }
        assertFalse(vm.state.value.connected)
        assertNull(vm.state.value.validUntil)
        assertEquals("Disconnected: Xbox no longer syncs.", vm.state.value.message)
    }

    @Test
    fun `a read-only device shows no code`() = runTest(testDispatcher) {
        VaultWriteGate.readOnly = true
        val vm = viewModel()
        advanceUntilIdle()
        assertTrue(vm.state.value.readOnly)

        vm.connect()
        advanceUntilIdle()

        assertNull(vm.state.value.code)
        assertFalse(vm.state.value.busy)
        coVerify(exactly = 0) { account.startSignIn(any()) }
        coVerify(exactly = 0) { account.awaitSignIn(any(), any()) }
        coVerify(exactly = 0) { scheduler.update() }
    }

    @Test
    fun `the status line follows the account`() = runTest(testDispatcher) {
        val vm = viewModel()
        advanceUntilIdle()
        assertNull(vm.state.value.status)

        val paused = XboxStatus(ok = false, atMillis = 1_800_000_000_000L, kind = XboxException.Kind.SIGN_IN_EXPIRED, message = "The Xbox sign-in has expired: Connect again.")
        statuses.value = paused
        advanceUntilIdle()

        assertEquals(paused, vm.state.value.status)
        assertTrue(vm.state.value.status!!.needsSignIn)
    }
}
