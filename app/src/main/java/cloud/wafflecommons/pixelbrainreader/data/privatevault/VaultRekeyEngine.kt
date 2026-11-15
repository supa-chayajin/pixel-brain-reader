package cloud.wafflecommons.pixelbrainreader.data.privatevault

import android.util.Log
import cloud.wafflecommons.pixelbrainreader.data.device.VaultWriteGate
import cloud.wafflecommons.pixelbrainreader.data.local.security.CryptoManager
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest

/** Where a change of password keeps its work: `filesDir/private_rekey`, outside the vault. */
object RekeyFiles {
    const val FOLDER = "private_rekey"
    const val MANIFEST = "manifest.json"

    fun workDir(filesDir: File) = File(filesDir, FOLDER)

    /**
     * True while a swap is under way: the vault holds notes under both passwords until it ends, so
     * nothing may commit it (JGitProvider refuses).
     */
    fun swapPending(filesDir: File): Boolean = File(workDir(filesDir), MANIFEST).isFile
}

/** One note of a swap: its vault path, the SHA-256 of the ciphertext it replaces, its staged copy. */
@Serializable
data class RekeyEntry(val path: String, val sha256: String, val staged: String)

@Serializable
data class RekeyManifest(val entries: List<RekeyEntry>)

/**
 * A change of the private vault's password (12.18, V1), crash-safe:
 * 1. **Check.** Every `*.md.enc` must open with the current password, or nothing is written.
 * 2. **Stage.** Each note is re-encrypted with the new password into the work folder (outside the
 *    vault, so git never sees a staged copy), fsynced, read back and compared.
 * 3. **Swap.** The manifest, written atomically, is the point of no return. Before it, a crash
 *    leaves the vault untouched and [resume] drops the work folder. After it, every staged copy is
 *    renamed over its note, one atomic rename each, and [resume] finishes the renames at the next
 *    start: they need no password.
 *
 * [checkpoint] is a test hook: the kill-mid-way test throws from it.
 */
class VaultRekeyEngine(
    private val vaultRoot: File,
    private val workDir: File,
    private val crypto: CryptoManager,
    private val checkpoint: (String) -> Unit = {}
) {
    sealed interface Outcome {
        data class Done(val files: Int) : Outcome

        /** Notes the current password doesn't open (their vault paths): nothing was written. */
        data class Unreadable(val paths: List<String>) : Outcome

        /** A note changed between its check and the swap: nothing was changed. */
        data object Moved : Outcome
    }

    enum class Resumed { NOTHING, ROLLED_BACK, FINISHED, STUCK }

    private val json = Json { ignoreUnknownKeys = true }
    private val staging get() = File(workDir, "staged")
    private val manifestFile get() = File(workDir, RekeyFiles.MANIFEST)

    /** Every encrypted note of the vault: the journal, an encrypted log's months, ADRs and reports. */
    fun encryptedFiles(): List<File> = vaultRoot.walkTopDown()
        .onEnter { it.name != ".git" }
        .filter { it.isFile && it.name.endsWith(".md.enc", ignoreCase = true) }
        .sortedBy { it.path }
        .toList()

    /** Re-encrypts every note from [current] to [new]. Call [resume] first: a swap left half done is finished before a new one starts. */
    suspend fun change(current: CharArray, new: CharArray, progress: (Int, Int) -> Unit = { _, _ -> }): Outcome {
        VaultWriteGate.check()
        check(!workDir.exists()) { "A previous change of password is still to finish" }
        val files = encryptedFiles()

        val unreadable = files.filterNot { opens(it, current) }
        if (unreadable.isNotEmpty()) return Outcome.Unreadable(unreadable.map { relative(it) })

        staging.mkdirs()
        val entries = files.mapIndexed { index, file ->
            val bytes = file.readBytes()
            val text = crypto.decrypt(bytes, current)
            val name = "$index.enc"
            val copy = File(staging, name)
            writeSynced(copy, crypto.encrypt(text, new))
            check(crypto.decrypt(copy.readBytes(), new) == text) { "A re-encrypted note didn't read back" }
            progress(index + 1, files.size)
            checkpoint("staged:$index")
            RekeyEntry(relative(file), sha256(bytes), name)
        }

        // Under the git and write locks nothing should change a note meanwhile; if one did, stop clean.
        if (entries.any { sha256(File(vaultRoot, it.path).readBytes()) != it.sha256 }) {
            workDir.deleteRecursively()
            return Outcome.Moved
        }

        writeManifest(RekeyManifest(entries))
        checkpoint("manifest")
        swap(entries)
        workDir.deleteRecursively()
        return Outcome.Done(entries.size)
    }

    /**
     * After a crash, at the next start: a work folder without a manifest is dropped (the vault was
     * never touched), one with a manifest has its renames finished. An unreadable manifest is left
     * as it is (STUCK) and the vault keeps refusing commits: nothing is guessed.
     */
    fun resume(): Resumed {
        if (!workDir.exists()) return Resumed.NOTHING
        VaultWriteGate.check()
        if (!manifestFile.exists()) {
            workDir.deleteRecursively()
            return Resumed.ROLLED_BACK
        }
        val manifest = try {
            json.decodeFromString(RekeyManifest.serializer(), manifestFile.readText())
        } catch (e: Exception) {
            Log.e(TAG, "The password change's manifest can't be read: left as it is")
            return Resumed.STUCK
        }
        swap(manifest.entries)
        workDir.deleteRecursively()
        return Resumed.FINISHED
    }

    private fun swap(entries: List<RekeyEntry>) {
        entries.forEachIndexed { index, entry ->
            val copy = File(staging, entry.staged)
            if (copy.isFile) move(copy, File(vaultRoot, entry.path)) // gone: renamed before a crash
            checkpoint("swapped:$index")
        }
    }

    private suspend fun opens(file: File, password: CharArray): Boolean = try {
        crypto.decrypt(file.readBytes(), password)
        true
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        false
    }

    private fun relative(file: File): String = file.relativeTo(vaultRoot).invariantSeparatorsPath

    private fun writeSynced(target: File, bytes: ByteArray) {
        FileOutputStream(target).use { out ->
            out.write(bytes)
            out.fd.sync()
        }
    }

    /** Temp file, fsync, atomic rename, then the folder itself: the manifest is all there or not at all. */
    private fun writeManifest(manifest: RekeyManifest) {
        val temp = File(workDir, "${RekeyFiles.MANIFEST}.tmp")
        writeSynced(temp, json.encodeToString(RekeyManifest.serializer(), manifest).toByteArray())
        move(temp, manifestFile)
        syncFolder(workDir)
    }

    private fun move(from: File, to: File) {
        try {
            Files.move(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: AtomicMoveNotSupportedException) {
            Files.move(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun syncFolder(folder: File) {
        try {
            FileChannel.open(folder.toPath(), StandardOpenOption.READ).use { it.force(true) }
        } catch (e: Exception) {
            // Not every file system lets a folder be synced; the rename is atomic either way.
        }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private companion object {
        const val TAG = "PrivateVault"
    }
}
