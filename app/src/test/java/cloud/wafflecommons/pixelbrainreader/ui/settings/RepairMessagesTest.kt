package cloud.wafflecommons.pixelbrainreader.ui.settings

import cloud.wafflecommons.pixelbrainreader.data.repository.VaultListFile
import org.junit.Assert.assertEquals
import org.junit.Test

/** What Repair says after "Write them back to the vault" (13.4, S7): never "Written back" for a write that failed. */
class RepairMessagesTest {

    @Test
    fun `a failed write-back says so`() {
        assertEquals("Writing back failed.", writeBackMessage(WriteBackOutcome.Failed))
    }

    @Test
    fun `a full write-back says it synced`() {
        assertEquals("Written back to the vault and synced.", writeBackMessage(WriteBackOutcome.Done(emptyList())))
    }

    @Test
    fun `the files left as they are are named, and a reload is asked first`() {
        assertEquals(
            "Written back and synced. The vault's chores were left as they are: the app holds none. Reload from the vault first.",
            writeBackMessage(WriteBackOutcome.Done(listOf(VaultListFile.CHORES)))
        )
        assertEquals(
            "Written back and synced. The vault's rooms and chores were left as they are: the app holds none. Reload from the vault first.",
            writeBackMessage(WriteBackOutcome.Done(listOf(VaultListFile.CHORES, VaultListFile.ROOMS)))
        )
        assertEquals(
            "Nothing written back: the app holds no habits, rooms or chores. Reload from the vault first.",
            writeBackMessage(WriteBackOutcome.Done(VaultListFile.entries.toList()))
        )
    }
}
