package cloud.wafflecommons.pixelbrainreader.data.guard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The confidentiality guard's matching rules. Secret samples are assembled at run time so that no
 * secret scanner mistakes this public test file for a leak.
 */
class ConfidentialityScannerTest {

    private val names = GuardSettings(terms = listOf("Acme", "Jean Dupont"), detectSecrets = false)
    private val secrets = GuardSettings(terms = emptyList(), detectSecrets = true)

    private fun added(text: String, path: String = NOTE) =
        StagedText(path = path, oldPath = null, oldText = null, newText = text, isNewPath = false)

    private fun edited(old: String, new: String, path: String = NOTE) =
        StagedText(path = path, oldPath = null, oldText = old, newText = new, isNewPath = false)

    @Test
    fun `folding drops case and accents and keeps every offset`() {
        val text = "Éléonore ÇA va"
        val folded = TextFold.fold(text)
        assertEquals("eleonore ca va", folded)
        assertEquals(text.length, folded.length)
    }

    @Test
    fun `a protected name is found as a whole word, ignoring case and accents`() {
        val findings = ConfidentialityScanner.scan(listOf(added("Intro\nRéunion avec ACMÉ demain\n")), names)

        assertEquals(1, findings.size)
        with(findings.single()) {
            assertEquals(FindingKind.NAME, kind)
            assertEquals("term:acme", ruleKey)
            assertEquals(2, line)
            assertTrue(excerpt, excerpt.contains("ACMÉ"))
        }
    }

    @Test
    fun `part of a longer word is not a match, a hyphenated one is`() {
        assertTrue(ConfidentialityScanner.scan(listOf(added("acmeco and macme\n")), names).isEmpty())
        assertEquals(1, ConfidentialityScanner.scan(listOf(added("the Acme-Corp deal\n")), names).size)
    }

    @Test
    fun `a two-word name matches across a non-breaking space`() {
        val findings = ConfidentialityScanner.scan(listOf(added("Call Jean Dupont back\n")), names)
        assertEquals(listOf("term:jean dupont"), findings.map { it.ruleKey })
    }

    @Test
    fun `only the occurrences a change adds are reported`() {
        val before = "Acme kickoff\n"
        assertTrue(ConfidentialityScanner.scan(listOf(edited(before, "Acme kickoff\nNotes\n")), names).isEmpty())

        val findings = ConfidentialityScanner.scan(listOf(edited(before, "Acme kickoff\nCall Acme back\n")), names)
        assertEquals(1, findings.size)
        assertEquals(2, findings.single().line)
    }

    @Test
    fun `line numbers survive CRLF line endings`() {
        val findings = ConfidentialityScanner.scan(listOf(edited("a\r\n", "a\r\nb\r\nAcme\r\n")), names)
        assertEquals(3, findings.single().line)
        assertEquals("Acme", findings.single().excerpt)
    }

    @Test
    fun `a new file's name is checked, an existing file's is not`() {
        val path = "20_Projects/Acme/brief.md"
        val created = StagedText(path, oldPath = null, oldText = null, newText = "hello\n", isNewPath = true)
        val findings = ConfidentialityScanner.scan(listOf(created), names)
        assertEquals(1, findings.size)
        assertEquals(0, findings.single().line)
        assertEquals(path, findings.single().excerpt)

        assertTrue(ConfidentialityScanner.scan(listOf(edited("hello\n", "hello again\n", path)), names).isEmpty())
    }

    @Test
    fun `a rename does not report a name the old path already had`() {
        val renamed = StagedText(
            path = "20_Projects/Acme/b.md",
            oldPath = "20_Projects/Acme/a.md",
            oldText = "same\n",
            newText = "same\n",
            isNewPath = true
        )
        assertTrue(ConfidentialityScanner.scan(listOf(renamed), names).isEmpty())
    }

    @Test
    fun `content that can't be read as text still has its name checked`() {
        val binary = StagedText("assets/acme-logo.png", oldPath = null, oldText = null, newText = null, isNewPath = true)
        val findings = ConfidentialityScanner.scan(listOf(binary), names)
        assertEquals(listOf(0), findings.map { it.line })
    }

    @Test
    fun `known secret formats are found and masked`() {
        val samples = mapOf(
            "GitHub token" to "gh" + "p_" + "aB3dE5fG7hJ9kL1mN3pQ5rS7tU9vW1xY3zA5",
            "AWS access key" to "AKIA" + "Z7Q2M4X9P1L6K3W8",
            "Slack token" to "xox" + "b-" + "1234567890-abcdefghij",
            "Stripe key" to "sk_" + "live_" + "4eC39HqLyjWDarjtT1zdp7dc",
            "AI API key" to "sk-" + "proj-" + "Ab3Cd5Ef7Gh9Ij1Kl3Mn5Op7Qr9St1Uv3Wx5",
            "JSON Web Token" to "eyJ" + "hbGciOiJIUzI1NiJ9" + ".eyJ" + "zdWIiOiIxMjM0NTY3ODkwIn0" + "." + "abcDEF123ghiJKL456",
            "Google API key" to "AI" + "za" + "SyD3x8Qm2Lk9Vb7Nc5Hj1Gf4Ds6Ap0Zr8Tq"
        )
        samples.forEach { (label, secret) ->
            val findings = ConfidentialityScanner.scan(listOf(added("see $secret here\n")), secrets)
            assertEquals(label, listOf(label), findings.map { it.label })
            assertFalse("$label leaked in the excerpt", findings.single().excerpt.contains(secret))
        }
    }

