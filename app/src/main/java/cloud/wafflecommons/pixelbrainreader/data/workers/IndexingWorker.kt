package cloud.wafflecommons.pixelbrainreader.data.workers

import android.content.Context
import android.util.Base64
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.await
import androidx.work.workDataOf
import cloud.wafflecommons.pixelbrainreader.data.ai.EmbedderUnavailableException
import cloud.wafflecommons.pixelbrainreader.data.ai.NoteChunker
import cloud.wafflecommons.pixelbrainreader.data.ai.NoteEmbedder
import cloud.wafflecommons.pixelbrainreader.data.ai.PassageCodec
import cloud.wafflecommons.pixelbrainreader.data.ai.RoomPassageStore
import cloud.wafflecommons.pixelbrainreader.data.ai.RunBudget
import cloud.wafflecommons.pixelbrainreader.data.ai.VectorSearchEngine
import cloud.wafflecommons.pixelbrainreader.data.device.VaultWriteGate
import cloud.wafflecommons.pixelbrainreader.data.local.AppDatabase
import cloud.wafflecommons.pixelbrainreader.data.local.dao.EmbeddingDao
import cloud.wafflecommons.pixelbrainreader.data.local.dao.FileDao
import cloud.wafflecommons.pixelbrainreader.data.local.entity.FileEntity
import cloud.wafflecommons.pixelbrainreader.data.local.security.CryptoManager
import cloud.wafflecommons.pixelbrainreader.data.local.security.SecretManager
import cloud.wafflecommons.pixelbrainreader.data.repository.UserPreferencesRepository
import cloud.wafflecommons.pixelbrainreader.data.repository.VaultDiscoveryRepository
import cloud.wafflecommons.pixelbrainreader.data.vault.MirrorNotes
import cloud.wafflecommons.pixelbrainreader.data.vault.VaultSchema
import cloud.wafflecommons.pixelbrainreader.data.work.MissionLogSettings
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File

/**
 * RAG embedding worker: embeds the notes that are new, changed, or not yet embedded.
 *
 * Enqueued on app start (PixelBrainApplication, unique + KEEP), by Settings → "Index Knowledge
 * Vault" and by the private-notes switch. Built to always finish (2026-11-11: the Fold re-embedded
 * the same 231 KB note all night — every run was stopped by the system's 10-minute job limit before
 * the note's single save, and the next run started it over):
 *
 * - **Never repeats work.** A note whose content changed keeps its vectors, marked stale by the
 *   vault scan, and [NoteEmbedder] reuses each passage whose text is unchanged: an edited note costs
 *   only its new passages. A content-identical rewrite (a pull, a rebase) never even comes here.
 * - **Never loses work.** New vectors are kept as they come, a few at a time: a stop, a crash or the
 *   job limit costs at most one batch, and the next run resumes the note where it was.
 * - **Ends itself in time.** A run stops after [RUN_BUDGET_MS], before the system's limit, and
 *   hands over to a new run under the same unique name until the vault is done.
 * - **Never blocked by one note.** Notes without any vector come first (every search misses them),
 *   smallest first; a passage the model rejects is left out instead of retried on every run.
 * - **Loads the model only when a passage needs it**: a run that only reuses never starts it.
 *
 * No mood/habit/chore reconcile — that's handled elsewhere (PixelBrainApplication.runStartupReconcile
 * + SyncOrchestrator Phase 4).
 */
