package cloud.wafflecommons.pixelbrainreader.data.privatevault

import cloud.wafflecommons.pixelbrainreader.data.local.security.CryptoManager
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The private journal's session (12.18, V3): the only readable copy of the password, in memory. */
class PrivateVaultSessionTest {

    private val session = PrivateVaultSession(CryptoManager())
    private val password = "correct horse battery".toCharArray()

    @Test
    fun `nothing reads the password before an unlock or after a lock`() = runTest {
        assertFalse(session.isOpen())
        assertNull(session.password())
        assertNull(session.key())
        try {
            session.withPassword { fail("a locked session handed its password out") }
        } catch (e: PrivateVaultLockedException) {
            assertEquals("Unlock the private journal first.", e.message)
        }

        session.open(password)
        assertTrue(session.unlocked.value)
        assertArrayEquals(password, session.password())
        session.lock()
        assertFalse(session.unlocked.value)
        assertNull(session.password())
    }

    @Test
    fun `the session keeps its own copy and hands out copies`() {
        val typed = password.copyOf()
        session.open(typed)
        typed.fill('x') // the caller wipes what it typed
        val copy = session.password()!!
        assertArrayEquals(password, copy)
        copy.fill('y') // and a reader wipes what it read
        assertArrayEquals(password, session.password())
    }

    @Test
    fun `a locked encrypted log says what to do`() = runTest {
        try {
            session.withPassword(locked = { PrivateVaultLockedException("Unlock the private journal to use the encrypted log.") }) { }
            fail("expected the locked message")
        } catch (e: PrivateVaultLockedException) {
            assertEquals("Unlock the private journal to use the encrypted log.", e.message)
        }
    }

    @Test
    fun `a save asked for just before a lock still lands with its key`() = runTest {
        session.open(password)
        val key = session.key()!!
        session.lock()
        val written = session.writeWith(key) { it.concatToString() }
        assertEquals(password.concatToString(), written)
        key.wipe()
    }
}
