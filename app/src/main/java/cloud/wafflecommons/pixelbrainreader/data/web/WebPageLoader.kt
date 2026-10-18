package cloud.wafflecommons.pixelbrainreader.data.web

import android.content.Context
import android.os.SystemClock
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import cloud.wafflecommons.pixelbrainreader.data.utils.ImportUrlValidator
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.jsoup.HttpStatusException
import org.jsoup.Jsoup
import org.jsoup.UnsupportedMimeTypeException
import java.io.IOException
import java.net.URI
import javax.inject.Inject
import javax.inject.Singleton

/** A page's HTML and the address it ended at (after redirects). */
data class LoadedPage(val html: String, val url: String)

/** Why a page couldn't be read. [worthABrowser]: a real browser may still get it (bot checks, refusals, flaky networks). */
enum class PageProblem(val worthABrowser: Boolean) {
    NOT_PUBLIC(false), NOT_HTML(false), GONE(false), PAYWALL(true), REFUSED(true), BLOCKED(true), SERVER(true), NETWORK(true), NO_TEXT(false)
}

/** A page that couldn't be read: the [problem], the HTTP [status] when the site answered, a technical [detail]. */
class PageUnavailableException(val problem: PageProblem, val status: Int? = null, val detail: String? = null) :
    IOException("$problem${status?.let { " (HTTP $it)" }.orEmpty()}${detail?.let { ": $it" }.orEmpty()}")

/**
 * Loads a web page for an import, the SSRF guard applied to every address read (ANY app can share a
 * link to us). First a plain download, as a desktop browser would ask for it; when the site refuses
 * it (bot protection, 403/429) or builds its text with scripts, an off-screen WebView renders the
 * page as a real browser and hands its DOM over. The clip with the most text wins.
 */
@Singleton
class WebPageLoader @Inject constructor(@param:ApplicationContext private val context: Context) {

    /** Reads [url] as an article; [onBrowser] is called when the browser render starts (it takes a few seconds). */
    suspend fun load(url: String, onBrowser: () -> Unit = {}): WebClip {
        VideoEmbed.canonical(url)?.let { video -> videoClip(video)?.let { return it } }
        var failure: PageUnavailableException? = null
        val direct = try {
            download(url)
        } catch (e: CancellationException) {
            throw e
        } catch (e: PageUnavailableException) {
            if (!e.problem.worthABrowser) throw e
            failure = e
            null
        } catch (e: IOException) {
            failure = PageUnavailableException(PageProblem.NETWORK, detail = e.message)
            null
        }
        val directClip = direct?.let { clipOf(it) }
        if (directClip != null && directClip.quality == ClipQuality.FULL) return directClip
        onBrowser()
        val renderedClip = try {
            clipOf(render(url))
        } catch (e: CancellationException) {
            throw e
        } catch (e: PageUnavailableException) {
            // "A human check" says more than the download's bare 403.
            if (failure == null || e.problem == PageProblem.BLOCKED) failure = e
            null
        } catch (e: Exception) {
            // No WebView on this device, or it crashed: the download's result stands.
            null
        }
        return listOfNotNull(directClip, renderedClip).maxByOrNull { it.words }
            ?: throw failure ?: PageUnavailableException(PageProblem.BLOCKED)
    }

