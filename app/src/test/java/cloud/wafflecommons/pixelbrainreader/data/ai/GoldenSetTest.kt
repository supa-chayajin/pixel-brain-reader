package cloud.wafflecommons.pixelbrainreader.data.ai

import cloud.wafflecommons.pixelbrainreader.data.search.FtsQuery
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** R1: the vault's golden set read and scored (settled decision #2 changes only on these numbers). */
class GoldenSetTest {

    private val note = """
        ---
        title: "🎯 RAG golden set"
        ---
        # 🎯 RAG golden set

        ## Questions

        ### Q01 · semantic · fr
        q: Quelle est la recette des pâtes carbonara ?
        expect: `30_Areas/Recipes/Carbo.md`

        ### Q02 · error · en
        q: ModuleNotFoundError: No module named 'click'
        expect: `20_Projects/Tools/RFC_A.md`
        expect: `20_Projects/Tools/RFC_B.md`

        ### Q03 · negative · en
        q: What is the capital of Australia?
    """.trimIndent()

    @Test
    fun `questions, kinds, languages and expected notes are read, and a question without expect is a negative`() {
        val questions = GoldenSet.parse(note)
        assertEquals(listOf("Q01", "Q02", "Q03"), questions.map { it.id })
        assertEquals(listOf("semantic", "error", "negative"), questions.map { it.kind })
        assertEquals(listOf("20_Projects/Tools/RFC_A.md", "20_Projects/Tools/RFC_B.md"), questions[1].expected)
        assertEquals(true, questions[2].isNegative)
    }

    @Test
    fun `recall at five per search, both together, and the negatives the floor kept empty`() {
        val (q1, q2, q3) = GoldenSet.parse(note)
        val report = GoldenSet.score(
            listOf(
                GoldenSet.Outcome(q1, byMeaning = listOf("30_Areas/Recipes/Carbo.md"), byWords = emptyList()),
                GoldenSet.Outcome(q2, byMeaning = emptyList(), byWords = listOf("20_Projects/Tools/RFC_A.md")),
                GoldenSet.Outcome(q3, byMeaning = emptyList(), byWords = listOf("x.md"))
            )
        )
        assertEquals(0.5, report.meaningRecall, 1e-9) // Q01 1.0, Q02 0.0
        assertEquals(0.25, report.wordsRecall, 1e-9) // Q01 0.0, Q02 0.5
        assertEquals(0.75, report.eitherRecall, 1e-9) // Q01 1.0, Q02 0.5
        assertEquals(1, report.negativesClean)
        assertEquals(emptyList<String>(), report.missed)
        assertEquals("3 questions · recall@5: meaning 0.50, words 0.25, either 0.75 · negatives clean 1/1", report.summary())
    }

    @Test
    fun `ranked full-text search ORs the words, short ones left out`() {
        assertEquals("quelle* OR est* OR recette* OR des*", FtsQuery.buildAny("Quelle est la recette des pâtes ?", maxTerms = 4))
        assertNull(FtsQuery.buildAny("a b ?"))
    }
}