    @Test
    fun `a private key header is reported as is`() {
        val header = "-----BEGIN " + "OPENSSH PRIVATE KEY-----"
        val findings = ConfidentialityScanner.scan(listOf(added("$header\nb3BlbnNzaC1rZXktdjE\n")), secrets)
        assertEquals("Private key", findings.single().label)
        assertEquals(header, findings.single().excerpt)
    }

    @Test
    fun `passwords in URLs and assignments are found, placeholders and code are not`() {
        val reported = listOf(
            "postgres://app:" + "Kx8mP2qL9" + "@db.prod.internal:5432/app",
            "DB_PASSWORD=" + "hunter2hunter2",
            "\"api_key\": \"" + "3f9a8b7c6d5e4f3a2b1c" + "\"",
            "SECRET_KEY = '" + "Summer2026!x" + "'"
        )
        reported.forEach { line ->
            assertEquals(line, 1, ConfidentialityScanner.scan(listOf(added("$line\n")), secrets).size)
        }
        val ignored = listOf(
            "postgres://postgres:postgres@localhost:5432/app",
            "https://user:" + "\${DB_PASSWORD}" + "@db.example.com",
            "password: changeme",
            "val token = getToken()",
            "apiKey: process.env.API_KEY",
            "token_count: 12345678",
            "AKIA" + "IOSFODNN7EXAMPLE",
            "password: <your password>"
        )
        ignored.forEach { line ->
            assertTrue(line, ConfidentialityScanner.scan(listOf(added("$line\n")), secrets).isEmpty())
        }
    }

    @Test
    fun `a specific rule wins over the generic assignment`() {
        val token = "gh" + "p_" + "aB3dE5fG7hJ9kL1mN3pQ5rS7tU9vW1xY3zA5"
        val findings = ConfidentialityScanner.scan(listOf(added("GITHUB_TOKEN=$token\n")), secrets)
        assertEquals(listOf("GitHub token"), findings.map { it.label })
    }

    @Test
    fun `an allowed finding lets the commit through, in its note or its folder`() {
        val change = added("Call Acme back\n", path = "20_Projects/x.md")
        assertEquals(1, ConfidentialityScanner.review(listOf(change), names).size)

        val inNote = names.copy(allowed = listOf(AllowEntry("20_Projects/x.md", "term:acme")))
        assertTrue(ConfidentialityScanner.review(listOf(change), inNote).isEmpty())

        val inFolder = names.copy(allowed = listOf(AllowEntry("20_Projects/", "term:acme")))
        assertTrue(ConfidentialityScanner.review(listOf(change), inFolder).isEmpty())
        val elsewhere = added("Call Acme back\n", path = "200_Projects/x.md")
        assertEquals(1, ConfidentialityScanner.review(listOf(elsewhere), inFolder).size)
    }

    @Test
    fun `a settings change releases the findings it no longer covers`() {
        val token = "AKIA" + "Z7Q2M4X9P1L6K3W8"
        val both = GuardSettings(terms = listOf("Acme"), detectSecrets = true)
        val findings = ConfidentialityScanner.scan(listOf(added("Acme $token\n")), both)
        assertEquals(2, findings.size)

        assertEquals(listOf(FindingKind.SECRET), ConfidentialityScanner.stillHeld(findings, both.copy(terms = emptyList())).map { it.kind })
        assertEquals(listOf(FindingKind.NAME), ConfidentialityScanner.stillHeld(findings, both.copy(detectSecrets = false)).map { it.kind })
    }

    @Test
    fun `nothing to look for reports nothing`() {
        val off = GuardSettings(terms = emptyList(), detectSecrets = false)
        assertTrue(ConfidentialityScanner.scan(listOf(added("Acme " + "AKIA" + "Z7Q2M4X9P1L6K3W8\n")), off).isEmpty())
    }

    @Test
    fun `too short a term is refused`() {
        assertEquals(null, TextFold.term(" a "))
        assertEquals("jean dupont", TextFold.term("  Jean   Dupont "))
    }

    @Test
    fun `a commit message naming a protected term is recognised`() {
        assertTrue(ConfidentialityScanner.mentionsAny("Delete 20_Projects/Acme/brief.md", listOf("acme")))
        assertFalse(ConfidentialityScanner.mentionsAny("journal(2026-10-18): update daily note", listOf("acme")))
    }

    private companion object {
        const val NOTE = "00_Inbox/note.md"
    }
}
