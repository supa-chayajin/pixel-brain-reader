package cloud.wafflecommons.pixelbrainreader.data.workers

import android.content.Context
import androidx.work.WorkManager
import cloud.wafflecommons.pixelbrainreader.data.device.VaultWriteGate
import cloud.wafflecommons.pixelbrainreader.data.games.psn.PsnAccount
import cloud.wafflecommons.pixelbrainreader.data.games.xbox.XboxAccount
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.runs
import io.mockk.unmockkObject
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * The Games sync's two slots (13.3, XB6): one worker runs both consoles, so the slots stay while
 * either is connected (a PlayStation disconnect leaves Xbox's nightly sync in place) and go once
 * neither is; a read-only device never touches them.
 */
class GamesSyncSchedulerTest {

    private val context: Context = mockk(relaxed = true)
    private val workManager: WorkManager = mockk(relaxed = true)
    private val psn: PsnAccount = mockk {
        every { configured } returns true
        coEvery { isConnected() } returns false
    }
    private val xbox: XboxAccount = mockk {
        every { configured } returns true
        coEvery { isConnected() } returns false
    }
    private val scheduler = GamesSyncScheduler(context, psn, xbox)

    @Before
    fun setUp() {
        VaultWriteGate.readOnly = false
        mockkStatic(WorkManager::class)
        every { WorkManager.getInstance(any()) } returns workManager
        mockkObject(GamesSyncWorker.Companion)
        coEvery { GamesSyncWorker.ensureScheduled(any()) } just runs
        every { GamesSyncWorker.cancel(any()) } just runs
    }

    @After
    fun tearDown() {
        unmockkObject(GamesSyncWorker.Companion)
        unmockkStatic(WorkManager::class)
        VaultWriteGate.readOnly = false
    }

    @Test
    fun `PlayStation connected keeps both slots`() = runTest {
        coEvery { psn.isConnected() } returns true

        scheduler.update()

        coVerify(exactly = 1) { GamesSyncWorker.ensureScheduled(workManager) }
        verify(exactly = 0) { GamesSyncWorker.cancel(any()) }
    }

    @Test
    fun `Xbox connected alone keeps both slots, so a PlayStation disconnect leaves them`() = runTest {
        coEvery { xbox.isConnected() } returns true

        scheduler.update()

        coVerify(exactly = 1) { GamesSyncWorker.ensureScheduled(workManager) }
        verify(exactly = 0) { GamesSyncWorker.cancel(any()) }
    }

    @Test
    fun `neither console connected cancels both slots`() = runTest {
        scheduler.update()

        verify(exactly = 1) { GamesSyncWorker.cancel(workManager) }
        coVerify(exactly = 0) { GamesSyncWorker.ensureScheduled(any()) }
    }

    @Test
    fun `a console this build has no app for counts as not connected`() = runTest {
        every { xbox.configured } returns false
        coEvery { xbox.isConnected() } returns true

        scheduler.update()

        verify(exactly = 1) { GamesSyncWorker.cancel(workManager) }
        coVerify(exactly = 0) { GamesSyncWorker.ensureScheduled(any()) }
    }

    @Test
    fun `a read-only device touches nothing`() = runTest {
        VaultWriteGate.readOnly = true
        coEvery { psn.isConnected() } returns true
        coEvery { xbox.isConnected() } returns true

        scheduler.update()

        verify(exactly = 0) { WorkManager.getInstance(any()) }
        coVerify(exactly = 0) { GamesSyncWorker.ensureScheduled(any()) }
        verify(exactly = 0) { GamesSyncWorker.cancel(any()) }
        coVerify(exactly = 0) { psn.isConnected() }
        coVerify(exactly = 0) { xbox.isConnected() }
    }
}