@HiltWorker
class IndexingWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val vaultDiscoveryRepository: VaultDiscoveryRepository,
    private val vectorSearchEngine: VectorSearchEngine,
    private val embeddingDao: EmbeddingDao,
    private val fileDao: FileDao,
    private val database: AppDatabase,
    private val userPrefs: UserPreferencesRepository,
    private val cryptoManager: CryptoManager,
    private val secretManager: SecretManager
) : CoroutineWorker(appContext, workerParams) {

    /** What a run did: the progress Settings shows and the summary the log keeps (counts only, no paths). */
    private class Tally(val notesTotal: Int) {
        val startedAt = System.currentTimeMillis()
        var notesDone = 0
        var embedded = 0
        var reused = 0
        var skipped = 0
        var failedNotes = 0

        fun progress() = workDataOf(KEY_NOTES_DONE to notesDone, KEY_NOTES_TOTAL to notesTotal, KEY_PASSAGES to embedded)

        fun output(partial: Boolean) = workDataOf(
            KEY_NOTES_DONE to notesDone, KEY_NOTES_TOTAL to notesTotal, KEY_PASSAGES to embedded, KEY_PARTIAL to partial
        )

        fun summary(): String {
            val elapsed = System.currentTimeMillis() - startedAt
            val perPassage = if (embedded > 0) "${elapsed / embedded} ms per embedded passage" else "no model time"
            return "notes $notesDone/$notesTotal, passages embedded $embedded, reused $reused, rejected $skipped, " +
                "notes failed $failedNotes, ${elapsed / 1000} s ($perPassage)"
        }
    }

    private var lastProgressAt = 0L

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        if (VaultWriteGate.readOnly) return@withContext Result.success() // no embeddings (AI) on a read-only device
        val budget = RunBudget(RUN_BUDGET_MS)
        val startTime = System.currentTimeMillis()
        try {
            // Cheap, no model load: a bumped EMBEDDER_SCHEMA_VERSION wipes the table first, and the
            // notes a rule keeps out of the index lose their old vectors.
            vectorSearchEngine.ensureSchemaVersion()
            purgeNeverEmbedded()

            val candidates = findCandidates()
            if (candidates.isEmpty()) {
                Log.d(TAG, "No embeddable candidates — nothing to do, model not loaded.")
                userPrefs.setLastIndexTime(startTime)
                return@withContext Result.success()
            }

            val vaultRoot = File(applicationContext.filesDir, "vault")
            val alreadyEmbedded = embeddingDao.fileIds().toHashSet()
            val ordered = indexingOrder(candidates, alreadyEmbedded) { File(vaultRoot, it).length() }
            val password = secretManager.getVaultPassword()?.takeIf { it.isNotBlank() }
            val embedder = NoteEmbedder(RoomPassageStore(database, embeddingDao), vectorSearchEngine::embed)
            val tally = Tally(ordered.size)
            Log.i(TAG, "IndexingWorker start: ${ordered.size} note(s) to embed, ${alreadyEmbedded.size} embedded, attempt $runAttemptCount")

            for (entity in ordered) {
                if (budget.exhausted()) return@withContext handOver(tally)
                val outcome = embedNote(entity, vaultRoot, password, embedder, budget, tally)
                if (outcome is NoteEmbedder.Outcome.Stopped) return@withContext handOver(tally)
                tally.notesDone++
                reportProgress(tally)
            }

            userPrefs.setLastIndexTime(startTime)
            Log.i(TAG, "IndexingWorker done: ${tally.summary()}")
            Result.success(tally.output(partial = false))
        } catch (e: EmbedderUnavailableException) {
            Log.e(TAG, "IndexingWorker ABORT: the embedding model could not start", e)
            Result.failure(workDataOf(KEY_ERROR to "The embedding model could not start"))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Fatal Indexing Error", e)
            if (runAttemptCount < 3) Result.retry() else Result.failure(workDataOf(KEY_ERROR to "Indexing failed"))
        }
    }

    /**
     * The notes to embed: never embedded, stale (their content changed), or left pending by a
     * stopped run — after a vault scan that records which contents changed.
     */
    private suspend fun findCandidates(): List<FileEntity> {
        val changed = try {
            vaultDiscoveryRepository.reindexAll().also { embeddingDao.deleteOrphans() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Vault scan failed", e)
            emptyList()
        }
        val needing = try {
            fileDao.getFilesNeedingEmbeddings()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "getFilesNeedingEmbeddings failed: ${e.message}")
            emptyList()
        }
        return filterEmbeddingCandidates((changed + needing).distinctBy { it.path })
    }

    /** One note, resumable. Null when the note can't be read (gone, undecryptable): the run moves on. */
    private suspend fun embedNote(
        entity: FileEntity,
        vaultRoot: File,
        password: String?,
        embedder: NoteEmbedder,
        budget: RunBudget,
        tally: Tally
    ): NoteEmbedder.Outcome? {
        val path = entity.path
        val isPrivate = isPrivatePath(path)
        return try {
            val file = File(vaultRoot, path)
            if (!file.isFile) {
                Log.d(TAG, "  skip $path: not on disk")
                return null
            }
            // Read BEFORE the content: if the note changes while it is embedded (a sync), its newer
            // file time leaves it needing the indexer — it can't pass for current with old vectors.
            val readAt = file.lastModified()
            val codec: PassageCodec
            val text: String
            if (isPrivate) {
                val secret = password ?: return null // the candidates already need a saved password
                codec = PrivatePassageCodec(cryptoManager, secret)
                text = withPassword(secret) { cryptoManager.decrypt(file.readBytes(), it) }
            } else {
                codec = PassageCodec.Plain
                text = file.readText()
            }
            val passages = NoteChunker.chunk(text, path)
            val outcome = embedder.embedNote(path, isPrivate, passages, codec, budget, stamp = readAt) { count ->
                tally.embedded += count
                reportProgress(tally)
            }
            if (outcome is NoteEmbedder.Outcome.Done) {
                tally.reused += outcome.reused
                tally.skipped += outcome.skipped
            }
            Log.d(TAG, "  $path: ${passages.size} passage(s), $outcome")
            outcome
        } catch (e: CancellationException) {
            throw e
        } catch (e: EmbedderUnavailableException) {
            throw e
        } catch (e: Exception) {
            Log.d(TAG, "  could not embed $path: ${e.message}")
            tally.failedNotes++
            null
        }
    }

    /** Time's up: a new run under the same unique name picks up where this one stops. */
    private suspend fun handOver(tally: Tally): Result {
        WorkManager.getInstance(applicationContext)
            .enqueueUniqueWork(UNIQUE_WORK_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request())
            .await()
        Log.i(TAG, "IndexingWorker: run time spent, a new run continues — ${tally.summary()}")
        return Result.success(tally.output(partial = true))
    }

    /** At most once a second: WorkManager keeps each progress update in its database. */
    private suspend fun reportProgress(tally: Tally) {
        val now = System.currentTimeMillis()
        if (now - lastProgressAt < PROGRESS_INTERVAL_MS) return
        lastProgressAt = now
        try {
            setProgress(tally.progress())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.d(TAG, "progress not recorded: ${e.message}")
        }
    }

    private suspend fun purgeNeverEmbedded() {
        try {
            val stale = neverEmbedded(embeddingDao.fileIds(), includePrivate = userPrefs.privateNotesInAi.first())
            stale.forEach { embeddingDao.deleteByFileId(it) }
            if (stale.isNotEmpty()) Log.i(TAG, "IndexingWorker: dropped the vectors of ${stale.size} note(s) kept out of the index")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Purge of excluded notes skipped: ${e.message}")
        }
    }

    private suspend fun filterEmbeddingCandidates(files: List<FileEntity>): List<FileEntity> {
        val today = java.time.LocalDate.now()
        // Settled decision #20: private notes only when the owner switched them on, and only with a
        // saved password to decrypt them (checked before the model is loaded).
        val includePrivate = userPrefs.privateNotesInAi.first() && !secretManager.getVaultPassword().isNullOrBlank()
        val candidates = embeddingCandidates(files, today, includePrivate)
        if (candidates.isEmpty() && files.isNotEmpty()) {
            val byType = files.groupingBy { it.type }.eachCount()
            Log.d(TAG, "filterEmbeddingCandidates: 0 candidates from ${files.size} items. byType=$byType")
        }
        return candidates
    }

    /** A private note's passages are kept encrypted (AES-GCM, the vault password), read back to be reused. */
    private class PrivatePassageCodec(private val crypto: CryptoManager, private val password: String) : PassageCodec {
        override suspend fun encode(text: String): String =
            withPassword(password) { Base64.encodeToString(crypto.encrypt(text, it), Base64.NO_WRAP) }

        override suspend fun decode(stored: String): String? = try {
            withPassword(password) { crypto.decrypt(Base64.decode(stored, Base64.NO_WRAP), it) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null // another password, or not ciphertext: re-embedded rather than reused
        }
    }

    companion object {
        private const val TAG = "RAG_DEBUG"

        /** WorkManager unique-work name, shared by every way indexing starts. */
        const val UNIQUE_WORK_NAME = "ManualVaultIndexing"

        /** A run's time. The system stops a background job after 10 minutes; this leaves room to hand over. */
        const val RUN_BUDGET_MS = 7 * 60_000L

        private const val PROGRESS_INTERVAL_MS = 1_000L

        // Progress and output keys (Settings reads them). "error" is what the failure chip shows.
        const val KEY_NOTES_DONE = "notesDone"
        const val KEY_NOTES_TOTAL = "notesTotal"
        const val KEY_PASSAGES = "passages"
        const val KEY_PARTIAL = "partial"
        const val KEY_ERROR = "error"

        /** The one indexing request: app start, Settings, the private-notes switch and every hand-over. */
        fun request(tag: String? = null): OneTimeWorkRequest =
            OneTimeWorkRequestBuilder<IndexingWorker>()
                .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
                .apply { if (tag != null) addTag(tag) }
                .build()

        /** Notes without any vector first (every search misses them), then the stale ones; smallest first. */
        internal fun indexingOrder(files: List<FileEntity>, embedded: Set<String>, sizeOf: (String) -> Long): List<FileEntity> =
            files.map { it to sizeOf(it.path) }
                .sortedWith(compareBy({ it.first.path in embedded }, { it.second }, { it.first.path }))
                .map { it.first }

        internal fun todayJournalPath(today: java.time.LocalDate): String =
            "10_Journal/${today.format(java.time.format.DateTimeFormatter.ISO_DATE)}.md"

        /**
         * Notes worth embedding. Settled decision #3 (CLAUDE.md): today's journal is NOT embedded —
         * it changes all day, so its vectors would be stale within minutes and every run would pay
         * a model load for it — and neither is `99_System` (machine JSON, noise for retrieval).
         * Private notes only with [includePrivate]: the owner's switch is on and a vault password is
         * stored (settled decision #20), checked BEFORE the model is loaded.
         * People notes never: what is written about a person is not something to retrieve into an
         * AI answer. Nor the encrypted mission log: work content outside any work folder would
         * reach AI answers in every mode. Nor Claude's memory mirrored from the desktop (settled
         * decision #16): it repeats the desktop's memory; the mirrored repository documents stay.
         */
        internal fun embeddingCandidates(
            files: List<FileEntity>,
            today: java.time.LocalDate,
            includePrivate: Boolean
        ): List<FileEntity> {
            val todayPath = todayJournalPath(today)
            return files.filter { entity ->
                val isPrivate = isPrivatePath(entity.path)
                entity.type == "file" &&
                    (entity.path.endsWith(".md", ignoreCase = true) ||
                        entity.path.endsWith(".md.enc", ignoreCase = true)) &&
                    entity.path != todayPath &&
                    !entity.path.contains("99_System") &&
                    !VaultSchema.isPeopleNote(entity.path) &&
                    !entity.path.startsWith(MissionLogSettings.ENCRYPTED_FOLDER + "/") &&
                    !MirrorNotes.isClaudeMemory(entity.path) &&
                    (!isPrivate || includePrivate)
            }
        }

        /** A private-vault path: under `99_Private/`, or an encrypted note anywhere. */
        internal fun isPrivatePath(path: String): Boolean =
            path.startsWith("99_Private/") || path.endsWith(".md.enc", ignoreCase = true)

        /**
         * The embedded notes to drop: those a rule added after they were embedded keeps out of the
         * index (Claude's mirrored memory), and the private notes while the owner keeps them out of
         * the AI (settled decision #20, [includePrivate] false). Today's journal is not one of them:
         * it is embedded once it is no longer today.
         */
        internal fun neverEmbedded(fileIds: List<String>, includePrivate: Boolean = true): List<String> =
            fileIds.filter { MirrorNotes.isClaudeMemory(it) || (!includePrivate && isPrivatePath(it)) }
    }
}

/** Runs [block] with the password as a char array, wiped afterwards. */
private inline fun <T> withPassword(password: String, block: (CharArray) -> T): T {
    val chars = password.toCharArray()
    try {
        return block(chars)
    } finally {
        java.util.Arrays.fill(chars, ' ')
    }
}
