package cloud.wafflecommons.pixelbrainreader.data.privacy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrafficAccountsTest {

    private var sent = 0L
    private var received = 0L
    private var counting = true
    private var clock = 0L
    private val accounts = TrafficAccounts({ if (counting) TrafficCount(sent, received) else null }, { clock })

    @Test
    fun `a call takes what the phone counted while it ran`() {
        val call = accounts.begin("github.com", NetPurpose.SYNC)
        sent += 100
        received += 1_000
        assertEquals(ClosedCall("github.com", NetPurpose.SYNC, 100, 1_000, measured = true), accounts.end(call))
    }

    @Test
    fun `calls at the same moment share it, and nothing is counted twice`() {
        val a = accounts.begin("github.com", NetPurpose.SYNC)
        sent += 100 // a alone
        val b = accounts.begin("api.open-meteo.com", NetPurpose.WEATHER)
        sent += 50 // shared
        val first = accounts.end(a)!!
        sent += 30 // b alone
        val second = accounts.end(b)!!
        assertEquals(125L, first.sent)
        assertEquals(55L, second.sent)
        assertEquals(180L, first.sent + second.sent)
    }

    @Test
    fun `what doesn't divide goes to the first open call`() {
        val calls = (1..3).map { accounts.begin("host$it", NetPurpose.OTHER) }
        received += 10
        val shares = calls.map { accounts.end(it)!!.received }
        assertEquals(10L, shares.sum())
        assertEquals(4L, shares.first())
    }

    @Test
    fun `traffic with no call open is the unnamed pool, taken once`() {
        sent += 40
        received += 7
        accounts.end(accounts.begin("github.com", NetPurpose.SYNC))
        assertEquals(TrafficCount(40, 7), accounts.take().first)
        assertEquals(TrafficCount(0, 0), accounts.take().first)
    }

    @Test
    fun `a phone that can't count leaves the calls counted without bytes`() {
        counting = false
        val accountsOff = TrafficAccounts({ null }, { clock })
        val call = accountsOff.begin("github.com", NetPurpose.SYNC)
        val closed = accountsOff.end(call)!!
        assertFalse(closed.measured)
        assertEquals(0L, closed.sent)
        assertFalse(accountsOff.measured)
    }

    @Test
    fun `an http call never reported as ended closes after ten minutes`() {
        val leaked = accounts.begin("api.open-meteo.com", NetPurpose.WEATHER, expires = true)
        val kept = accounts.begin("github.com", NetPurpose.SYNC)
        sent += 20
        clock += TrafficAccounts.MAX_OPEN_MS + 1
        val (_, late) = accounts.take()
        assertEquals(listOf("api.open-meteo.com"), late.map { it.host })
        assertEquals(10L, late.single().sent)
        assertNull(accounts.end(leaked))
        sent += 6
        assertEquals(16L, accounts.end(kept)!!.sent)
        assertTrue(accounts.take().second.isEmpty())
    }

    @Test
    fun `a counter that goes backwards adds nothing`() {
        sent = 1_000
        val fresh = TrafficAccounts({ TrafficCount(sent, received) }, { clock })
        val call = fresh.begin("github.com", NetPurpose.SYNC)
        sent = 10
        assertEquals(0L, fresh.end(call)!!.sent)
    }
}
