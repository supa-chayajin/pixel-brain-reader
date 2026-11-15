package cloud.wafflecommons.pixelbrainreader.data.privatevault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The journal's auto-lock (12.18, V2; owner's D2: 5 minutes after it leaves the screen). */
class AutoLockTest {

    private val minute = 60_000L
    private val delay = 5 * minute

    @Test
    fun `nothing counts down while the journal is on screen`() {
        val lock = AutoLock()
        assertFalse(lock.hold(now = 0))
        assertFalse(lock.expired(now = 24 * 60 * minute))
    }

    @Test
    fun `it locks five minutes after the journal leaves the screen`() {
        val lock = AutoLock()
        lock.hold(now = 0)
        assertEquals(minute + delay, lock.release(now = minute, delayMs = delay))
        assertFalse(lock.expired(now = minute + delay - 1))
        assertTrue(lock.expired(now = minute + delay))
    }

    @Test
    fun `a return in time keeps it open, a late return finds it expired`() {
        val lock = AutoLock()
        lock.hold(now = 0)
        lock.release(now = 0, delayMs = delay)
        assertFalse("back after 4 minutes", lock.hold(now = 4 * minute))
        lock.release(now = 10 * minute, delayMs = delay)
        assertTrue("back after 6 minutes", lock.hold(now = 16 * minute))
    }

    @Test
    fun `at once means the deadline is the moment it leaves`() {
        val lock = AutoLock()
        lock.hold(now = 0)
        val deadline = lock.release(now = 3 * minute, delayMs = 0)
        assertEquals(3 * minute, deadline)
        assertTrue(lock.expired(now = 3 * minute))
    }

    @Test
    fun `only the last screen to leave starts the countdown`() {
        val lock = AutoLock()
        lock.hold(now = 0)
        lock.hold(now = 0)
        assertNull(lock.release(now = minute, delayMs = delay))
        assertFalse(lock.expired(now = 60 * minute))
        assertEquals(2 * minute + delay, lock.release(now = 2 * minute, delayMs = delay))
    }

    @Test
    fun `an unlock with no screen holding it counts down at once, and a lock clears it`() {
        val lock = AutoLock()
        assertEquals(delay, lock.opened(now = 0, delayMs = delay))
        assertTrue(lock.expired(now = delay))
        lock.locked()
        assertFalse(lock.expired(now = 99 * minute))

        lock.hold(now = 0)
        assertNull("a screen holds it", lock.opened(now = 0, delayMs = delay))
    }

    @Test
    fun `a stored value the menu doesn't offer falls back to five minutes`() {
        assertEquals(5, AutoLock.minutesOrDefault(7))
        assertEquals(0, AutoLock.minutesOrDefault(0))
        assertEquals("At once", AutoLock.label(0))
        assertEquals("After 1 minute", AutoLock.label(1))
        assertEquals("After 15 minutes", AutoLock.label(15))
    }
}
