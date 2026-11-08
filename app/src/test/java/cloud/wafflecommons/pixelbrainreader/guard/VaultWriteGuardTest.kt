package cloud.wafflecommons.pixelbrainreader.guard

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Layer 5 of the read-only device (documentation/plans/2026-11-08-read-only-device.md): every file
 * the app writes goes through a writer that checks VaultWriteGate, so a read-only device can't
 * change its clone. This guard fails the build when code elsewhere starts writing, renaming or
 * deleting files directly: route the write through SafeFileProvider / NoteRepository instead, or —
 * if it writes outside the vault — add the file below with the reason.
 *
 * Directory creation (`mkdirs`) is not listed: git never records an empty folder. Nor is
 * `File.createNewFile()`, which reads like the screens' own "create a new file" actions.
 */
class VaultWriteGuardTest {

    private val sourceRoot: File = listOf("src/main/java", "app/src/main/java")
        .map { File(it, "cloud/wafflecommons/pixelbrainreader") }
        .first { it.isDirectory }

    /** Raw file mutations (java.io / java.nio / Kotlin's File extensions). */
    private val rawWrite = Regex(
        "\\.(writeText|writeBytes|appendText|appendBytes|outputStream|bufferedWriter|printWriter|renameTo|" +
            "copyTo|copyRecursively|deleteRecursively)\\(|\\.delete\\(\\)|" +
            "\\b(FileOutputStream|FileWriter|RandomAccessFile)\\(|" +
            "\\bFiles\\.(move|write|writeString|copy|delete|deleteIfExists|newOutputStream|newBufferedWriter|createFile)\\("
    )

    /** Vault writers: each one checks VaultWriteGate before it writes. */
    private val gatedWriters = setOf(
        "data/utils/SafeFileProvider.kt",
        "data/repository/PrivateNoteRepository.kt",
        "data/repository/AssetRepository.kt",
        "data/repository/FileRepository.kt",
        "data/repository/DailyNoteRepository.kt",
        "data/sync/ConflictResolver.kt",
        "data/work/MissionLogRepository.kt",
        // git itself: every command that writes is gated; the clone and its deletion are the login's.
        "data/remote/JGitProvider.kt"
    )

    /** Files that write only outside the vault (filesDir caches, the export target). */
    private val outsideTheVault = mapOf(
        "data/remote/GitFileAges.kt" to "filesDir/git_file_ages.json",
        "data/sync/SyncDigest.kt" to "filesDir/sync_digest.json",
        "widget/manager/WidgetSnapshotManager.kt" to "filesDir/widget_snapshot.json",
        "data/device/LocalCopyReset.kt" to "the caches above, at a login's fresh clone",
        "data/usecase/SyncHealthDataUseCase.kt" to "the legacy filesDir/10_Journal folder (vault writes go through FileRepository)",
        "data/ai/VectorSearchEngine.kt" to "the model files copied out of the APK assets",
        "data/repository/VaultExportRepository.kt" to "the export the user picks (it only reads the vault)",
        "data/local/security/SecretManager.kt" to "the legacy plain preferences file it migrates"
    )

    @Test
    fun `nothing writes files outside the gated writers`() {
        val violations = mutableListOf<String>()
        sourceRoot.walkTopDown().filter { it.extension == "kt" }.forEach { file ->
            val path = file.relativeTo(sourceRoot).invariantSeparatorsPath
            if (path in gatedWriters || path in outsideTheVault) return@forEach
            file.readLines().forEachIndexed { index, line ->
                val code = line.trim()
                if (code.startsWith("//") || code.startsWith("*") || code.startsWith("/*")) return@forEachIndexed
                if (rawWrite.containsMatchIn(line)) violations += "$path:${index + 1}  $code"
            }
        }
        assertTrue(
            "Raw file writes outside the gated writers (see VaultWriteGuardTest):\n" + violations.joinToString("\n"),
            violations.isEmpty()
        )
    }

    @Test
    fun `every vault writer checks the gate`() {
        val ungated = gatedWriters.filterNot { File(sourceRoot, it).readText().contains("VaultWriteGate") }
        assertTrue("Vault writers that never check VaultWriteGate: $ungated", ungated.isEmpty())
    }

    @Test
    fun `the allow-lists name files that exist`() {
        val missing = (gatedWriters + outsideTheVault.keys).filterNot { File(sourceRoot, it).isFile }
        assertTrue("Allow-listed files that no longer exist: $missing", missing.isEmpty())
    }
}
