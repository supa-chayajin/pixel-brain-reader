package cloud.wafflecommons.pixelbrainreader.data.utils

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.IOException
import java.net.URI

/** A fetch was refused because a hop targets a non-public address — not worth retrying. */
class BlockedAddressException(message: String) : IOException(message)

/**
 * Fetches an untrusted web page with the SSRF guard applied to EVERY hop: each URL must pass
 * [ImportUrlValidator.isSafe] and its host must resolve only to public addresses. Jsoup's own
 * redirect following meant only the first URL was ever validated, so any public page could bounce
 * the fetch into the LAN or a cloud metadata endpoint.
 *
 * Blocking (DNS + network): call it off the main thread.
 */
object SafeWebFetcher {

    private const val MAX_REDIRECTS = 5

    fun fetch(url: String, userAgent: String, timeoutMs: Int): Document {
        var current = url
        repeat(MAX_REDIRECTS + 1) {
            if (!ImportUrlValidator.isSafe(current) || !ImportUrlValidator.resolvesToPublicAddresses(current)) {
                throw BlockedAddressException("Refusing to fetch a non-public address")
            }
            val response = Jsoup.connect(current)
                .userAgent(userAgent)
                .timeout(timeoutMs)
                .followRedirects(false)
                .execute()
            if (response.statusCode() !in 300..399) return response.parse()

            val location = response.header("Location")
                ?: throw IOException("Redirect without a Location header")
            current = URI(current).resolve(location.trim()).toString()
        }
        throw IOException("Too many redirects")
    }
}
