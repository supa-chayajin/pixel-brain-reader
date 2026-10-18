package cloud.wafflecommons.pixelbrainreader.data.workers

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import cloud.wafflecommons.pixelbrainreader.data.ai.AiLanguage
import cloud.wafflecommons.pixelbrainreader.data.ai.LocalAiManager
import cloud.wafflecommons.pixelbrainreader.data.repository.FileRepository
import cloud.wafflecommons.pixelbrainreader.data.sync.VaultPushScheduler
import cloud.wafflecommons.pixelbrainreader.data.utils.BlockedAddressException
import cloud.wafflecommons.pixelbrainreader.data.utils.SafeWebFetcher
import cloud.wafflecommons.pixelbrainreader.data.vault.VaultSchema
import com.vladsch.flexmark.html2md.converter.FlexmarkHtmlConverter
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * "Reader-mode" web importer. Instead of dumping the whole page, it:
 *  1. strips chrome/ads/nav/footers,
 *  2. scores candidate containers and keeps the densest article body,
 *  3. rewrites relative image/link URLs to absolute (so images actually render),
 *  4. pulls Open-Graph/meta metadata (title, author, date, site, hero image),
 *  5. converts to clean markdown and wraps it in a tidy, Obsidian-flavoured note
 *     (frontmatter + source callout + AI-summary callout).
 */
