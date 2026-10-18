package cloud.wafflecommons.pixelbrainreader.data.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MentionFinderTest {

    @Test
    fun `a whole-word mention outside links, code and frontmatter is found`() {
        val content = """
            ---
            title: "Roadmap Q4 notes"
            ---
            Voir [[Roadmap Q4]] et `Roadmap Q4` et [lien](Roadmap Q4.md).
            ```
            Roadmap Q4 dans du code
            ```
            Mes Roadmap Q4s et la roadmap q4 révisée.
        """.trimIndent()

        assertEquals("Mes Roadmap Q4s et la roadmap q4 révisée.", MentionFinder.firstMention(content, listOf("Roadmap Q4")))
        assertNull(MentionFinder.firstMention("Voir [[Roadmap Q4]].", listOf("Roadmap Q4")))
        assertNull(MentionFinder.firstMention("Une API ici.", listOf("API"))) // too short a name
    }

    @Test
    fun `accents and case aside, linking keeps the text's own spelling as the alias`() {
        assertEquals("La revue de securite a eu lieu.", MentionFinder.firstMention("La revue de securite a eu lieu.", listOf("Revue de sécurité")))
        assertEquals("Voir [[Plan Sécurité]] demain.", MentionFinder.link("Voir Plan Sécurité demain.", "Plan Sécurité"))
        assertEquals("Voir [[Plan Sécurité|plan securite]] demain.", MentionFinder.link("Voir plan securite demain.", "Plan Sécurité"))
        assertNull(MentionFinder.link("Rien ici.", "Plan Sécurité"))
    }
}
