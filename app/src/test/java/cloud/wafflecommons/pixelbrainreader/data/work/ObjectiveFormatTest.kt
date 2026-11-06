package cloud.wafflecommons.pixelbrainreader.data.work

import cloud.wafflecommons.pixelbrainreader.data.vault.LinkExtractor
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
    fun `linked projects and notes resolve both ways, and a linked project's work keeps the objective alive`() {
        val today = LocalDate.of(2026, 11, 6)
        val lead = WorkNote.from(
            "30_Areas/Objectifs/Lead.md",
            "---\ntitle: \"Lead\"\ndate_updated: '2026-09-01'\ntags:\n  - objectif\n  - plateforme\n  - meta\n---\n# Lead\n\nVoir [[RFC-007]].\n"
        )
        val meeting = WorkNote.from("30_Areas/Réunions/2026-10-01 Point.md", "---\ntitle: \"Point\"\ndate_updated: '2026-10-01'\n---\nPour [[Lead]].\n")
        val daily = WorkNote.from("10_Journal/2026-11-05.md", "---\ndate: 2026-11-05\n---\nAvancé sur [[Lead]].\n")
        val idea = WorkNote.from("20_Projects/23_Other/Notes/Idea.md", "---\ntitle: \"Idea\"\n---\nServes [[Lead]].\n")
        val rfc = WorkNote.from("20_Projects/21_Demo/RFCs/RFC-007.md", "---\ntitle: \"RFC 7\"\n---\n# RFC 7\n")
        fun readme(tags: String) = "---\ntitle: \"Project\"\ntags: [$tags]\n---\n# Project\n"
        val worklog = "# Worklog\n\n## 🧬 Commits\n\n- feat: one\n- fix: two\n"
        val demo = ProjectFormat.summarize(
            "20_Projects/21_Demo",
            mapOf("20_Projects/21_Demo/README.md" to readme("meta, demo"), rfc.path to rfc.content, "20_Projects/21_Demo/Worklog/2026-11-05.md" to worklog),
            emptyList(), today
        )
        val other = ProjectFormat.summarize("20_Projects/23_Other", mapOf("20_Projects/23_Other/README.md" to readme("meta"), idea.path to idea.content), emptyList(), today)
        val tagged = ProjectFormat.summarize("20_Projects/24_Tagged", mapOf("20_Projects/24_Tagged/README.md" to readme("meta, plateforme")), emptyList(), today)
        val unrelated = ProjectFormat.summarize("20_Projects/25_Unrelated", mapOf("20_Projects/25_Unrelated/README.md" to readme("meta")), emptyList(), today)
        val links = listOf(
            lead.path to "rfc-007", // the objective links into a project
            idea.path to "lead", // a project note links to the objective
            meeting.path to "lead", // a note outside the projects links to it
            daily.path to "lead" // a journal entry: not a linked note
        )
        val byKey = listOf(lead, meeting, daily, idea, rfc).groupBy { LinkExtractor.keyOfPath(it.path) }

        val plain = ObjectiveFormat.objective(lead, emptyList())
        val linked = ObjectiveFormat.withLinks(plain, links, byKey, listOf(demo, other, tagged, unrelated))

        // `plateforme` is on one README only; `meta` is on all of them and links nothing.
        assertEquals(listOf("20_Projects/21_Demo", "20_Projects/23_Other", "20_Projects/24_Tagged"), linked.linkedProjects.map { it.root })
        assertEquals(listOf(meeting.path), linked.linkedNotes.map { it.path })
        // The note last moved on 1 September, but the linked project committed yesterday.
        assertTrue(plain.stale(today))
        assertFalse(linked.stale(today))
    }

    @Test
    fun `a sub-project's note links the sub-project, not its parent`() {
        val today = LocalDate.of(2026, 11, 6)
        val goal = WorkNote.from("30_Areas/Objectifs/Goal.md", "---\ntitle: \"Goal\"\ntags:\n  - objectif\n---\n# Goal\n")
        val plan = WorkNote.from("20_Projects/28_Work/app_one/Notes/Plan.md", "---\ntitle: \"Plan\"\n---\nServes [[Goal]].\n")
        val parent = ProjectFormat.summarize("20_Projects/28_Work", mapOf("20_Projects/28_Work/README.md" to "# Work\n"), emptyList(), today)
        val app = ProjectFormat.summarize("20_Projects/28_Work/app_one", mapOf(plan.path to plan.content), emptyList(), today)
        val linked = ObjectiveFormat.withLinks(
            ObjectiveFormat.objective(goal, emptyList()),
            listOf(plan.path to "goal"),
            listOf(goal, plan).groupBy { LinkExtractor.keyOfPath(it.path) },
            listOf(parent, app)
        )
        assertEquals(listOf("20_Projects/28_Work/app_one"), linked.linkedProjects.map { it.root })
    }

    @Test
    fun `the weekly slot is the next start strictly after now`() {
        val settings = ObjectiveSettings(slotEnabled = true, slotDay = 5, slotStart = "14:00")
        assertEquals(LocalDateTime.of(2026, 10, 23, 14, 0), ObjectiveFormat.nextSlot(settings, LocalDateTime.of(2026, 10, 18, 9, 0)))
        assertEquals(LocalDateTime.of(2026, 10, 23, 14, 0), ObjectiveFormat.nextSlot(settings, LocalDateTime.of(2026, 10, 23, 13, 59)))
        assertEquals(LocalDateTime.of(2026, 10, 30, 14, 0), ObjectiveFormat.nextSlot(settings, LocalDateTime.of(2026, 10, 23, 14, 0)))
    }
}
