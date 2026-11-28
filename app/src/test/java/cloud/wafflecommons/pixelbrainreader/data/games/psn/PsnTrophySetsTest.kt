package cloud.wafflecommons.pixelbrainreader.data.games.psn

import cloud.wafflecommons.pixelbrainreader.data.games.PsnTitleState
import cloud.wafflecommons.pixelbrainreader.data.games.TrophyRecord
import cloud.wafflecommons.pixelbrainreader.data.games.TrophySetFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 13.4: every trophy list of a PSN title, asked once more for the titles kept with one list. */
class PsnTrophySetsTest {

    private fun played(vararg ids: String) = ids.map { PsnPlayedTitle(titleId = it, name = it) }

    private val before = mapOf(
        // Kept by 13.3: one list, looked up.
        "OLD" to PsnTitleState("OLD", "Glass Tower", trophySet = "NPWR00001_00", trophyLookedUp = true),
        // Kept by 13.4: its lists known.
        "DONE" to PsnTitleState("DONE", "Tin Orchard", trophySet = "NPWR00002_00", trophyLookedUp = true, trophySets = listOf("NPWR00002_00")),
        // Asked once: no trophies at all.
        "NONE" to PsnTitleState("NONE", "Lantern Bay", trophyLookedUp = true),
        // In the snapshot, never asked yet.
        "WAIT" to PsnTitleState("WAIT", "Coral Reef")
    )

    @Test
    fun `lookups go to never-asked titles first, then the old ones once`() {
        val ids = played("OLD", "DONE", "NEW", "NONE", "WAIT")
        assertEquals(listOf("NEW", "WAIT", "OLD"), PsnTrophySets.toLookUp(ids, before, 50))
        assertEquals(listOf("NEW", "WAIT"), PsnTrophySets.toLookUp(ids, before, 2))
        assertTrue(PsnTrophySets.toLookUp(played("DONE", "NONE"), before, 50).isEmpty())
        // What the answer gives: every list, the one kept before first.
        val looked = mapOf("OLD" to listOf(PsnTrophyTitle(npCommunicationId = "NPWR00009_00"), PsnTrophyTitle(npCommunicationId = "NPWR00001_00")))
        assertEquals(listOf("NPWR00001_00", "NPWR00009_00"), PsnTrophySets.setsOf("OLD", before["OLD"], looked))
        assertEquals(listOf("NPWR00005_00"), PsnTrophySets.setsOf("NEW", null, mapOf("NEW" to listOf(PsnTrophyTitle(npCommunicationId = "NPWR00005_00")))))
        // Not in the answer: what the snapshot kept, its one list included.
        assertEquals(listOf("NPWR00001_00"), PsnTrophySets.setsOf("OLD", before["OLD"], emptyMap()))
        assertEquals(listOf("NPWR00001_00"), PsnTrophySets.setsOf("OLD", before["OLD"], mapOf("OLD" to emptyList())))
        assertEquals(listOf("NPWR00002_00"), PsnTrophySets.setsOf("DONE", before["DONE"], emptyMap()))
        assertTrue(PsnTrophySets.setsOf("NONE", before["NONE"], emptyMap()).isEmpty())
        assertTrue(PsnTrophySets.setsOf("NEW", null, emptyMap()).isEmpty())
        // A title's lists, whichever app kept them.
        assertEquals(listOf("NPWR00001_00"), before.getValue("OLD").allTrophySets)
        assertEquals(listOf("A", "B"), PsnTitleState("X", "x", trophySet = "A", trophySets = listOf("A", "B")).allTrophySets)
        assertTrue(before.getValue("NONE").allTrophySets.isEmpty())
    }

    @Test
    fun `a list moves on a new version or another earned count`() {
        val saved = TrophySetFile(
            "NPWR00001_00", "trophy2", version = "01.00",
            trophies = listOf(TrophyRecord(1, earnedAt = "2026-11-02T20:00:00Z"), TrophyRecord(2))
        )
        val listed = PsnTrophyTitle(npCommunicationId = "NPWR00001_00", trophySetVersion = "01.00", earnedTrophies = PsnTrophyCounts(bronze = 1))
        assertFalse(PsnTrophySets.moved(saved, listed))
        assertTrue(PsnTrophySets.moved(saved, listed.copy(trophySetVersion = "01.10")))
        assertTrue(PsnTrophySets.moved(saved, listed.copy(earnedTrophies = PsnTrophyCounts(bronze = 1, gold = 1))))
        // Never saved, or saved empty: read.
        assertTrue(PsnTrophySets.moved(null, listed))
        assertTrue(PsnTrophySets.moved(saved.copy(trophies = emptyList()), listed))
        // PlayStation doesn't list it: only a missing or empty file moves.
        assertFalse(PsnTrophySets.moved(saved, null))
        assertTrue(PsnTrophySets.moved(null, null))
        // No version in the answer: the earned count decides.
        assertFalse(PsnTrophySets.moved(saved, listed.copy(trophySetVersion = null)))
    }
}
