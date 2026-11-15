package cloud.wafflecommons.pixelbrainreader.data.repository

import cloud.wafflecommons.pixelbrainreader.data.ai.CompiledVoiceResult
import cloud.wafflecommons.pixelbrainreader.data.sync.VaultPushScheduler
import cloud.wafflecommons.pixelbrainreader.data.utils.SafeFileProvider
import cloud.wafflecommons.pixelbrainreader.data.vault.VaultSchema
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Quick captures land in the vault's `00_Inbox/` as ordinary markdown notes: they sync, get
 * indexed for search and RAG, and can be filed later (from the app or Obsidian). The daily note
 * only gets a one-line timeline entry linking to the capture — a multi-line body can't live in
 * the timeline (see MarkdownBurner.singleLine).
 */
@Singleton
class CaptureRepository @Inject constructor(
    private val noteRepository: NoteRepository,
    private val safeFileProvider: SafeFileProvider,
    private val pushScheduler: VaultPushScheduler,
    private val assetRepository: AssetRepository
) {

    /** Writes a dictated note to the inbox and returns its vault-relative path. */
    suspend fun saveVoiceNote(capturedAt: LocalDateTime, compiled: CompiledVoiceResult): String =
        withContext(Dispatchers.IO) {
            val path = uniquePath("Voice note ${capturedAt.format(NAME_TIME)}")
            noteRepository.saveNote(path, renderVoiceNote(capturedAt, compiled))
            pushScheduler.requestPush()
            path
        }

    /**
     * A typed or dictated capture (X4, 12.21) as a new inbox note, from the tile or the lock screen
     * ([source]: `tile` or `notes`); its vault-relative path.
     */
    suspend fun saveQuickNote(capturedAt: LocalDateTime, text: String, source: String): String =
        withContext(Dispatchers.IO) {
            requireVault()
            val path = uniquePath("Capture ${capturedAt.format(NAME_TIME)}")
            noteRepository.saveNote(path, renderQuickNote(capturedAt, text, source))
            pushScheduler.requestPush()
            path
        }

    /**
     * A photo capture (13.0, X2): the photo kept in the vault's attachment folder, beside a new
     * inbox note that shows it first, then [text] (what was typed and what the photo said).
     */
    suspend fun savePhotoNote(capturedAt: LocalDateTime, text: String, jpeg: ByteArray, source: String): String =
        withContext(Dispatchers.IO) {
            requireVault()
            val path = uniquePath("Capture ${capturedAt.format(NAME_TIME)}")
            val image = assetRepository.saveDropped(path, "image/jpeg", null) { jpeg.inputStream() }
                ?: throw java.io.IOException("The photo couldn't be kept")
            val body = cloud.wafflecommons.pixelbrainreader.data.capture.PhotoCapture.body(image.substringAfterLast('/'), text)
            noteRepository.saveNote(path, renderQuickNote(capturedAt, body, source, PHOTO_TAGS))
            pushScheduler.requestPush()
            path
        }

    /**
     * The captures reachable without opening the app (the tile, the lock screen, a share) need the
     * vault's clone: before the first login, the first clone replaces the folder and its notes.
     */
    private fun requireVault() {
        if (!safeFileProvider.getSafeFile(".git").isDirectory) throw NoVaultYetException()
    }

    /** No clone of the vault on this phone yet: log in first. */
    class NoVaultYetException : java.io.IOException("No vault on this phone yet")

    private fun uniquePath(baseName: String): String {
        var path = "$INBOX/$baseName.md"
        var n = 2
        while (safeFileProvider.getSafeFile(path).exists()) {
            path = "$INBOX/$baseName ($n).md"
            n++
        }
        return path
    }

    companion object {
        const val INBOX = "00_Inbox"
        private val NAME_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HHmm")
        private val HEADING_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

        private val QUICK_TAGS = listOf("inbox", "capture")
        private val PHOTO_TAGS = listOf("inbox", "capture", "photo")

        internal fun renderQuickNote(capturedAt: LocalDateTime, text: String, source: String, tags: List<String> = QUICK_TAGS): String = buildString {
            val title = "Capture ${capturedAt.format(HEADING_TIME)}"
            append(
                VaultSchema.frontmatter(
                    title = title,
                    path = "$INBOX/$title.md",
                    date = capturedAt.toLocalDate(),
                    tags = tags,
                    extra = listOf(
                        "source" to source,
                        "captured" to VaultSchema.singleQuoted(capturedAt.withSecond(0).withNano(0).toString())
                    )
                )
            )
            append('\n')
            append("# ").append(title).append("\n\n")
            append(text.trim()).append('\n')
        }

        internal fun renderVoiceNote(capturedAt: LocalDateTime, compiled: CompiledVoiceResult): String = buildString {
            val title = "Voice note ${capturedAt.format(HEADING_TIME)}"
            append(
                VaultSchema.frontmatter(
                    title = title,
                    path = "$INBOX/$title.md",
                    date = capturedAt.toLocalDate(),
                    tags = listOf("inbox", "voice"),
                    extra = listOf(
                        "source" to "voice",
                        "captured" to VaultSchema.singleQuoted(capturedAt.withSecond(0).withNano(0).toString())
                    )
                )
            )
            append('\n')
            append("# ").append(title).append("\n\n")
            if (compiled.structuredMarkdown.isNotBlank()) {
                append(compiled.structuredMarkdown.trim()).append("\n\n")
            }
            if (compiled.detectedTasks.isNotEmpty()) {
                // Plain bullets, not checkboxes: the tasks live on the daily board, and a second
                // checkbox here would be a second place to tick them (and earn XP twice).
                append("## Added to the day's tasks\n\n")
                compiled.detectedTasks.forEach { append("- ").append(it).append('\n') }
                append('\n')
            }
            append("## Transcript\n\n")
            compiled.rawTranscript.trim().lineSequence().forEach { append("> ").append(it).append('\n') }
        }
    }
}
