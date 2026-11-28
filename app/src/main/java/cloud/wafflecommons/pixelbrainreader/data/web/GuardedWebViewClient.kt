package cloud.wafflecommons.pixelbrainreader.data.web

import android.annotation.SuppressLint
import android.util.Log
import android.webkit.ServiceWorkerClient
import android.webkit.ServiceWorkerController
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import cloud.wafflecommons.pixelbrainreader.data.utils.ImportUrlValidator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.ByteArrayInputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * The WebView client of every page opened for an import (off screen or in the share screen) and of
 * a game's map: the page navigates only to public http(s) addresses, and its requests to private or
 * local ones get a 403 — a shared link must not reach the device's network neighbours. The page's
 * service workers get the same answer ([guardServiceWorkers], 13.4), their requests never reaching
 * a page's client.
 *
 * What the guard can't see (13.4, the audit's webview-lan-guard-gaps): a WebSocket connection never
 * reaches a WebViewClient (WebView has no hook for one), and an address is checked as written, with
 * no DNS lookup: a public name that points into the local network passes, and Chromium resolves the
 * name again itself anyway. The map browser keeps its main frame on https, so the WebView's own
 * mixed-content blocking stays on for every page it shows.
 */
open class GuardedWebViewClient : WebViewClient() {

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
        request.isForMainFrame && !ImportUrlValidator.isSafe(request.url.toString())

    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
        if (refusedWebRequest(request.url.toString())) blockedResponse() else null
}

/** A web address (http or https) the guard refuses: a private, local or malformed one. Other schemes are not its business. */
private fun refusedWebRequest(target: String): Boolean {
    val web = target.startsWith("http://", ignoreCase = true) || target.startsWith("https://", ignoreCase = true)
    return web && !ImportUrlValidator.isSafe(target)
}

/** The answer to a refused request: an empty 403. */
internal fun blockedResponse(): WebResourceResponse =
    WebResourceResponse("text/plain", "utf-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(ByteArray(0)))

private val serviceWorkersGuarded = AtomicBoolean(false)

/**
 * The guard for service workers (13.4): their fetches reach no page's [WebViewClient], so the
 * process's [ServiceWorkerClient] answers 403 to every web address [ImportUrlValidator.isSafe]
 * refuses, as [GuardedWebViewClient] does for the page, and their settings reach nothing local
 * (files, content providers), as [configureForImport]'s do. Process-wide and idempotent: set once,
 * the first time a guarded page is configured, on the main thread. The platform's controller
 * (API 24), not androidx.webkit's, which isn't a dependency. A controller the WebView can't give
 * is tried again at the next page.
 */
fun guardServiceWorkers() {
    if (!serviceWorkersGuarded.compareAndSet(false, true)) return
    try {
        val controller = ServiceWorkerController.getInstance()
        controller.setServiceWorkerClient(object : ServiceWorkerClient() {
            override fun shouldInterceptRequest(request: WebResourceRequest): WebResourceResponse? =
                if (refusedWebRequest(request.url.toString())) blockedResponse() else null
        })
        with(controller.serviceWorkerWebSettings) {
            allowContentAccess = false
            allowFileAccess = false
        }
    } catch (e: RuntimeException) {
        serviceWorkersGuarded.set(false)
        // The type only: nothing of a page is in it, but a message could carry an address.
        Log.w(TAG, "Service workers unguarded for now: ${e.javaClass.simpleName}")
    }
}

private const val TAG = "WebGuard"

/** Scripts on, nothing local reachable (files, content providers); [loadImages] off for the off-screen read. */
@SuppressLint("SetJavaScriptEnabled")
fun WebView.configureForImport(loadImages: Boolean) {
    with(settings) {
        javaScriptEnabled = true
        domStorageEnabled = true
        allowFileAccess = false
        allowContentAccess = false
        blockNetworkImage = !loadImages
        loadsImagesAutomatically = loadImages
        mediaPlaybackRequiresUserGesture = true
    }
    // 13.4: a page's service workers are held to the same guard as the page.
    guardServiceWorkers()
}

/** The page's DOM as it stands now (scripts run, checks passed), or null when it can't be read. Call it on the main thread. */
suspend fun WebView.readDom(): String? {
    val encoded = suspendCancellableCoroutine<String?> { cont ->
        evaluateJavascript("(function(){return document.documentElement.outerHTML;})()") { cont.resume(it) }
    } ?: return null
    // A page's DOM runs to megabytes: decoded off the main thread.
    return withContext(Dispatchers.Default) { runCatching { Json.decodeFromString<String>(encoded) }.getOrNull() }
}
