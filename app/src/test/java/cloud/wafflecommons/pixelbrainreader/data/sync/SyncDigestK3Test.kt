package cloud.wafflecommons.pixelbrainreader.data.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SyncDigestK3Test {

    private val digest = SyncDigest(
        atMillis = 1_000L,
        added = listOf("Inbox/new.md"),
        modified = listOf("20_Projects/plan.md"),
        deleted = listOf("old.md"),
        beforeHead = "aaa",
        afterHead = "bbb"
    )

    @Test
    fun `the notes a sync brought or changed are marked, not the deleted ones`() {
        assertEquals(setOf("Inbox/new.md", "20_Projects/plan.md"), digest.brought)
    }

    @Test
    fun `a note's change carries both heads, and whether the sync added it`() {
        assertEquals(SyncChange("aaa", "bbb", 1_000L, isNew = false), digest.changeFor("20_Projects/plan.md"))
        assertEquals(SyncChange("aaa", "bbb", 1_000L, isNew = true), digest.changeFor("Inbox/new.md"))
        assertNull(digest.changeFor("old.md"))
        assertNull(digest.changeFor("untouched.md"))
    }

    @Test
    fun `a digest saved before 12_20 has no heads, so no note opens on it`() {
        assertNull(digest.copy(beforeHead = null).changeFor("20_Projects/plan.md"))
    }
}