@HiltWorker
class ImportWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val fileRepository: FileRepository,
    private val localAiManager: LocalAiManager,
    private val pushScheduler: VaultPushScheduler
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val url = inputData.getString("url") ?: return@withContext Result.failure()

        try {
            // 1. Fetch. Every redirect hop is re-validated against the SSRF guard: following
            // redirects freely let a public page send the fetch to an internal address.
            val doc = SafeWebFetcher.fetch(url, DESKTOP_USER_AGENT, 20_000)

            // 2. Metadata (Open Graph first, then sensible fallbacks)
            val meta = extractMetadata(doc, url)

            // 3. Readable body → clean markdown
            val readableHtml = extractReadableHtml(doc)
            val rawMarkdown = FlexmarkHtmlConverter.builder().build().convert(readableHtml)
            val markdownBody = tidyMarkdown(rawMarkdown)

            // 4. AI summary (on-device) — best-effort, formatted as a callout later.
            val summary = summarize(markdownBody)

            // 5. Assemble a nice note
            val finalContent = buildNote(meta, summary, markdownBody)

            // 6. Save to Inbox (path-safe Unicode filename, timestamped on collision)
            val sanitizedTitle = meta.title.replace(Regex("[^\\p{L}\\p{N} \\-_]"), "").trim().take(50)
                .ifBlank { "Article" }
            val fileName = "00_Inbox/$sanitizedTitle.md"
            val finalPath = if (fileRepository.fileExists(fileName)) {
                "00_Inbox/${sanitizedTitle}_${System.currentTimeMillis()}.md"
            } else {
                fileName
            }
            fileRepository.createFile(finalPath, finalContent)

            // 7. Git: batched background push.
            pushScheduler.requestPush()

            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: BlockedAddressException) {
            Log.w(TAG, "Import refused: the page is on a non-public address")
            Result.failure()
        } catch (e: Exception) {
            // Transient fetch/parse/summary failure → retry (network flakiness) rather than
            // silently dropping the import, but cap attempts so a bad URL doesn't spin.
            Log.w(TAG, "Import failed (attempt ${runAttemptCount + 1})", e)
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }

    // --- Extraction helpers ---------------------------------------------------

    private data class ArticleMeta(
        val title: String,
        val siteName: String?,
        val author: String?,
        val published: String?,
        val description: String?,
        val heroImage: String?,
        val url: String
    )

    private fun extractMetadata(doc: Document, url: String): ArticleMeta {
        val title = metaContent(doc, "og:title", "twitter:title")
            ?: doc.title().ifBlank { null }
            ?: "Untitled Article"
        val heroImage = metaContent(doc, "og:image", "twitter:image")?.let { doc.resolveUrl(it) }
        return ArticleMeta(
            title = title.trim(),
            siteName = metaContent(doc, "og:site_name"),
            author = metaContent(doc, "article:author", "author", "twitter:creator"),
            published = metaContent(doc, "article:published_time", "og:article:published_time", "date"),
            description = metaContent(doc, "og:description", "description", "twitter:description"),
            heroImage = heroImage,
            url = url
        )
    }

    /** First non-blank content of any <meta property=key> / <meta name=key>. */
    private fun metaContent(doc: Document, vararg keys: String): String? {
        for (k in keys) {
            doc.selectFirst("meta[property=$k]")?.attr("content")?.trim()?.takeIf { it.isNotBlank() }?.let { return it }
            doc.selectFirst("meta[name=$k]")?.attr("content")?.trim()?.takeIf { it.isNotBlank() }?.let { return it }
        }
        return null
    }

    private fun Document.resolveUrl(maybeRelative: String): String =
        try { java.net.URI(this.baseUri()).resolve(maybeRelative).toString() } catch (e: Exception) { maybeRelative }

    /** Strip chrome, score candidate containers, keep the densest article body. */
    private fun extractReadableHtml(doc: Document): String {
        doc.select(
            "script, style, noscript, nav, aside, footer, header, form, iframe, svg, button, " +
                "[role=navigation], [role=banner], [role=complementary], [aria-hidden=true], " +
                ".ad, .ads, .advertisement, .share, .social, .newsletter, .comments, .comment, " +
                ".related, .promo, .cookie, .subscribe, .sidebar, .menu, .nav, .footer, .header"
        ).remove()

        val candidates = doc.select(
            "article, main, [role=main], .post, .article, .entry-content, .post-content, " +
                ".article-content, .story-body, .content, #content"
        )
        val best = candidates.maxByOrNull { scoreElement(it) }
        val container = if (best != null && scoreElement(best) > 0) best else doc.body()

        // Make images and links absolute so they render outside the origin.
        container?.select("[src]")?.forEach { it.attr("src", it.absUrl("src")) }
        container?.select("[href]")?.forEach { it.attr("href", it.absUrl("href")) }

        return container?.html() ?: ""
    }

    /** Cheap readability score: paragraph density + text length. */
    private fun scoreElement(el: Element): Int {
        val paragraphs = el.select("p").size
        val textLen = el.text().length
        return paragraphs * 15 + textLen / 50
    }

    /**
     * Optional on-device summary. LocalAiManager reports failures AS failures: the chat wrapper used
     * here before turned them into an "unavailable" sentence, which was then written into the note
     * as its summary.
     */
    private suspend fun summarize(markdownBody: String): String? {
        if (markdownBody.length <= 500) return null
        val prompt = "Résume cet article en 3 puces concises, en français :\n\n" +
            "${markdownBody.take(4000)}\n\n${AiLanguage.DIRECTIVE}"
        return localAiManager.generateResponse(prompt).getOrNull()?.trim()?.ifBlank { null }
    }

    /** Collapse excess blank lines and stray whitespace left by the HTML→MD converter. */
    private fun tidyMarkdown(md: String): String =
        md.replace(Regex("\\n{3,}"), "\n\n")
            .replace(Regex("[ \\t]+\\n"), "\n")
            .trim()

    private fun buildNote(meta: ArticleMeta, summary: String?, body: String): String {
        val today = java.time.LocalDate.now()
        val sb = StringBuilder()

        // Frontmatter: the vault's standard keys first (the desktop linter checks them), then the
        // clipping's own metadata.
        sb.append(
            VaultSchema.frontmatter(
                title = meta.title,
                path = "${VaultSchema.INBOX_FOLDER}/clipping.md",
                date = today,
                tags = listOf("clipping", "web"),
                extra = buildList {
                    add("source" to VaultSchema.doubleQuoted(meta.url))
                    meta.author?.let { add("author" to VaultSchema.doubleQuoted(it)) }
                    meta.published?.let { add("published" to VaultSchema.doubleQuoted(it)) }
                    add("captured" to VaultSchema.singleQuoted(today.toString()))
                }
            )
        )
        sb.append('\n')

        // Title
        sb.append("# ${meta.title.replace("\r", " ").replace("\n", " ").trim()}\n\n")

        // Source callout (renders via the app's ObsidianCalloutPlugin)
        val sourceLabel = meta.siteName ?: java.net.URI(meta.url).host ?: meta.url
        val bits = buildList {
            add("[$sourceLabel](${meta.url})")
            meta.author?.let { add(it) }
            meta.published?.let { add(it.take(10)) }
        }
        sb.append("> [!info] Source\n")
        sb.append("> ${bits.joinToString("  ·  ")}\n\n")

        // Hero image
        meta.heroImage?.let { sb.append("![]($it)\n\n") }

        // AI summary callout
        if (!summary.isNullOrBlank()) {
            sb.append("> [!abstract] Summary\n")
            summary.lines().forEach { line -> sb.append("> ${line.trimEnd()}\n") }
            sb.append("\n")
        }

        sb.append("---\n\n")
        sb.append(body).append("\n")
        return sb.toString()
    }

    private companion object {
        const val TAG = "ImportWorker"
        const val DESKTOP_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36"
    }
}
