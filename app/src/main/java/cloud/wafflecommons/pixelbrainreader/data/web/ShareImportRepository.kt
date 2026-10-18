package cloud.wafflecommons.pixelbrainreader.data.web

import cloud.wafflecommons.pixelbrainreader.data.vault.VaultSchema
import cloud.wafflecommons.pixelbrainreader.data.work.DraftWriter
import cloud.wafflecommons.pixelbrainreader.di.ApplicationScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/** What a loading import is doing: downloading, rendering the page off screen, or reading the page the user opened. */
enum class LoadStep { DOWNLOAD, BROWSER, READ }

/** Where a share's import stands. [passage]: text shared with the link (a highlight), quoted in the note. */
sealed interface ShareImport {
    data class Loading(val url: String?, val step: LoadStep) : ShareImport
    data class Article(val clip: WebClip, val passage: String?) : ShareImport
    data class LinkOnly(
        val url: String,
        val titleHint: String?,
        val problem: PageProblem,
        val status: Int?,
        val detail: String?,
        val description: String?,
        val passage: String?
    ) : ShareImport
    data class Text(val titleHint: String?, val markdown: String) : ShareImport
}

/** The title the note starts with: the page's, the shared one, or a dated default (titles are note content: French). */
fun ShareImport.defaultTitle(today: LocalDate): String = when (this) {
    is ShareImport.Article -> clip.title
    is ShareImport.LinkOnly -> titleHint ?: runCatching { java.net.URI(url).host?.removePrefix("www.") }.getOrNull() ?: "Lien"
    is ShareImport.Text -> titleHint ?: "Note partagée du $today"
    is ShareImport.Loading -> ""
}

/**
 * The share screen's import (web pages and text shared from other apps): reads and clips the page
 * (falling back to a browser render, then to the link alone), then writes the note — never over an
 * existing one. Runs in the application scope: leaving the screen while a page loads doesn't lose it.
 */
@Singleton
class ShareImportRepository @Inject constructor(
    private val loader: WebPageLoader,
    private val draftWriter: DraftWriter,
    private val summarizer: ClipSummarizer,
    @param:ApplicationScope private val appScope: CoroutineScope
) {
    private val _state = MutableStateFlow<ShareImport?>(null)
    val state: StateFlow<ShareImport?> = _state.asStateFlow()

    private val _generation = MutableStateFlow(0)

    /** Bumped by every share: the screen opens on it, even while an older import is still parked. */
    val generation: StateFlow<Int> = _generation.asStateFlow()

    private var job: Job? = null
    private var lastShare: Pair<SharedContent, String?>? = null
    private var lastPassage: String? = null

    /** Imports what was shared: the page when the text holds a link, the text (or the HTML selection) otherwise. */
    fun start(shared: SharedContent, html: String?) {
        job?.cancel()
        lastShare = shared to html
        val url = shared.url
        _state.value = ShareImport.Loading(url, LoadStep.DOWNLOAD)
        _generation.value++
        job = appScope.launch {
            val selection = html?.let { withContext(Dispatchers.Default) { WebClipper.fragment(it, url) } }?.takeIf { it.isNotBlank() }
            if (url == null) {
                _state.value = ShareImport.Text(shared.titleHint, selection ?: shared.passage.orEmpty())
                return@launch
            }
            val passage = selection ?: shared.passage
            lastPassage = passage
            val result = try {
                val clip = loader.load(url) { _state.value = ShareImport.Loading(url, LoadStep.BROWSER) }
                if (clip.quality == ClipQuality.EMPTY) {
                    ShareImport.LinkOnly(clip.url, shared.titleHint ?: clip.title, PageProblem.NO_TEXT, null, null, clip.description, passage)
                } else {
                    ShareImport.Article(clip, passage)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: PageUnavailableException) {
                ShareImport.LinkOnly(WebClipper.cleanUrl(url), shared.titleHint, e.problem, e.status, e.detail, null, passage)
            } catch (e: Exception) {
                ShareImport.LinkOnly(WebClipper.cleanUrl(url), shared.titleHint, PageProblem.NETWORK, null, e.message, null, passage)
            }
            _state.value = result
        }
    }

    /**
     * Imports the page as the user sees it in the share screen's browser — past a human check or a
     * consent wall the off-screen read couldn't get through.
     */
    fun importRendered(html: String, url: String) {
        val shared = lastShare?.first
        val passage = lastPassage
        job?.cancel()
        _state.value = ShareImport.Loading(url, LoadStep.READ)
        job = appScope.launch {
            val result = try {
                val clip = loader.clipRendered(html, url)
                if (clip.quality == ClipQuality.EMPTY) {
                    ShareImport.LinkOnly(clip.url, shared?.titleHint ?: clip.title, PageProblem.NO_TEXT, null, null, clip.description, passage)
                } else {
                    ShareImport.Article(clip, passage)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: PageUnavailableException) {
                ShareImport.LinkOnly(WebClipper.cleanUrl(url), shared?.titleHint, e.problem, e.status, e.detail, null, passage)
            }
            _state.value = result
        }
    }

    /** Tries the page again (the network came back, the site let us in). */
    fun retry() {
        lastShare?.let { (shared, html) -> start(shared, html) }
    }

    fun dismiss() {
        job?.cancel()
        lastShare = null
        lastPassage = null
        _state.value = null
    }

    /**
     * Writes the import as a note in [folder] named after [title] ("Title (2).md" when taken), with
     * an on-device summary of the article when [summarize]. [editedMarkdown]: the body as the user
     * edited it, if they did. Returns the note's path.
     */
    suspend fun save(title: String, folder: String, editedMarkdown: String?, summarize: Boolean): String {
        val current = _state.value ?: throw IllegalStateException("Nothing to import")
        val today = LocalDate.now()
        val cleanTitle = title.replace(Regex("""\s+"""), " ").trim().ifEmpty { "Clipping" }
        val target = folder.trim().trim('/').ifEmpty { VaultSchema.INBOX_FOLDER }
        val content = when (current) {
            is ShareImport.Article -> {
                val clip = editedMarkdown?.let { current.clip.copy(markdown = it) } ?: current.clip
                val summary = if (summarize) summarizer.summarize(clip.markdown) else null
                WebClipNote.render(clip, cleanTitle, target, summary, current.passage, today)
            }
            is ShareImport.LinkOnly -> WebClipNote.linkOnly(
                current.url, cleanTitle, target, WebClipNote.reason(current.problem, current.status, current.detail),
                description = current.description, passage = current.passage, today = today
            )
            is ShareImport.Text -> WebClipNote.text(cleanTitle, target, editedMarkdown ?: current.markdown, today)
            is ShareImport.Loading -> throw IllegalStateException("The page is still loading")
        }
        val path = draftWriter.write("$target/${WebClipNote.fileName(cleanTitle)}.md", content)
        if (_state.value === current) {
            lastShare = null
            lastPassage = null
            _state.value = null
        }
        return path
    }
}
