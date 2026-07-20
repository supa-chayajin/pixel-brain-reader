package cloud.wafflecommons.pixelbrainreader.data.sync

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Pins the backup mechanics of [ConflictResolver.secureLocalBackup] — the routine that
 * snapshots a user's local file before/around a sync conflict:
 *  - backup lands NEXT TO the original, named `{base}_sync_conflict_{yyyyMMdd_HHmmss}{.ext}`;
 *  - backup content is a byte-identical copy of the current on-disk file;
 *  - the original file is left untouched;
 *  - a missing/unreadable path returns null instead of throwing.
 */
class ConflictResolverTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var filesDir: File
    private lateinit var resolver: ConflictResolver

    private val vaultDir: File
        get() = File(filesDir, "vault")

    @Before
    fun setUp() {
        filesDir = temp.newFolder("filesDir")
        vaultDir.mkdirs()
        val context = mockk<Context> {
            every { filesDir } returns this@ConflictResolverTest.filesDir
        }
        resolver = ConflictResolver(context)
    }

    @Test
    fun `backs up the file next to the original with timestamped name and identical content`() {
        runBlocking {
            val original = File(vaultDir, "10_Journal/note.md")
            original.parentFile?.mkdirs()
            original.writeText(CONTENT)

            val backup = resolver.secureLocalBackup("10_Journal/note.md")

            assertNotNull("backup must be created for an existing file", backup)
            assertTrue(backup!!.exists())
            // Same directory as the original.
            assertEquals(original.parentFile, backup.parentFile)
            // Name contract: {base}_sync_conflict_{yyyyMMdd_HHmmss}.{ext}
            assertTrue(
                "unexpected backup name: ${backup.name}",
                backup.name.matches(Regex("""note_sync_conflict_\d{8}_\d{6}\.md"""))
            )
            // Byte-identical copy; original untouched.
            assertEquals(CONTENT, backup.readText())
            assertEquals(CONTENT, original.readText())
        }
    }

    @Test
    fun `extension-less file gets a backup without a trailing dot`() {
        runBlocking {
            val original = File(vaultDir, "TODO")
            original.writeText(CONTENT)

            val backup = resolver.secureLocalBackup("TODO")

            assertNotNull(backup)
            assertTrue(
                "unexpected backup name: ${backup!!.name}",
                backup.name.matches(Regex("""TODO_sync_conflict_\d{8}_\d{6}"""))
            )
            assertEquals(CONTENT, backup.readText())
        }
    }

    @Test
    fun `missing file returns null instead of throwing`() {
        runBlocking {
            val backup = resolver.secureLocalBackup("nulle/part/fantome.md")
            assertNull(backup)
        }
    }

    private companion object {
        const val CONTENT = "contenu local precieux\navec deux lignes\n"
    }
}
