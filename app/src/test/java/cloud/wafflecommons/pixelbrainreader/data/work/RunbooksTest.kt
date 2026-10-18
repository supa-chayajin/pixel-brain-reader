package cloud.wafflecommons.pixelbrainreader.data.work

import cloud.wafflecommons.pixelbrainreader.data.vault.CodeBlocks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class RunbooksTest {

    private val runbook = """
        ---
        title: "Redémarrer l'API"
        tags:
          - runbook
        ---
        # Redémarrer l'API

        ## Vérifier l'état
        Regarder les sondes.
        ```bash
        # not a heading
        kubectl get pods
        ```
        ## Redémarrer
        ```bash
        kubectl rollout restart deploy/api
        ```
        ### Contrôler
        ````text
        nested ``` fence stays code
        ````
    """.trimIndent()

    @Test
    fun `code blocks keep their language, heading and line`() {
        val blocks = CodeBlocks.extract("r.md", runbook)
        assertEquals(listOf("bash", "bash", "text"), blocks.map { it.language })
        assertEquals("# not a heading\nkubectl get pods", blocks[0].code)
        assertEquals(listOf("Vérifier l'état", "Redémarrer", "Contrôler"), blocks.map { it.heading })
        assertEquals("nested ``` fence stays code", blocks[2].code)
        assertEquals(10, blocks[0].line) // the opening fence, 1-based
    }

    @Test
    fun `sections are the steps, a list when there are none`() {
        val steps = Runbooks.steps("r.md", runbook)
        assertEquals(listOf("Vérifier l'état", "Redémarrer", "Contrôler"), steps.map { it.title })
        assertEquals(1, steps[0].code.size)

        val list = Runbooks.steps("l.md", "# Titre\n1. Couper le trafic\n2. [ ] Purger\n- Relancer")
        assertEquals(listOf("Couper le trafic", "Purger", "Relancer"), list.map { it.title })
    }

    @Test
    fun `a runbook is tagged or lives in a Runbooks folder`() {
        assertTrue(Runbooks.isRunbook(WorkNote.from("x.md", runbook)))
        assertTrue(Runbooks.isRunbook(WorkNote.from("40_Resources/Runbooks/y.md", "# y")))
    }

    @Test
    fun `the incident log lists each step with its status, time and note`() {
        val note = WorkNote.from("40_Resources/Runbooks/Redémarrer l'API.md", runbook)
        val steps = Runbooks.steps(note.path, runbook)
        val start = LocalDateTime.of(2026, 10, 18, 14, 32)
        val results = listOf(
            StepResult(steps[0], StepStatus.DONE, start.plusMinutes(2), "3 pods KO"),
            StepResult(steps[1], StepStatus.FAILED, start.plusMinutes(5), null),
            StepResult(steps[2], StepStatus.SKIPPED, start.plusMinutes(6), null)
        )
        val log = Runbooks.incidentLog(note, start, results, start.plusMinutes(7), "Rollback fait", LocalDate.of(2026, 10, 18))

        assertEquals("00_Inbox/Incident Redémarrer l'API 2026-10-18 14h32.md", Runbooks.incidentPath(note, start))
        assertTrue(log.contains("type: inbox\n"))
        assertTrue(log.contains("**Bilan** : 1 fait · 1 en échec · 1 passé"))
        assertTrue(log.contains("1. ✅ Vérifier l'état — fait à 14:34 — 3 pods KO\n"))
        assertTrue(log.contains("2. ❌ Redémarrer — échec à 14:37\n"))
        assertTrue(log.contains("## Notes\n\nRollback fait\n"))
    }
}