    /** The plain download: redirects (HTTP and meta refresh) followed by hand, each one guarded, cookies carried. */
    suspend fun download(url: String): LoadedPage = withContext(Dispatchers.IO) {
        var current = url
        val cookies = mutableMapOf<String, String>()
        repeat(MAX_REDIRECTS + 1) {
            guard(current)
            val response = try {
                Jsoup.connect(current)
                    .userAgent(DESKTOP_USER_AGENT)
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .header("Accept-Language", "fr-FR,fr;q=0.9,en-US;q=0.8,en;q=0.7")
                    .header("Upgrade-Insecure-Requests", "1")
                    .cookies(cookies)
                    .timeout(TIMEOUT_MS)
                    .maxBodySize(MAX_BYTES)
                    .followRedirects(false)
                    .execute()
            } catch (e: HttpStatusException) {
                throw PageUnavailableException(problemFor(e.statusCode), e.statusCode)
            } catch (e: UnsupportedMimeTypeException) {
                throw PageUnavailableException(PageProblem.NOT_HTML, detail = e.mimeType)
            }
            cookies.putAll(response.cookies())
            if (response.statusCode() in 300..399) {
                val location = response.header("Location") ?: throw PageUnavailableException(PageProblem.SERVER, response.statusCode())
                current = URI(current).resolve(location.trim()).toString()
                // A consent wall (consent.google.com, consent.youtube.com…): only a browser gets past it.
                if (URI(current).host?.startsWith("consent.") == true) throw PageUnavailableException(PageProblem.BLOCKED)
                return@repeat
            }
            val html = response.body()
            val refresh = metaRefresh(html, current)
            if (refresh != null) {
                current = refresh
                return@repeat
            }
            if (WebClipper.looksBlocked(html)) throw PageUnavailableException(PageProblem.BLOCKED, response.statusCode())
            return@withContext LoadedPage(html, current)
        }
        throw PageUnavailableException(PageProblem.NETWORK, detail = "too many redirects")
    }

    /**
     * Renders [url] in an off-screen WebView and reads its DOM until the article is there: a bot
     * check that clears itself reloads the page, and script-built pages fill in after "finished".
     * Images aren't loaded, local files and the device's network neighbours are out of reach, and
     * only http(s) navigation to public addresses is followed.
     */
    suspend fun render(url: String): LoadedPage {
        guard(url)
        var best: Pair<LoadedPage, Int>? = null
        var failed = false
        withContext(Dispatchers.Main) {
            val webView = WebView(context)
            val finished = Channel<Boolean>(Channel.CONFLATED)
            try {
                webView.configureForImport(loadImages = false)
                webView.webViewClient = SignallingClient(finished)
                // A viewport, or pages that measure the window lay out (and load) nothing.
                webView.layout(0, 0, VIEWPORT_WIDTH, VIEWPORT_HEIGHT)
                webView.loadUrl(url)
                withTimeoutOrNull(RENDER_TIMEOUT_MS) {
                    if (!finished.receive()) {
                        failed = true
                        return@withTimeoutOrNull
                    }
                    var previous = -1
                    var blockedSince = 0L
                    while (true) {
                        delay(SETTLE_MS)
                        val html = webView.readDom() ?: continue
                        val page = LoadedPage(html, webView.url ?: url)
                        val clip = withContext(Dispatchers.Default) {
                            if (WebClipper.looksBlocked(html)) null else WebClipper.clip(html, page.url)
                        }
                        // A check that clears itself does so in seconds; one that waits for a human never will.
                        if (clip == null) {
                            val now = SystemClock.elapsedRealtime()
                            if (blockedSince == 0L) blockedSince = now else if (now - blockedSince > BLOCKED_GIVE_UP_MS) break
                        } else {
                            blockedSince = 0L
                        }
                        val words = clip?.words ?: BLOCKED_PAGE
                        if (words > (best?.second ?: BLOCKED_PAGE)) best = page to words
                        if (clip?.quality == ClipQuality.FULL) break
                        // Settled: two reads alike once the page stopped loading.
                        if (words != BLOCKED_PAGE && words == previous && webView.progress == 100) break
                        previous = words
                    }
                }
            } finally {
                webView.stopLoading()
                webView.destroy()
            }
        }
        val page = best?.first ?: throw PageUnavailableException(if (failed) PageProblem.NETWORK else PageProblem.BLOCKED)
        // The page may have moved on (scripts, redirects): keep it only if it is public too.
        guard(page.url)
        return page
    }

