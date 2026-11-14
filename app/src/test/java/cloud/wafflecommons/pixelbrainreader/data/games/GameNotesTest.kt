package cloud.wafflecommons.pixelbrainreader.data.games

import cloud.wafflecommons.pixelbrainreader.data.utils.TimelineLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

class GameNotesTest {

    private val zone: ZoneId = ZoneId.of("Europe/Paris")

    // --- Timeline lines ------------------------------------------------------------------

    @Test
    fun `durations read the way the owner writes them`() {
        assertEquals("45 min", GameTimeline.duration(45))
        assertEquals("2 h", GameTimeline.duration(120))
        assertEquals("2 h 35", GameTimeline.duration(155))
        assertEquals("1 h 05", GameTimeline.duration(65))
    }

    @Test
    fun `a game line carries its page as an invisible marker`() {
        val page = GamePage("x/Ring_of_Ash.md", "Ring of Ash", emptyList(), GameTemplate.SOULS_LIKE, GameState.PLAYING, null, null)
        val text = GameTimeline.text(GameDayLine(page, LocalTime.of(21, 5), 155))
        assertEquals("🎮 Ring of Ash · 2 h 35 <!--game:Ring_of_Ash-->", text)
        assertTrue(GameTimeline.isGameLine(text))
        assertFalse(GameTimeline.isGameLine("🎮 Ring of Ash with a friend"))
    }

    private val pastDay = """
        ---
        date: 2026-11-13
        ---

        # 2026-11-13

        ## 🗓️ Timeline

        - 08:30 Coffee
        - 21:00 🎮 Ring of Ash · 1 h <!--game:Ring_of_Ash-->
        - 22:15 Call <!--gcal:evt1-->

        ## 📝 Journal

        - [ ] Something
    """.trimIndent() + "\n"

    @Test
    fun `a past day's game lines are replaced in time order and nothing else moves`() {
        val lines = listOf(
            TimelineLine(LocalTime.of(21, 0), "🎮 Ring of Ash · 2 h 35 <!--game:Ring_of_Ash-->"),
            TimelineLine(LocalTime.of(23, 30), "🎮 Outlaw Trail 2 · 45 min <!--game:Outlaw_Trail_2-->")
        )
        val edited = GameTimeline.applyToNote(pastDay, lines)
        assertEquals(
            pastDay.replace(
                "- 21:00 🎮 Ring of Ash · 1 h <!--game:Ring_of_Ash-->\n- 22:15 Call <!--gcal:evt1-->\n",
                "- 21:00 🎮 Ring of Ash · 2 h 35 <!--game:Ring_of_Ash-->\n- 22:15 Call <!--gcal:evt1-->\n" +
                    "- 23:30 🎮 Outlaw Trail 2 · 45 min <!--game:Outlaw_Trail_2-->\n"
            ),
            edited
        )
        // Applying the same lines again changes nothing.
        assertEquals(edited, GameTimeline.applyToNote(edited, lines))
    }

    @Test
    fun `No events gives its place, and a note without a Timeline is left alone`() {
        val empty = "# 2026-11-13\n\n## 🗓️ Timeline\n\n*No events*\n\n## 📝 Journal\n\n*No tasks*\n"
        val line = TimelineLine(LocalTime.of(22, 0), "🎮 Ring of Ash · 2 h <!--game:Ring_of_Ash-->")
        assertEquals(
            "# 2026-11-13\n\n## 🗓️ Timeline\n\n- 22:00 🎮 Ring of Ash · 2 h <!--game:Ring_of_Ash-->\n\n## 📝 Journal\n\n*No tasks*\n",
            GameTimeline.applyToNote(empty, listOf(line))
        )
        val noTimeline = "# A note\n\nText.\n"
        assertEquals(noTimeline, GameTimeline.applyToNote(noTimeline, listOf(line)))
    }

    @Test
    fun `an empty list removes the derived lines only`() {
        assertEquals(
            pastDay.replace("- 21:00 🎮 Ring of Ash · 1 h <!--game:Ring_of_Ash-->\n", ""),
            GameTimeline.applyToNote(pastDay, emptyList())
        )
    }

    // --- The 🏆 section (D12) ---------------------------------------------------------------

    private fun set(vararg trophies: TrophyRecord, title: String = "Ring of Ash") =
        TrophySetFile(set = "NPWR00001_00", service = "trophy2", title = title, trophies = trophies.toList())

    private val earnedSet = set(
        TrophyRecord(1, "First Steps", grade = "bronze", earnedAt = "2026-11-02T20:10:00Z"),
        TrophyRecord(2, "The Hidden Boss", grade = "gold", hidden = true),
        TrophyRecord(3, "Lord of Ash", grade = "silver", earnedAt = "2026-11-01T21:00:00Z"),
        TrophyRecord(4, "Everything", grade = "platinum")
    )

