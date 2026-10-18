package cloud.wafflecommons.pixelbrainreader.data.workers

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import cloud.wafflecommons.pixelbrainreader.data.vault.VaultSchema
import cloud.wafflecommons.pixelbrainreader.data.web.ClipQuality
import cloud.wafflecommons.pixelbrainreader.data.web.ClipSummarizer
import cloud.wafflecommons.pixelbrainreader.data.web.PageProblem
import cloud.wafflecommons.pixelbrainreader.data.web.PageUnavailableException
import cloud.wafflecommons.pixelbrainreader.data.web.WebClipNote
import cloud.wafflecommons.pixelbrainreader.data.web.WebClipper
import cloud.wafflecommons.pixelbrainreader.data.web.WebPageLoader
import cloud.wafflecommons.pixelbrainreader.data.work.DraftWriter
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import java.net.URI
import java.time.LocalDate

/**
 * The `pixelbrain://import?url=…` importer (confirmed by the user in MainActivity): the same
 * reader mode as the share screen ([WebPageLoader] + [WebClipper]), written to the inbox with an
 * on-device summary. A page that can't be read still becomes a note (the link and why) instead of
 * vanishing after the retries; an address that isn't public is refused outright.
 */
@HiltWorker
class ImportWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val loader: WebPageLoader,
    private val draftWriter: DraftWriter,
    private val summarizer: ClipSummarizer
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val url = inputData.getString("url") ?: return Result.failure()
        val today = LocalDate.now()
        val folder = VaultSchema.INBOX_FOLDER
        val (title, content) = try {
            val clip = loader.load(url)
            if (clip.quality == ClipQuality.EMPTY) {
                clip.title to WebClipNote.linkOnly(
                    clip.url, clip.title, folder, WebClipNote.reason(PageProblem.NO_TEXT, null, null), clip.description, null, today
                )
            } else {
                clip.title to WebClipNote.render(clip, clip.title, folder, summarizer.summarize(clip.markdown), passage = null, today = today)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: PageUnavailableException) {
            if (e.problem == PageProblem.NOT_PUBLIC) {
                Log.w(TAG, "Import refused: the page is on a non-public address")
                return Result.failure()
            }
            if (e.problem == PageProblem.NETWORK && runAttemptCount < MAX_RETRIES) return Result.retry()
            linkOnly(url, WebClipNote.reason(e.problem, e.status, e.detail), today)
        } catch (e: Exception) {
            Log.w(TAG, "Import failed (attempt ${runAttemptCount + 1})", e)
            if (runAttemptCount < MAX_RETRIES) return Result.retry()
            linkOnly(url, WebClipNote.reason(PageProblem.NETWORK, null, null), today)
        }
        draftWriter.write("$folder/${WebClipNote.fileName(title)}.md", content)
        return Result.success()
    }

    private fun linkOnly(url: String, reason: String, today: LocalDate): Pair<String, String> {
        val clean = WebClipper.cleanUrl(url)
        val title = runCatching { URI(clean).host?.removePrefix("www.") }.getOrNull() ?: "Lien"
        return title to WebClipNote.linkOnly(clean, title, VaultSchema.INBOX_FOLDER, reason, null, null, today)
    }

    private companion object {
        const val TAG = "ImportWorker"
        const val MAX_RETRIES = 3
    }
}
