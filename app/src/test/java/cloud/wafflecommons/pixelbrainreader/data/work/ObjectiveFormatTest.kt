package cloud.wafflecommons.pixelbrainreader.data.work

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class ObjectiveFormatTest {

    private val content = """
        ---
        title: "Sécuriser la chaîne CI"
        date_created: '2026-01-10'
        date_updated: '2026-09-01'
        type: area
        status: 🏗️ wip
        tags:
          - objectif
          - securite
        aliases: []
        ---
        # Sécuriser la chaîne CI

        - [x] Inventaire des secrets 📅 2026-03-01
        - [ ] Rotation automatique [due:: 2026-11-15]
          * [ ] Sous-étape sans date
        ```
        - [ ] dans un bloc de code
        ```
    """.trimIndent()

    private val note = WorkNote.from("30_Areas/Objectifs/CI.md", content)

    @Test
    fun `tagged notes are objectives and their other tags pick the material`() {
        assertTrue(ObjectiveFormat.isObjective(note))
        assertEquals(setOf("securite"), ObjectiveFormat.materialTags(note))
        assertFalse(ObjectiveFormat.isObjective(WorkNote.from("x.md", "---\ntitle: \"x\"\ntags:\n  - securite\n---\n")))
    }

    @Test
    fun `milestones are the checklist outside code, with their due dates`() {
        val milestones = ObjectiveFormat.milestones(content)
        assertEquals(listOf("Inventaire des secrets", "Rotation automatique", "Sous-étape sans date"), milestones.map { it.text })
        assertEquals(listOf(true, false, false), milestones.map { it.done })
        assertEquals(LocalDate.of(2026, 11, 15), milestones[1].due)
        assertNull(milestones[2].due)
    }

    @Test
    fun `toggling flips exactly one box`() {
        val milestone = ObjectiveFormat.milestones(content)[1]
        val toggled = ObjectiveFormat.toggle(content, milestone)!!
        assertTrue(toggled.contains("- [x] Rotation automatique [due:: 2026-11-15]"))
        assertEquals(content.lines().size, toggled.lines().size)
        assertTrue(ObjectiveFormat.milestones(ObjectiveFormat.toggle(toggled, ObjectiveFormat.milestones(toggled)[1])!!)[1].done.not())
        assertNull(ObjectiveFormat.toggle("other content", milestone))
    }

    @Test
    fun `progress, next due milestone, material and staleness`() {
        val log = listOf(
            LogEntry(LocalDateTime.of(2026, 10, 10, 9, 0), LogKind.FACT, "Audit fait", tags = listOf("securite")),
            LogEntry(LocalDateTime.of(2026, 10, 11, 9, 0), LogKind.FACT, "Autre", tags = listOf("infra"))
        )
        val objective = ObjectiveFormat.objective(note, log)
        assertEquals(1, objective.done)
        assertEquals(1f / 3, objective.progress, 1e-6f)
        assertEquals("Rotation automatique", objective.nextDue!!.text)
        assertEquals(listOf("Audit fait"), objective.material.map { it.text })
        assertFalse(objective.stale(LocalDate.of(2026, 10, 20)))
        assertTrue(objective.stale(LocalDate.of(2026, 12, 1)))
    }

    @Test
    fun `the weekly slot is the next start strictly after now`() {
        val settings = ObjectiveSettings(slotEnabled = true, slotDay = 5, slotStart = "14:00")
        assertEquals(LocalDateTime.of(2026, 10, 23, 14, 0), ObjectiveFormat.nextSlot(settings, LocalDateTime.of(2026, 10, 18, 9, 0)))
        assertEquals(LocalDateTime.of(2026, 10, 23, 14, 0), ObjectiveFormat.nextSlot(settings, LocalDateTime.of(2026, 10, 23, 13, 59)))
        assertEquals(LocalDateTime.of(2026, 10, 30, 14, 0), ObjectiveFormat.nextSlot(settings, LocalDateTime.of(2026, 10, 23, 14, 0)))
    }
}
