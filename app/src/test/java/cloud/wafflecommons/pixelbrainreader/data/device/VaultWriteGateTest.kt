package cloud.wafflecommons.pixelbrainreader.data.device

import java.io.IOException
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The read-only device's one switch (VaultWriteGate) and the login's repository match (LocalCopyReset). */
class VaultWriteGateTest {

    @After
    fun tearDown() {
        VaultWriteGate.readOnly = false
    }

    @Test
    fun `a writing device passes the gate`() {
        VaultWriteGate.readOnly = false
        VaultWriteGate.check()
        assertFalse(VaultWriteGate.refuses())
    }

    @Test
    fun `a read-only device is refused with an IOException callers already handle`() {
        VaultWriteGate.readOnly = true
        try {
            VaultWriteGate.check()
            fail("check() must throw on a read-only device")
        } catch (e: IOException) {
            assertTrue(e is ReadOnlyDeviceException)
        }
        assertTrue(VaultWriteGate.refuses())
    }

    @Test
    fun `a refusal tells the screens, a writing device never does`() = runTest(UnconfinedTestDispatcher()) {
        val told = mutableListOf<Unit>()
        val screens = launch { VaultWriteGate.refusals.collect { told += it } }

        VaultWriteGate.readOnly = false
        VaultWriteGate.refuses()
        runCurrent()
        assertEquals(0, told.size)

        VaultWriteGate.readOnly = true
        VaultWriteGate.refuses()
        runCurrent()
        assertEquals(1, told.size)
        screens.cancel()
    }

    @Test
    fun `a refused write is recognised under its wrappers`() {
        val wrapped = RuntimeException("save failed", IllegalStateException("io", ReadOnlyDeviceException()))
        assertTrue(VaultWriteGate.isRefusal(wrapped))
        assertFalse(VaultWriteGate.isRefusal(IOException("disk full")))
    }

    @Test
    fun `the same repository whatever its spelling`() {
        val url = "https://github.com/owner/vault.git"
        assertTrue(LocalCopyReset.sameRepository(url, "https://github.com/owner/vault"))
        assertTrue(LocalCopyReset.sameRepository(url, "https://GitHub.com/Owner/Vault.git/"))
        assertTrue(LocalCopyReset.sameRepository(" $url ", url))
        assertFalse(LocalCopyReset.sameRepository(url, "https://github.com/owner/vault-sandbox.git"))
        assertFalse(LocalCopyReset.sameRepository(url, "https://gitlab.com/owner/vault.git"))
        assertFalse(LocalCopyReset.sameRepository(null, url))
        assertEquals(false, LocalCopyReset.sameRepository(url, null))
    }
}
