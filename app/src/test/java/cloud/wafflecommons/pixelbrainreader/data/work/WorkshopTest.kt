package cloud.wafflecommons.pixelbrainreader.data.work

import cloud.wafflecommons.pixelbrainreader.data.ai.AiLanguage
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class WorkshopTest {

    private val meeting = """
        ---
        title: "Atelier roadmap"
        ---
        # Atelier roadmap

        ## Ordre du jour

        - Tour de table
        - [ ] Priorités du trimestre
        1. Risques
        -${' '}

        ## Notes

        - pas un point de l'ordre du jour
    """.trimIndent()

    @Test
    fun `the agenda is the list under Ordre du jour, empty items left out`() {
        assertEquals(listOf("Tour de table", "Priorités du trimestre", "Risques"), Workshop.agenda(meeting))
        assertEquals(emptyList<String>(), Workshop.agenda("# Réunion\n\n## Ordre du jour\n\n- \n\n## Notes\n"))
        assertEquals(listOf("Point"), Workshop.agenda("## Agenda\n\n- Point\n"))
    }

    @Test
    fun `the minutes are shared evenly, never under five a step`() {
        assertEquals(listOf(17, 17, 16), Workshop.steps(listOf("a", "b", "c"), 50).map { it.minutes })
        assertEquals(listOf(5, 5, 5), Workshop.steps(listOf("a", "b", "c"), 10).map { it.minutes })
        assertTrue(Workshop.steps(emptyList(), 60).isEmpty())
    }

    @Test
    fun `the steps run one after the other and keep what each took`() {
        var draft = Workshop.Draft("p.md", "T", Workshop.steps(listOf("a", "b"), 20))
        assertFalse(draft.started)
        draft = Workshop.start(draft, 0)
        assertEquals(600L, Workshop.secondsLeft(draft.steps[0], 0))
        assertEquals(-60L, Workshop.secondsLeft(draft.steps[0], 660_000))
        draft = Workshop.next(draft, 660_000)
        assertEquals(1, draft.current)
        assertEquals(11, Workshop.minutesTaken(draft.steps[0]))
        draft = Workshop.next(draft, 900_000)
        assertTrue(draft.over)
        assertEquals(4, Workshop.minutesTaken(draft.steps[1]))
        assertEquals(draft, Workshop.next(draft, 1_000_000))
    }

    @Test
    fun `the synthesis lists the board by column, actions as checkboxes, then the run`() {
        var draft = Workshop.start(
            Workshop.Draft("20_Projects/X/Réunions/2026-11-15 Atelier.md", "Atelier", Workshop.steps(listOf("Cadrage"), 30)),
            0
        )
        draft = Workshop.add(draft, "1", Workshop.Column.DECISION, "On garde le format actuel")
        draft = Workshop.add(draft, "2", Workshop.Column.ACTION, "@moi écrire le plan\nlundi")
        draft = Workshop.add(draft, "3", Workshop.Column.IDEA, "   ")
        draft = Workshop.next(draft, 25 * 60_000L)
        val path = Workshop.synthesisPath(draft.notePath)
        assertEquals("20_Projects/X/Réunions/2026-11-15 Atelier (synthèse).md", path)

        val note = Workshop.synthesis(draft, path, LocalDate.parse("2026-11-15"), "Un résumé court.")

        assertTrue(note.startsWith("---\n"))
        assertTrue(note.contains("**Atelier du** 2026-11-15 · 25 min (prévu 30) · **Réunion** : [[2026-11-15 Atelier]]"))
        assertTrue(note.contains("## Résumé\n\nUn résumé court.\n"))
        assertTrue(note.contains("## Décisions\n\n- On garde le format actuel\n"))
        assertTrue(note.contains("## Actions\n\n- [ ] @moi écrire le plan lundi\n"))
        assertFalse("an empty card is never kept", note.contains("## Idées"))
        assertTrue(note.contains("## Déroulé\n\n- Cadrage — 25 min (prévu 30)\n"))
        assertFalse(Workshop.synthesis(draft, path, LocalDate.parse("2026-11-15"), null).contains("## Résumé"))
    }

    @Test
    fun `the meeting note links its synthesis once, under its Atelier heading`() {
        val path = "R/2026-11-15 Atelier (synthèse).md"
        val link = "- Synthèse de l'atelier → [[2026-11-15 Atelier (synthèse)]]"
        val plain = "# Atelier\n\n## Notes\n\ntext\n"
        val linked = Workshop.withSynthesisLink(plain, path)
        assertEquals("# Atelier\n\n## Notes\n\ntext\n\n## Atelier\n\n$link\n", linked)
        assertEquals(linked, Workshop.withSynthesisLink(linked, path))
        assertEquals(
            "# A\n\n## Atelier\n\n- older\n$link\n\n## Notes\n",
            Workshop.withSynthesisLink("# A\n\n## Atelier\n\n- older\n\n## Notes\n", path)
        )
        assertEquals(
            "# A\n\n## Atelier\n\n$link\n\n## Notes\n",
            Workshop.withSynthesisLink("# A\n\n## Atelier\n## Notes\n", path)
        )
    }

    @Test
    fun `the summary prompt is French, from the board, and ends with the directive`() {
        val draft = Workshop.add(Workshop.Draft("p.md", "T", Workshop.steps(listOf("a"), 10)), "1", Workshop.Column.IDEA, "une idée")
        val prompt = Workshop.summaryPrompt(draft)
        assertTrue(prompt.contains("Idées :\n- une idée\n"))
        assertTrue(prompt.endsWith(AiLanguage.DIRECTIVE))
    }

    @Test
    fun `a draft survives the phone's storage`() {
        val draft = Workshop.add(Workshop.start(Workshop.Draft("p.md", "T", Workshop.steps(listOf("a", "b"), 20)), 5), "1", Workshop.Column.QUESTION, "Qui ?")
        val json = Json { ignoreUnknownKeys = true }
        assertEquals(draft, json.decodeFromString(Workshop.Draft.serializer(), json.encodeToString(Workshop.Draft.serializer(), draft)))
    }
}
