package cloud.wafflecommons.pixelbrainreader.ui.settings

import cloud.wafflecommons.pixelbrainreader.data.local.security.SecretManager
import cloud.wafflecommons.pixelbrainreader.ui.cancelAndAwait
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch

/** Settings › Vault & device › This device & version (13.4, S-i): the Vault row never says "none" before it knows. */
@OptIn(ExperimentalCoroutinesApi::class)
class DeviceViewModelTest {

    private var viewModel: DeviceViewModel? = null

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        viewModel?.cancelAndAwait()
        Dispatchers.resetMain()
    }

    private fun viewModel(secrets: SecretManager) = DeviceViewModel(secrets).also { viewModel = it }

    /** The row once the read is done (it runs on a real thread). */
    private suspend fun DeviceViewModel.settled(): DeviceVault =
        withContext(Dispatchers.Default) { withTimeout(5_000) { vault.first { it != DeviceVault.Reading } } }

    @Test
    fun `the row reads Reading until the repository answers, then names it`() = runBlocking {
        val answer = CountDownLatch(1)
        val vm = viewModel(mockk { every { getRepoInfo() } answers { answer.await(); "owner" to "vault" } })

        assertEquals(DeviceVault.Reading, vm.vault.value)
        answer.countDown()
        assertEquals(DeviceVault.Saved("owner/vault"), vm.settled())
    }

    @Test
    fun `no repository saved is said once the read found none`() = runBlocking {
        val vm = viewModel(mockk { every { getRepoInfo() } returns (null to " ") })

        assertEquals(DeviceVault.None, vm.settled())
    }

    @Test
    fun `a read that fails is not no repository saved`() = runBlocking {
        val vm = viewModel(mockk { every { getRepoInfo() } throws IllegalStateException("keystore") })

        assertEquals(DeviceVault.Unreadable, vm.settled())
    }
}