    /** A video's card from its platform's oEmbed answer (title, channel, thumbnail), with the page's description when it reads. */
    private suspend fun videoClip(video: String): WebClip? {
        val endpoint = VideoEmbed.endpoint(video) ?: return null
        return try {
            guard(endpoint)
            val oEmbed = withContext(Dispatchers.IO) {
                Jsoup.connect(endpoint).ignoreContentType(true).userAgent(DESKTOP_USER_AGENT).timeout(TIMEOUT_MS).maxBodySize(MAX_BYTES).execute().body()
            }
            // The page's description is a bonus: it never holds the card up for long.
            val description = try {
                withTimeoutOrNull(DESCRIPTION_TIMEOUT_MS) {
                    download(video).let { page -> withContext(Dispatchers.Default) { WebClipper.describe(page.html, page.url) } }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                null
            }
            VideoEmbed.clip(oEmbed, video, description)
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            // No oEmbed answer: the page is read like any other.
            null
        }
    }

    /** A page the user opened in the share screen (past a human check, say), clipped as it stands. */
    suspend fun clipRendered(html: String, url: String): WebClip {
        guard(url)
        return clipOf(LoadedPage(html, url))
    }

    private suspend fun clipOf(page: LoadedPage): WebClip = withContext(Dispatchers.Default) { WebClipper.clip(page.html, page.url) }

    /** The guarded client, telling the read loop when the main frame has loaded or failed. */
    private class SignallingClient(private val finished: Channel<Boolean>) : GuardedWebViewClient() {
        override fun onPageFinished(view: WebView, url: String) {
            finished.trySend(true)
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            // Network-level failures only: a challenge page arrives as an HTTP 403/503 and must load.
            if (request.isForMainFrame) finished.trySend(false)
        }
    }

    private suspend fun guard(url: String) {
        val safe = withContext(Dispatchers.IO) { ImportUrlValidator.isSafe(url) && ImportUrlValidator.resolvesToPublicAddresses(url) }
        if (!safe) throw PageUnavailableException(PageProblem.NOT_PUBLIC)
    }

    private fun problemFor(status: Int): PageProblem = when (status) {
        401, 402 -> PageProblem.PAYWALL
        403, 429 -> PageProblem.REFUSED
        404, 410 -> PageProblem.GONE
        else -> PageProblem.SERVER
    }

    /** The target of an immediate `<meta http-equiv="refresh">` (link shorteners answer browsers so). */
    private fun metaRefresh(html: String, base: String): String? {
        val content = Jsoup.parse(html.take(HEAD_SCAN)).selectFirst("meta[http-equiv~=(?i)^refresh$]")?.attr("content") ?: return null
        val match = REFRESH.find(content) ?: return null
        if ((match.groupValues[1].toIntOrNull() ?: Int.MAX_VALUE) > MAX_REFRESH_DELAY) return null
        return runCatching { URI(base).resolve(match.groupValues[2].trim()).toString() }.getOrNull()
    }

    private companion object {
        const val MAX_REDIRECTS = 6
        const val TIMEOUT_MS = 15_000
        const val MAX_BYTES = 8 * 1024 * 1024
        const val HEAD_SCAN = 64 * 1024
        const val MAX_REFRESH_DELAY = 5
        const val RENDER_TIMEOUT_MS = 25_000L
        const val DESCRIPTION_TIMEOUT_MS = 8_000L
        const val BLOCKED_GIVE_UP_MS = 12_000L
        const val SETTLE_MS = 1_500L
        const val BLOCKED_PAGE = -1
        const val VIEWPORT_WIDTH = 1280
        const val VIEWPORT_HEIGHT = 2400
        const val DESKTOP_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0.0.0 Safari/537.36"
        val REFRESH = Regex("""^\s*(\d+)\s*[;,]\s*url\s*=\s*['"]?([^'"]+)['"]?\s*$""", RegexOption.IGNORE_CASE)
    }
}