    @Test
    fun `the section lists earned trophies only, by date, never an unearned name`() {
        val lines = TrophySection.lines(listOf(earnedSet), zone)
        assertEquals(
            listOf("*2 of 4 trophies · 50 %*", "", "- 🥈 Lord of Ash — 2026-11-01", "- 🥉 First Steps — 2026-11-02"),
            lines
        )
        assertFalse(lines.any { it.contains("Hidden Boss") || it.contains("Everything") })
        assertTrue(TrophySection.lines(emptyList(), zone).isEmpty())
    }

    @Test
    fun `the section is appended once, then rewritten in place, the rest byte-identical`() {
        val page = "---\ngame_template: souls-like\n---\n# Ring of Ash\n\n## Bosses\n\n- [ ] One\n"
        val once = TrophySection.apply(page, TrophySection.lines(listOf(earnedSet), zone))
        assertTrue(once.startsWith(page.trimEnd('\n') + "\n\n## 🏆 Trophies\n\n*2 of 4"))
        assertTrue(once.endsWith("— 2026-11-02\n"))
        assertEquals(once, TrophySection.apply(once, TrophySection.lines(listOf(earnedSet), zone)))
        val more = earnedSet.copy(trophies = earnedSet.trophies.map { if (it.id == 4) it.copy(earnedAt = "2026-11-03T22:00:00Z") else it })
        val twice = TrophySection.apply(once, TrophySection.lines(listOf(more), zone))
        assertTrue(twice.contains("*3 of 4 trophies · 75 %*"))
        assertTrue(twice.contains("- 🏆 Everything — 2026-11-03"))
        assertEquals(1, twice.split(TrophySection.HEADING).size - 1)
        assertEquals(page.trimEnd('\n'), twice.substringBefore("\n\n## 🏆 Trophies"))
    }

    @Test
    fun `a section followed by the owner's own section keeps that section`() {
        val page = "# G\n\n## 🏆 Trophies\n\n*old*\n\n## My notes\n\nKeep me.\n"
        val updated = TrophySection.apply(page, listOf("*1 of 1 trophies · 100 %*"))
        assertEquals("# G\n\n## 🏆 Trophies\n\n*1 of 1 trophies · 100 %*\n\n## My notes\n\nKeep me.\n", updated)
        assertEquals("# G\n\n## My notes\n\nKeep me.\n", TrophySection.apply(page, emptyList()))
    }

    @Test
    fun `two sets on one page say which set each trophy is from`() {
        val online = TrophySetFile("NPWR00002_00", "trophy", title = "Ring of Ash Online", trophies = listOf(TrophyRecord(7, "Posse", earnedAt = "2026-11-05T10:00:00Z")))
        val lines = TrophySection.lines(listOf(earnedSet, online), zone)
        assertTrue(lines.contains("- 🥉 Posse — 2026-11-05 · Ring of Ash Online"))
    }

    // --- Trophy markers (D3) ---------------------------------------------------------------

    private val checklist = """
        ## Bosses
        - [ ] The first knight <!--trophy:NPWR00001_00#3-->
        - [ ] Not yet <!--trophy:NPWR00001_00#2-->
        - [x] Already ✅ 2026-10-01 <!--trophy:NPWR00001_00#1-->
        - [ ] My own line
    """.trimIndent()

    @Test
    fun `an earned trophy ticks its line once, dated, and never unticks one`() {
        val earned = mapOf(3 to LocalDate.of(2026, 11, 1), 1 to LocalDate.of(2026, 11, 2))
        val ticked = TrophyMarkers.tick(checklist) { set, id -> if (set == "NPWR00001_00") earned[id] else null }
        assertTrue(ticked.contains("- [x] The first knight ✅ 2026-11-01 <!--trophy:NPWR00001_00#3-->"))
        assertTrue(ticked.contains("- [ ] Not yet <!--trophy:NPWR00001_00#2-->"))
        assertTrue(ticked.contains("- [x] Already ✅ 2026-10-01 <!--trophy:NPWR00001_00#1-->"))
        assertTrue(ticked.contains("- [ ] My own line"))
        assertEquals(ticked, TrophyMarkers.tick(ticked) { _, id -> earned[id] })
    }

    @Test
    fun `waiting lines are masked in the app until revealed`() {
        val masked = TrophyMarkers.masked(checklist)
        assertTrue(masked.contains("- [ ] ??? <!--trophy:NPWR00001_00#3-->"))
        assertTrue(masked.contains("- [x] Already ✅ 2026-10-01"))
        assertTrue(masked.contains("- [ ] My own line"))
        assertFalse(masked.contains("The first knight"))
        assertTrue(TrophyMarkers.hasWaiting(checklist))
        assertFalse(TrophyMarkers.hasWaiting("- [ ] My own line"))
    }
}
