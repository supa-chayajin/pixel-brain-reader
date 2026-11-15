package cloud.wafflecommons.pixelbrainreader.data.privatevault

import cloud.wafflecommons.pixelbrainreader.data.local.security.CryptoManager
import cloud.wafflecommons.pixelbrainreader.data.repository.UserPreferencesRepository
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
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

    private val preferences: UserPreferencesRepository = mockk {
        every { privateAutoLockMinutes } returns flowOf(5)
    }

    // The auto-lock's timer runs in this scope; its virtual clock never advances here.
    private val session = PrivateVaultSession(CryptoManager(), preferences, TestScope())
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
    fun `set to lock at once, the journal leaving the screen locks it`() {
        val atOnce = PrivateVaultSession(
            CryptoManager(),
            mockk { every { privateAutoLockMinutes } returns flowOf(0) },
            TestScope(UnconfinedTestDispatcher())
        )
        atOnce.hold()
        atOnce.open(password)
        assertTrue("on screen, nothing counts down", atOnce.isOpen())
        atOnce.release()
        assertFalse(atOnce.isOpen())
        assertFalse(atOnce.unlocked.value)
    }

    @Test
    fun `nobody gets the password while it changes`() {
        session.open(password)
        session.beginChange()
        assertNull(session.password())
        assertNull(session.key())
        assertFalse(session.isOpen())
        session.endChange(null) // the change failed: the session is as it was
        assertArrayEquals(password, session.password())
    }

    @Test
    fun `a save taken before a change of password writes with the new one, and never with the old`() = runTest {
        session.open(password)
        val key = session.key()!!
        session.beginChange()
        session.endChange("a brand new password".toCharArray())
        assertEquals("a brand new password", session.writeWith(key) { it.concatToString() })

        session.lock()
        try {
            session.writeWith(key) { fail("the old password wrote after the change") }
            fail("expected the locked session to refuse")
        } catch (e: PrivateVaultLockedException) {
            // nothing may write with the old password once the journal is locked
        }
        key.wipe()
    }

    @Test
    fun `a lock during a change stays locked`() {
        session.open(password)
        session.beginChange()
        session.lock()
        session.endChange("a brand new password".toCharArray())
        assertFalse(session.isOpen())
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
