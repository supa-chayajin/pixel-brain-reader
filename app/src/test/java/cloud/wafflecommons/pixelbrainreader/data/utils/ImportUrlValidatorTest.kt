package cloud.wafflecommons.pixelbrainreader.data.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Guards the pixelbrain://import deep link against SSRF / arbitrary-fetch abuse (P1.1). */
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
    fun `allows public ips just outside the private ranges`() {
        assertTrue(ImportUrlValidator.isSafe("http://172.15.0.1/"))  // just below 172.16
        assertTrue(ImportUrlValidator.isSafe("http://172.32.0.1/"))  // just above 172.31
        assertTrue(ImportUrlValidator.isSafe("http://8.8.8.8/"))
    }

    @Test
    fun `rejects internal hostnames`() {
        assertFalse(ImportUrlValidator.isSafe("http://router.local/"))
        assertFalse(ImportUrlValidator.isSafe("http://db.internal/"))
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
}
