package cloud.wafflecommons.pixelbrainreader.data.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Guards the pixelbrain://import deep link and shared links against SSRF / arbitrary-fetch abuse. */
class ImportUrlValidatorTest {

    @Test
    fun `accepts normal public https and http urls`() {
        assertTrue(ImportUrlValidator.isSafe("https://example.com/article"))
        assertTrue(ImportUrlValidator.isSafe("http://blog.example.org/post?id=1"))
        assertTrue(ImportUrlValidator.isSafe("https://sub.domain.co.uk/x/y/z"))
    }

    @Test
    fun `rejects non-http schemes`() {
        assertFalse(ImportUrlValidator.isSafe("file:///etc/passwd"))
        assertFalse(ImportUrlValidator.isSafe("content://com.evil/data"))
        assertFalse(ImportUrlValidator.isSafe("javascript:alert(1)"))
        assertFalse(ImportUrlValidator.isSafe("ftp://example.com/x"))
    }

    @Test
    fun `rejects loopback and localhost`() {
        assertFalse(ImportUrlValidator.isSafe("http://localhost/admin"))
        assertFalse(ImportUrlValidator.isSafe("http://127.0.0.1:8080/"))
        assertFalse(ImportUrlValidator.isSafe("http://127.1.2.3/"))
        assertFalse(ImportUrlValidator.isSafe("http://localhost./"))
        assertFalse(ImportUrlValidator.isSafe("http://api.localhost/"))
    }

    @Test
    fun `rejects private and link-local ranges including cloud metadata`() {
        assertFalse(ImportUrlValidator.isSafe("http://10.0.0.5/secret"))
        assertFalse(ImportUrlValidator.isSafe("http://192.168.1.1/router"))
        assertFalse(ImportUrlValidator.isSafe("http://172.16.0.1/"))
        assertFalse(ImportUrlValidator.isSafe("http://172.31.255.255/"))
        assertFalse(ImportUrlValidator.isSafe("http://169.254.169.254/latest/meta-data/")) // AWS/GCP metadata
    }

    @Test
    fun `rejects carrier-grade nat and this-network addresses`() {
        assertFalse(ImportUrlValidator.isSafe("http://100.64.0.1/"))
        assertFalse(ImportUrlValidator.isSafe("http://100.127.255.254/"))
        assertFalse(ImportUrlValidator.isSafe("http://0.0.0.0/"))
    }

    @Test
    fun `rejects alternative spellings of internal ipv4 addresses`() {
        assertFalse(ImportUrlValidator.isSafe("http://2130706433/"))   // 127.0.0.1 as one decimal number
        assertFalse(ImportUrlValidator.isSafe("http://0177.0.0.1/"))   // octal
        assertFalse(ImportUrlValidator.isSafe("http://0x7f.1/"))       // hex + short form
        assertFalse(ImportUrlValidator.isSafe("http://127.1/"))        // short form
        assertFalse(ImportUrlValidator.isSafe("http://0/"))            // 0.0.0.0
        assertFalse(ImportUrlValidator.isSafe("http://0xa9fea9fe/"))   // 169.254.169.254
    }

    @Test
    fun `rejects internal ipv6 literals`() {
        assertFalse(ImportUrlValidator.isSafe("http://[::1]/"))
        assertFalse(ImportUrlValidator.isSafe("http://[::]/"))
        assertFalse(ImportUrlValidator.isSafe("http://[fd12:3456::1]/"))       // unique local
        assertFalse(ImportUrlValidator.isSafe("http://[fe80::1]/"))            // link-local
        assertFalse(ImportUrlValidator.isSafe("http://[::ffff:127.0.0.1]/"))   // IPv4-mapped loopback
        assertFalse(ImportUrlValidator.isSafe("http://[::ffff:10.0.0.1]/"))    // IPv4-mapped private
    }

    @Test
    fun `allows public ips just outside the private ranges`() {
        assertTrue(ImportUrlValidator.isSafe("http://172.15.0.1/"))  // just below 172.16
        assertTrue(ImportUrlValidator.isSafe("http://172.32.0.1/"))  // just above 172.31
        assertTrue(ImportUrlValidator.isSafe("http://8.8.8.8/"))
        assertTrue(ImportUrlValidator.isSafe("http://100.128.0.1/")) // just above 100.64/10
        assertTrue(ImportUrlValidator.isSafe("https://[2606:4700:4700::1111]/"))
    }

    @Test
    fun `rejects internal hostnames`() {
        assertFalse(ImportUrlValidator.isSafe("http://router.local/"))
        assertFalse(ImportUrlValidator.isSafe("http://db.internal/"))
        assertFalse(ImportUrlValidator.isSafe("http://nas.lan/"))
    }

    @Test
    fun `rejects blank null and over-long urls`() {
        assertFalse(ImportUrlValidator.isSafe(null))
        assertFalse(ImportUrlValidator.isSafe(""))
        assertFalse(ImportUrlValidator.isSafe("   "))
        assertFalse(ImportUrlValidator.isSafe("https://example.com/" + "a".repeat(3000)))
    }

    @Test
    fun `rejects garbage that fails to parse`() {
        assertFalse(ImportUrlValidator.isSafe("not a url"))
        assertFalse(ImportUrlValidator.isSafe("http://"))
    }

    @Test
    fun `resolution check refuses literal internal addresses and accepts public ones`() {
        // Literal addresses resolve without touching DNS.
        assertFalse(ImportUrlValidator.resolvesToPublicAddresses("http://127.0.0.1/"))
        assertFalse(ImportUrlValidator.resolvesToPublicAddresses("http://[::1]/"))
        assertTrue(ImportUrlValidator.resolvesToPublicAddresses("http://8.8.8.8/"))
    }
}
