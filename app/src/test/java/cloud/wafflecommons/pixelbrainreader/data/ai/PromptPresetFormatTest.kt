package cloud.wafflecommons.pixelbrainreader.data.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class PromptPresetFormatTest {

    @Test
    fun `the defaults survive a write and a read of the presets file`() {
        val file = PromptPresetFormat.render(PromptPresetFormat.DEFAULTS, LocalDate.of(2026, 10, 18))

        assertTrue(file.startsWith("---\ntitle: \"Prompt presets\"\n"))
        assertTrue(file.contains("type: system\n"))
        assertEquals(PromptPresetFormat.DEFAULTS, PromptPresetFormat.parse(file))
    }

    @Test
    fun `options open a section, the rest is the prompt`() {
        val presets = PromptPresetFormat.parse(
            """
            # Prompt presets
            Intro text that is not a preset.

            ## 📝 Synthèse de réunion
            notes: oui
            description: Turns raw notes into decisions

            Résume en trois puces.
            Liste les décisions.

            ## Empty one
            notes: yes
            """.trimIndent()
        )

        assertEquals(1, presets.size)
        with(presets.single()) {
            assertEquals("synthese-de-reunion", id)
            assertEquals("📝 Synthèse de réunion", title)
            assertEquals("Turns raw notes into decisions", description)
            assertTrue(useNotes)
            assertEquals("Résume en trois puces.\nListe les décisions.", prompt)
        }
    }

    @Test
    fun `a prompt is capped for Gemini Nano and duplicate titles get distinct ids`() {
        val long = "x".repeat(PromptPresetFormat.MAX_PROMPT_CHARS + 500)
        val presets = PromptPresetFormat.parse("## Coach\n\n$long\n## Coach\nnotes: no\n\nSecond.")

        assertEquals(PromptPresetFormat.MAX_PROMPT_CHARS, presets[0].prompt.length)
        assertEquals(listOf("coach", "coach-2"), presets.map { it.id })
        assertFalse(presets[1].useNotes)
    }

    @Test
    fun `every preset answers in French whatever its text`() {
        val preset = PromptPresetFormat.parse("## Free\nAnswer however you like.").single()
        assertTrue(preset.systemPrompt.endsWith(AiLanguage.SYSTEM_DIRECTIVE))
    }
}
