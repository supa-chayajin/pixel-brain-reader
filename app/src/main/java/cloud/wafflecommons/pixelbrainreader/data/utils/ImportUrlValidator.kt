package cloud.wafflecommons.pixelbrainreader.data.utils

/**
 * Validates a deep-linked import URL (pixelbrain://import?url=...). The deep link is PUBLIC
 * and BROWSABLE, so the URL is untrusted: this rejects non-http(s) schemes, over-long URLs,
 * and obvious internal/loopback/link-local targets to stop the link being used as an SSRF /
 * arbitrary-fetch primitive into the LAN or cloud metadata endpoints.
 *
 * Uses java.net.URI (not android.net.Uri) so it is pure-JVM and unit-testable, and does NO
 * DNS resolution (that would ANR on the main thread and be TOCTOU-racy). The user-facing
 * confirmation dialog — which shows the resolved host — is the final backstop.
 */
object ImportUrlValidator {

    private const val MAX_LEN = 2048

    // Literal private / loopback / link-local IPv4 ranges (LAN + 169.254.169.254 metadata).
    private val privateIpv4 = Regex("^(0|10|127|169\\.254|192\\.168|172\\.(1[6-9]|2\\d|3[01]))\\..*")

    fun isSafe(raw: String?): Boolean {
        if (raw.isNullOrBlank() || raw.length > MAX_LEN) return false
        val uri = try { java.net.URI(raw) } catch (e: Exception) { return false }
        val scheme = uri.scheme?.lowercase() ?: return false
        if (scheme != "http" && scheme != "https") return false
        val host = uri.host?.lowercase()
        if (host.isNullOrBlank()) return false
        if (host == "localhost" || host == "127.0.0.1" || host == "::1" ||
            host.endsWith(".local") || host.endsWith(".internal")) return false
        if (privateIpv4.matches(host)) return false
        return true
    }
}
