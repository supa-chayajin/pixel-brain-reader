package cloud.wafflecommons.pixelbrainreader.data.work

import android.util.Log
import cloud.wafflecommons.pixelbrainreader.data.ai.LocalAiManager
import cloud.wafflecommons.pixelbrainreader.data.device.VaultWriteGate
import cloud.wafflecommons.pixelbrainreader.data.repository.FileRepository
import cloud.wafflecommons.pixelbrainreader.data.repository.UserPreferencesRepository
import cloud.wafflecommons.pixelbrainreader.data.sync.VaultPushScheduler
import cloud.wafflecommons.pixelbrainreader.data.vault.FrontmatterLines
import cloud.wafflecommons.pixelbrainreader.data.vault.MirrorNotes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The workshop kit (W2, 12.21): one workshop at a time, its draft kept on the phone
 * (`UserPreferencesRepository.workshopDraft`) until the synthesis is written beside the meeting
 * note and linked from it (the meeting note read from disk first). Nothing reaches the vault before
 * that, and nothing at all on a read-only device.
 */
@Singleton
class WorkshopRepository @Inject constructor(
    private val userPreferences: UserPreferencesRepository,
    private val fileRepository: FileRepository,
    private val draftWriter: DraftWriter,
    private val pushScheduler: VaultPushScheduler,
    private val localAiManager: LocalAiManager
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()

    /** The workshop under way, if any; an unreadable one reads as none. */
    val draft: Flow<Workshop.Draft?> = userPreferences.workshopDraft.map { raw -> raw?.let(::decode) }

    /** Applies [change] to the draft kept on the phone. */
    suspend fun update(change: (Workshop.Draft?) -> Workshop.Draft?) {
        mutex.withLock {
            val current = userPreferences.workshopDraft.first()?.let(::decode)
            val next = change(current)
            if (next != current) userPreferences.setWorkshopDraft(next?.let { json.encodeToString(Workshop.Draft.serializer(), it) })
        }
    }

    /** A new workshop from the meeting note on disk; null when the note has no agenda. */
    suspend fun prepare(notePath: String): Workshop.Draft? = withContext(Dispatchers.IO) {
        val content = try {
            fileRepository.readFile(notePath)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        } ?: return@withContext null
        val agenda = Workshop.agenda(content)
        if (agenda.isEmpty()) return@withContext null
        val minutes = FrontmatterLines.get(content, "duration_min")?.toIntOrNull()?.takeIf { it > 0 } ?: Workshop.DEFAULT_MINUTES
        val title = FrontmatterLines.get(content, "title")?.trim()?.trim('"', '\'')?.takeIf { it.isNotEmpty() }
            ?: notePath.substringAfterLast('/').removeSuffix(".md")
        Workshop.Draft(notePath, title, Workshop.steps(agenda, minutes))
    }

    /**
     * Writes the synthesis beside the meeting note (never over a note: " (2)"), with Nano's French
     * summary when [withSummary] and the model answers, links it from the meeting note read from
     * disk, and drops the draft. The synthesis note's path.
     */
    suspend fun finish(draft: Workshop.Draft, today: LocalDate, withSummary: Boolean): String = withContext(Dispatchers.IO) {
        VaultWriteGate.check()
        val summary = if (withSummary && draft.cards.isNotEmpty()) {
            localAiManager.generateResponse(Workshop.summaryPrompt(draft)).getOrNull()
        } else {
            null
        }
        val wanted = Workshop.synthesisPath(draft.notePath)
        val path = draftWriter.write(wanted, Workshop.synthesis(draft, wanted, today, summary))
        try {
            val meeting = fileRepository.readFile(draft.notePath)
            if (meeting != null && !MirrorNotes.isGenerated(draft.notePath, meeting)) {
                val linked = Workshop.withSynthesisLink(meeting, path)
                if (linked != meeting) fileRepository.saveFileLocally(draft.notePath, linked)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The synthesis is written: only the link back is missing.
            Log.w(TAG, "The meeting note couldn't link its synthesis: ${e.javaClass.simpleName}")
        }
        pushScheduler.requestPush()
        update { null }
        path
    }

    private fun decode(raw: String): Workshop.Draft? = try {
        json.decodeFromString(Workshop.Draft.serializer(), raw)
    } catch (e: Exception) {
        null
    }

    private companion object {
        const val TAG = "Workshop"
    }
}
