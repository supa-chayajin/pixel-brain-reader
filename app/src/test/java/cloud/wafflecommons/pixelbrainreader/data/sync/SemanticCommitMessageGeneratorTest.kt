package cloud.wafflecommons.pixelbrainreader.data.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SemanticCommitMessageGeneratorTest {

    @Test
    fun isGenericMessage_detectsGenericPatterns() {
        assertTrue(SemanticCommitMessageGenerator.isGenericMessage("Auto-sync"))
        assertTrue(SemanticCommitMessageGenerator.isGenericMessage("auto-save: local changes"))
        assertTrue(SemanticCommitMessageGenerator.isGenericMessage("Sync changes"))
        assertTrue(SemanticCommitMessageGenerator.isGenericMessage(null))
        assertTrue(SemanticCommitMessageGenerator.isGenericMessage("  "))
        assertFalse(SemanticCommitMessageGenerator.isGenericMessage("feat(auth): add GitHub device flow"))
        assertFalse(SemanticCommitMessageGenerator.isGenericMessage("Delete 20_Projects/Waffle.md"))
    }

    @Test
    fun generate_singleJournalFile_generatesJournalConventionalCommit() {
        val paths = listOf("10_Journal/2026-10-17.md")
        val message = SemanticCommitMessageGenerator.generate(paths)
        assertEquals("journal(2026-10-17): update daily note", message)
    }

    @Test
    fun generate_singleProjectFile_generatesProjectCommit() {
        val paths = listOf("20_Projects/21_Waffle_Framework/Architecture.md")
        val message = SemanticCommitMessageGenerator.generate(paths)
        assertEquals("projects(21_Waffle_Framework): update Architecture", message)
    }

    @Test
    fun generate_multipleFilesInSameCategory_generatesCategoryCommit() {
        val paths = listOf(
            "20_Projects/21_Waffle_Framework/Architecture.md",
            "20_Projects/22_Closd/Meeting.md"
        )
        val message = SemanticCommitMessageGenerator.generate(paths)
        assertEquals("projects: update 2 files", message)
    }

    @Test
    fun generate_multipleFilesAcrossDifferentCategories_generatesVaultCommit() {
        val paths = listOf(
            "10_Journal/2026-10-17.md",
            "20_Projects/21_Waffle_Framework/Readme.md"
        )
        val message = SemanticCommitMessageGenerator.generate(paths)
        assertEquals("vault: update journal, projects (2 files)", message)
    }

    @Test
    fun generate_encryptedNote_neverNamesIt() {
        val message = SemanticCommitMessageGenerator.generate(listOf("99_Private/Therapy notes.md.enc"))
        assertEquals("private: update an encrypted note", message)
        assertFalse(message.contains("Therapy"))
    }

    @Test
    fun generate_emptyList_returnsFallback() {
        val message = SemanticCommitMessageGenerator.generate(emptyList(), "Fallback")
        assertEquals("Fallback", message)
    }
}
