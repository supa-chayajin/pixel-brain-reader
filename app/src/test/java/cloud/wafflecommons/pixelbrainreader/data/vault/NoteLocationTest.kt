package cloud.wafflecommons.pixelbrainreader.data.vault

import org.junit.Assert.assertEquals
import org.junit.Test

class NoteLocationTest {

    @Test
    fun `a note's folder is the path without its name`() {
        assertEquals("20_Projects/21_App/Notes", NoteLocation.folderOf("20_Projects/21_App/Notes/Idea.md"))
        assertEquals("00_Inbox", NoteLocation.folderOf("00_Inbox/Capture.md"))
    }

    @Test
    fun `a note at the top has the root as its folder`() {
        assertEquals("", NoteLocation.folderOf("README.md"))
        assertEquals("", NoteLocation.folderOf("/README.md"))
        assertEquals(NoteLocation.ROOT, NoteLocation.label(NoteLocation.folderOf("README.md")))
    }

    @Test
    fun `the label joins the folders and skips empty parts`() {
        assertEquals("20_Projects › 21_App › Notes", NoteLocation.label("20_Projects/21_App/Notes"))
        assertEquals("20_Projects › Notes", NoteLocation.label("/20_Projects//Notes/"))
        assertEquals(NoteLocation.ROOT, NoteLocation.label(""))
    }

    @Test
    fun `a screen reader hears the folders, never the separator`() {
        assertEquals("In 20_Projects, 21_App", NoteLocation.spoken("20_Projects/21_App"))
        assertEquals("In Vault root", NoteLocation.spoken(""))
    }
}
