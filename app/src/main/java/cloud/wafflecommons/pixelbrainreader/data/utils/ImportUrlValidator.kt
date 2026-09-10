package cloud.wafflecommons.pixelbrainreader.data.utils

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI

/**
 * Validates an untrusted import URL (the public, BROWSABLE `pixelbrain://import?url=…` deep link and
 * shared links). It rejects non-http(s) schemes, over-long URLs, and internal / loopback / link-local
 * / private targets, so a link can't be used as an SSRF / arbitrary-fetch primitive into the LAN or
 * cloud metadata endpoints.
 *
 * [isSafe] is pure JVM and does NO DNS resolution (it runs on the main thread): it classifies the
 * host as written — including IPv6 literals and every inet_aton spelling of an IPv4 address
 * (`2130706433`, `0177.0.0.1`, `0x7f.1`), which Android's resolver accepts as numeric addresses.
 * [resolvesToPublicAddresses] adds the DNS check for code that performs the fetch, off the main
 * thread. The user-facing confirmation dialog, which shows the host, is the final backstop.
 */
object ImportUrlValidator {

    private const val MAX_LEN = 2048

    private val blockedHostSuffixes = listOf(".localhost", ".local", ".internal", ".lan", ".home.arpa")

    fun isSafe(raw: String?): Boolean {
        if (raw.isNullOrBlank() || raw.length > MAX_LEN) return false
        val uri = try { URI(raw) } catch (e: Exception) { return false }
        val scheme = uri.scheme?.lowercase() ?: return false
        if (scheme != "http" && scheme != "https") return false
        val host = normalizeHost(uri.host) ?: return false
        if (host == "localhost" || blockedHostSuffixes.any { host.endsWith(it) }) return false
        literalAddress(host)?.let { return isPublicAddress(it) }
        return true
    }

    /**
     * Resolves the host of [url] and requires EVERY address to be public (a name pointing at the LAN
     * is refused too). Blocking DNS: never call it on the main thread.
     */
    fun resolvesToPublicAddresses(url: String): Boolean {
        val host = try { normalizeHost(URI(url).host) } catch (e: Exception) { null } ?: return false
        val addresses = try { InetAddress.getAllByName(host) } catch (e: Exception) { return false }
        return addresses.isNotEmpty() && addresses.all { isPublicAddress(it) }
    }

    private fun normalizeHost(host: String?): String? =
        host?.lowercase()
            ?.removePrefix("[")?.removeSuffix("]")
            ?.trimEnd('.')
            ?.takeIf { it.isNotBlank() }

    /** The address a host spells WITHOUT any DNS lookup — an IPv6 or IPv4 literal — or null for a name. */
    private fun literalAddress(host: String): InetAddress? {
        if (':' in host) {
            // An IPv6 literal: parsing a literal never touches DNS.
            return try { InetAddress.getByName(host) } catch (e: Exception) { null }
        }
        return parseIpv4(host)?.let { InetAddress.getByAddress(it) }
    }

    /** IPv4 in any inet_aton form (1–4 dot-separated parts, each decimal, octal `0…` or hex `0x…`), or null. */
    private fun parseIpv4(host: String): ByteArray? {
        val parts = host.split('.')
        if (parts.size > 4 || parts.any { it.isEmpty() }) return null
        val values = parts.map { parseIpv4Part(it) ?: return null }

        // Every part but the last is one byte; the last one fills the remaining bytes.
        var address = 0L
        for (value in values.dropLast(1)) {
            if (value > 0xFF) return null
            address = (address shl 8) or value
        }
        val lastBits = 8 * (5 - parts.size)
        val last = values.last()
        if (last >= (1L shl lastBits)) return null
        address = (address shl lastBits) or last
        return ByteArray(4) { i -> (address shr (24 - 8 * i)).toByte() }
    }

    private fun parseIpv4Part(part: String): Long? = try {
        when {
            part.startsWith("0x") || part.startsWith("0X") ->
                part.substring(2).takeIf { it.isNotEmpty() && it.length <= 8 }?.toLong(16)
            part.length > 1 && part.startsWith("0") ->
                part.substring(1).takeIf { digits -> digits.length <= 11 && digits.all { it in '0'..'7' } }?.toLong(8)
            else -> part.takeIf { it.length <= 10 && it.all { c -> c.isDigit() } }?.toLong()
        }
    } catch (e: NumberFormatException) {
        null
    }

    private fun isPublicAddress(address: InetAddress): Boolean {
        if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
            address.isSiteLocalAddress || address.isMulticastAddress
        ) return false
        val bytes = address.address
        return when (address) {
            is Inet4Address -> isPublicIpv4(bytes)
            is Inet6Address -> {
                val first = bytes[0].toInt() and 0xFF
                val embeddedIpv4 = address.isIPv4CompatibleAddress ||
                    (bytes.take(10).all { it.toInt() == 0 } && bytes[10].toInt() and 0xFF == 0xFF && bytes[11].toInt() and 0xFF == 0xFF)
                when {
                    first and 0xFE == 0xFC -> false // fc00::/7 unique local
                    embeddedIpv4 -> isPublicIpv4(bytes.copyOfRange(12, 16))
                    else -> true
                }
            }
            else -> false
        }
    }

    private fun isPublicIpv4(bytes: ByteArray): Boolean {
        val a = bytes[0].toInt() and 0xFF
        val b = bytes[1].toInt() and 0xFF
        val c = bytes[2].toInt() and 0xFF
        return when {
            a == 0 -> false                          // 0.0.0.0/8 "this network"
            a == 10 -> false                         // private
            a == 100 && b in 64..127 -> false        // 100.64.0.0/10 carrier-grade NAT
            a == 127 -> false                        // loopback
            a == 169 && b == 254 -> false            // link-local (cloud metadata)
            a == 172 && b in 16..31 -> false         // private
            a == 192 && b == 0 && c == 0 -> false    // 192.0.0.0/24 IETF protocol assignments
            a == 192 && b == 168 -> false            // private
            a == 198 && b in 18..19 -> false         // 198.18.0.0/15 benchmarking
            a >= 224 -> false                        // multicast, reserved, broadcast
            else -> true
        }
    }
}
