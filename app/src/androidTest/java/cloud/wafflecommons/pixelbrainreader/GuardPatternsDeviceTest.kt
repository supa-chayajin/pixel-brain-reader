package cloud.wafflecommons.pixelbrainreader

import androidx.test.ext.junit.runners.AndroidJUnit4
import cloud.wafflecommons.pixelbrainreader.data.guard.ConfidentialityScanner
import cloud.wafflecommons.pixelbrainreader.data.guard.GuardSettings
import cloud.wafflecommons.pixelbrainreader.data.guard.StagedText
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Compiles and runs the confidentiality guard's patterns on ANDROID's regex engine (ICU), which
 * the JVM unit tests can't do. A pattern ICU rejects would fail every commit on the device.
 * Run on the emulator: `./gradlew connectedDebugAndroidTest`.
 */
@RunWith(AndroidJUnit4::class)
class GuardPatternsDeviceTest {

    @Test
    fun secretsAndNamesAreFoundOnIcu() {
        val text = listOf(
            "Réunion avec ACMÉ",
            "token: " + "gh" + "p_" + "aB3dE5fG7hJ9kL1mN3pQ5rS7tU9vW1xY3zA5",
            "DB_PASSWORD=" + "hunter2hunter2",
            "postgres://app:" + "Kx8mP2qL9" + "@db.prod.internal/app",
            "-----BEGIN " + "RSA PRIVATE KEY-----"
        ).joinToString("\n", postfix = "\n")
        val change = StagedText("00_Inbox/x.md", oldPath = null, oldText = null, newText = text, isNewPath = false)

        val findings = ConfidentialityScanner.scan(listOf(change), GuardSettings(terms = listOf("Acme"), detectSecrets = true))

        assertEquals(
            listOf("Protected name", "GitHub token", "Password or token", "Password in a URL", "Private key"),
            findings.map { it.label }
        )
    }
}
