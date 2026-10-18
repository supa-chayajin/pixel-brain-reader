package cloud.wafflecommons.pixelbrainreader.data.web

import android.annotation.SuppressLint
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
import kotlin.coroutines.resume

/**
 * The WebView client of every page opened for an import (off screen or in the share screen): the
 * page navigates only to public http(s) addresses, and its requests to private or local ones get a
 * 403 — a shared link must not reach the device's network neighbours.
 */
open class GuardedWebViewClient : WebViewClient() {

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
        request.isForMainFrame && !ImportUrlValidator.isSafe(request.url.toString())

    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
        val target = request.url.toString()
        val web = target.startsWith("http://", ignoreCase = true) || target.startsWith("https://", ignoreCase = true)
        return if (web && !ImportUrlValidator.isSafe(target)) {
            WebResourceResponse("text/plain", "utf-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(ByteArray(0)))
        } else {
            null
        }
    }
}

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
}

/** The page's DOM as it stands now (scripts run, checks passed), or null when it can't be read. Call it on the main thread. */
suspend fun WebView.readDom(): String? {
    val encoded = suspendCancellableCoroutine<String?> { cont ->
        evaluateJavascript("(function(){return document.documentElement.outerHTML;})()") { cont.resume(it) }
    } ?: return null
    // A page's DOM runs to megabytes: decoded off the main thread.
    return withContext(Dispatchers.Default) { runCatching { Json.decodeFromString<String>(encoded) }.getOrNull() }
}
