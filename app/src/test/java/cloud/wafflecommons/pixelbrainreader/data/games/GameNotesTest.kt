package cloud.wafflecommons.pixelbrainreader.data.games

import cloud.wafflecommons.pixelbrainreader.data.utils.TimelineLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
        val page = "# G\n\n## 🏆 Trophies\n\n*0 of 1 trophies · 0 %*\n\n## My notes\n\nKeep me.\n"
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

    // --- The 🎖️ section (13.3, XB2) -----------------------------------------------------------

    private val icon = AchievementSection.ICON
    private val heading = AchievementSection.HEADING

    private fun achievements(vararg records: AchievementRecord, titleId: String = "1915865634", title: String = "Ring of Ash") =
        AchievementSetFile(titleId = titleId, title = title, achievements = records.toList())

    private val earnedAchievements = achievements(
        AchievementRecord("1", "First Steps", gamerscore = 10, earnedAt = "2026-11-02T20:10:00Z"),
        AchievementRecord("2", "The Hidden Boss", gamerscore = 50, secret = true),
        AchievementRecord("12", "Lord of Ash", gamerscore = 30, earnedAt = "2026-11-01T21:00:00Z"),
        AchievementRecord("9", "Second Wind", gamerscore = 5, earnedAt = "2026-11-01T08:00:00Z"),
        AchievementRecord("3", "Everything", gamerscore = 100),
        AchievementRecord("20", "", gamerscore = 15, earnedAt = "2026-11-03T10:00:00Z")
    )

    @Test
    fun `the achievements section lists earned ones only, dated, with their gamerscore`() {
        val lines = AchievementSection.lines(listOf(earnedAchievements), zone)
        assertEquals(
            listOf(
                "*4 of 6 achievements · 60 of 210 G · 66 %*",
                "",
                // The same day by number: 9 before 12.
                "- $icon Second Wind — 2026-11-01 · 5 G",
                "- $icon Lord of Ash — 2026-11-01 · 30 G",
                "- $icon First Steps — 2026-11-02 · 10 G",
                "- $icon Achievement 20 — 2026-11-03 · 15 G"
            ),
            lines
        )
        assertFalse(lines.any { it.contains("Hidden Boss") || it.contains("Everything") })
        assertTrue(AchievementSection.lines(emptyList(), zone).isEmpty())
        assertTrue(AchievementSection.lines(listOf(achievements()), zone).isEmpty())
        // The heading and the icon are U+1F396 U+FE0F.
        assertEquals(listOf(0x1F396, 0xFE0F), icon.codePoints().toArray().toList())
        assertEquals("## $icon Achievements", heading)
    }

    @Test
    fun `two titles on one page say which title each is from`() {
        val legacy = achievements(
            AchievementRecord("7", "Posse", gamerscore = 20, earnedAt = "2026-11-05T10:00:00Z"),
            titleId = "1234567890",
            title = "Ring of Ash (360)"
        )
        val lines = AchievementSection.lines(listOf(earnedAchievements, legacy), zone)
        assertEquals("*5 of 7 achievements · 80 of 230 G · 71 %*", lines.first())
        assertTrue(lines.contains("- $icon Posse — 2026-11-05 · 20 G · Ring of Ash (360)"))
        assertTrue(lines.contains("- $icon First Steps — 2026-11-02 · 10 G · Ring of Ash"))
        // A file without achievements isn't a title the page holds.
        val alone = AchievementSection.lines(listOf(earnedAchievements, achievements(titleId = "5", title = "Empty")), zone)
        assertTrue(alone.contains("- $icon First Steps — 2026-11-02 · 10 G"))
    }

    @Test
    fun `the section goes right after the trophies, before the owner's next section`() {
        val lines = listOf("*1 of 1 achievements · 10 of 10 G · 100 %*", "", "- $icon First Steps — 2026-11-02 · 10 G")
        val page = "# G\n\n## 🏆 Trophies\n\n*1 of 1 trophies · 100 %*\n\n- 🥉 First Steps — 2026-11-02\n\n## My notes\n\nKeep me.\n"
        val once = AchievementSection.apply(page, lines)
        assertEquals(
            "# G\n\n## 🏆 Trophies\n\n*1 of 1 trophies · 100 %*\n\n- 🥉 First Steps — 2026-11-02\n\n$heading\n\n" +
                lines.joinToString("\n") + "\n\n## My notes\n\nKeep me.\n",
            once
        )
        assertEquals(once, AchievementSection.apply(once, lines))
        // No blank line before the owner's section: the heading still gets one before it.
        val tight = "# G\n\n## 🏆 Trophies\n\n- 🥉 First Steps — 2026-11-02\n## My notes\n"
        assertEquals(
            "# G\n\n## 🏆 Trophies\n\n- 🥉 First Steps — 2026-11-02\n\n$heading\n\n" + lines.joinToString("\n") + "\n\n## My notes\n",
            AchievementSection.apply(tight, lines)
        )
        // Trophies closing the note: the achievements follow them at the end.
        val last = "# G\n\n## 🏆 Trophies\n\n- 🥉 First Steps — 2026-11-02\n"
        assertEquals(last + "\n$heading\n\n" + lines.joinToString("\n") + "\n", AchievementSection.apply(last, lines))
    }

    @Test
    fun `without trophies it is appended, then rewritten in place, removed when empty`() {
        val page = "---\ngame_template: souls-like\n---\n# Ring of Ash\n\n## Bosses\n\n- [ ] One\n"
        val lines = AchievementSection.lines(listOf(earnedAchievements), zone)
        val once = AchievementSection.apply(page, lines)
        assertEquals(page + "\n$heading\n\n" + lines.joinToString("\n") + "\n", once)
        val more = earnedAchievements.copy(
            achievements = earnedAchievements.achievements.map { if (it.id == "3") it.copy(earnedAt = "2026-11-04T22:00:00Z") else it }
        )
        val twice = AchievementSection.apply(once, AchievementSection.lines(listOf(more), zone))
        assertTrue(twice.contains("*5 of 6 achievements · 160 of 210 G · 83 %*"))
        assertTrue(twice.contains("- $icon Everything — 2026-11-04 · 100 G"))
        assertEquals(1, twice.split(heading).size - 1)
        assertEquals(page, AchievementSection.apply(twice, emptyList()))
        // Nothing to write and no section: the page as it was.
        assertEquals(page, AchievementSection.apply(page, emptyList()))
    }

    @Test
    fun `a heading without the variation selector is found and rewritten`() {
        val bare = "## 🎖 Achievements"
        val page = "# G\n\n$bare\n\n*0 of 1 achievements · 0 of 10 G · 0 %*\n\n## My notes\n\nKeep me.\n"
        assertEquals(
            "# G\n\n$heading\n\n*1 of 1 achievements · 10 of 10 G · 100 %*\n\n## My notes\n\nKeep me.\n",
            AchievementSection.apply(page, listOf("*1 of 1 achievements · 10 of 10 G · 100 %*"))
        )
        assertTrue(AchievementSection.isHeading(bare))
        assertTrue(AchievementSection.isHeading(heading))
        assertFalse(AchievementSection.isHeading("## Achievements"))
        assertFalse(AchievementSection.isHeading(TrophySection.HEADING))
    }

    @Test
    fun `the trophies section is byte-identical with or without achievements around it`() {
        val base = "---\ngame_template: souls-like\n---\n# Ring of Ash\n\n## Bosses\n\n- [ ] One\n"
        val trophyLines = TrophySection.lines(listOf(earnedSet), zone)
        val achievementLines = AchievementSection.lines(listOf(earnedAchievements), zone)
        val trophiesOnly = TrophySection.apply(base, trophyLines)
        val both = AchievementSection.apply(trophiesOnly, achievementLines)
        assertEquals(trophiesOnly + "\n$heading\n\n" + achievementLines.joinToString("\n") + "\n", both)
        // Rewriting the trophies leaves the achievements as they are, and the same trophies change nothing.
        assertEquals(both, TrophySection.apply(both, trophyLines))
        val more = earnedSet.copy(trophies = earnedSet.trophies.map { if (it.id == 4) it.copy(earnedAt = "2026-11-03T22:00:00Z") else it })
        val moreTrophies = TrophySection.apply(both, TrophySection.lines(listOf(more), zone))
        assertEquals(both.substringAfter(heading), moreTrophies.substringAfter(heading))
        assertEquals(
            TrophySection.apply(trophiesOnly, TrophySection.lines(listOf(more), zone)),
            moreTrophies.substringBefore("\n$heading")
        )
        // Achievements first: the trophies come after them, both kept.
        val achievementsFirst = AchievementSection.apply(base, achievementLines)
        val then = TrophySection.apply(achievementsFirst, trophyLines)
        assertEquals(both, then)
        assertEquals(then, AchievementSection.apply(then, achievementLines))
        // Removing the trophies keeps the achievements.
        assertEquals(base + "\n$heading\n\n" + achievementLines.joinToString("\n") + "\n", TrophySection.apply(both, emptyList()))
    }

    @Test
    fun `a name from the console servers stays one line, so the section never breaks`() {
        val odd = achievements(
            AchievementRecord("1", "Foo\n## Notes", gamerscore = 10, earnedAt = "2026-11-02T20:10:00Z"),
            AchievementRecord("2", " Tab\tand\u0007 bell ", gamerscore = 5, earnedAt = "2026-11-03T20:10:00Z")
        )
        val legacy = achievements(
            AchievementRecord("7", "Posse", gamerscore = 20, earnedAt = "2026-11-05T10:00:00Z"),
            titleId = "1234567890",
            title = "Ring of Ash\r\n## 360"
        )
        val lines = AchievementSection.lines(listOf(odd, legacy), zone)
        assertEquals(
            listOf(
                "*3 of 3 achievements · 35 of 35 G · 100 %*",
                "",
                "- $icon Foo ## Notes — 2026-11-02 · 10 G · Ring of Ash",
                "- $icon Tab and bell — 2026-11-03 · 5 G · Ring of Ash",
                "- $icon Posse — 2026-11-05 · 20 G · Ring of Ash ## 360"
            ),
            lines
        )
        // Applied twice, the page is the same: no name opened a section of its own.
        val page = "# G\n\n## Bosses\n\n- [ ] One\n"
        val once = AchievementSection.apply(page, lines)
        assertEquals(once, AchievementSection.apply(once, lines))
        assertEquals(listOf("## Bosses", heading), once.lines().filter { it.startsWith("## ") })
        // A trophy's name and a set's title too.
        val online = TrophySetFile(
            "NPWR00002_00", "trophy", title = "Ring of Ash\n## Online", trophies = listOf(TrophyRecord(7, "Posse", earnedAt = "2026-11-05T10:00:00Z"))
        )
        val trophies = TrophySection.lines(listOf(set(TrophyRecord(1, "First\nSteps", grade = "bronze", earnedAt = "2026-11-02T20:10:00Z")), online), zone)
        assertEquals(
            listOf("*2 of 2 trophies · 100 %*", "", "- 🥉 First Steps — 2026-11-02 · Ring of Ash", "- 🥉 Posse — 2026-11-05 · Ring of Ash ## Online"),
            trophies
        )
        // ™, ® and © stay; a name of control characters only falls back to its number.
        assertEquals("Brick™ Wars® ©", oneLine("Brick™\u0000 Wars®\u001B ©"))
        assertEquals(
            "- $icon Achievement 3 — 2026-11-02 · 1 G",
            AchievementSection.lines(listOf(achievements(AchievementRecord("3", "\u0007", gamerscore = 1, earnedAt = "2026-11-02T20:10:00Z"))), zone).last()
        )
    }

    private val achievementChecklist = """
        ## Bosses
        - [ ] Beat the first knight <!--achievement:1915865634#12-->
        - [ ] Not yet <!--achievement:1915865634#2-->
        - [x] Already ✅ 2026-10-01 <!--achievement:1915865634#1-->
        - [ ] Dated by hand ✅ 2026-10-03 <!--achievement:1915865634#5-->
        - [ ] My own line
    """.trimIndent()

    @Test
    fun `an earned achievement ticks its line once, dated, never unticked`() {
        val earned = mapOf("12" to LocalDate.of(2026, 11, 1), "1" to LocalDate.of(2026, 11, 2), "5" to LocalDate.of(2026, 11, 3))
        val ticked = AchievementMarkers.tick(achievementChecklist) { titleId, id -> if (titleId == "1915865634") earned[id] else null }
        assertTrue(ticked.contains("- [x] Beat the first knight ✅ 2026-11-01 <!--achievement:1915865634#12-->"))
        assertTrue(ticked.contains("- [ ] Not yet <!--achievement:1915865634#2-->"))
        assertTrue(ticked.contains("- [x] Already ✅ 2026-10-01 <!--achievement:1915865634#1-->"))
        assertTrue(ticked.contains("- [x] Dated by hand ✅ 2026-10-03 <!--achievement:1915865634#5-->"))
        assertTrue(ticked.contains("- [ ] My own line"))
        assertEquals(ticked, AchievementMarkers.tick(ticked) { _, id -> earned[id] })
        // An achievement no longer reported leaves its line ticked.
        assertEquals(ticked, AchievementMarkers.tick(ticked) { _, _ -> null })
        // Windows lines keep their ending.
        assertEquals(
            "- [x] Beat the first knight ✅ 2026-11-01 <!--achievement:1915865634#12-->\r\n- [ ] Other\r\n",
            AchievementMarkers.tick("- [ ] Beat the first knight <!--achievement:1915865634#12-->\r\n- [ ] Other\r\n") { _, id -> earned[id] }
        )
    }

    @Test
    fun `achievement and trophy markers don't tick each other`() {
        val mixed = "- [ ] Knight <!--trophy:NPWR00001_00#3-->\n- [ ] Knight on Xbox <!--achievement:1915865634#3-->\n"
        assertEquals(
            "- [ ] Knight <!--trophy:NPWR00001_00#3-->\n- [x] Knight on Xbox ✅ 2026-11-01 <!--achievement:1915865634#3-->\n",
            AchievementMarkers.tick(mixed) { _, _ -> LocalDate.of(2026, 11, 1) }
        )
        assertEquals(
            "- [x] Knight ✅ 2026-11-02 <!--trophy:NPWR00001_00#3-->\n- [ ] Knight on Xbox <!--achievement:1915865634#3-->\n",
            TrophyMarkers.tick(mixed) { _, _ -> LocalDate.of(2026, 11, 2) }
        )
        assertFalse(TrophyMarkers.hasWaiting("- [ ] Knight on Xbox <!--achievement:1915865634#3-->"))
        assertFalse(AchievementMarkers.hasWaiting("- [ ] Knight <!--trophy:NPWR00001_00#3-->"))
    }

    @Test
    fun `waiting achievement lines are masked`() {
        val masked = AchievementMarkers.masked(achievementChecklist)
        assertTrue(masked.contains("- [ ] ??? <!--achievement:1915865634#12-->"))
        assertTrue(masked.contains("- [x] Already ✅ 2026-10-01 <!--achievement:1915865634#1-->"))
        assertTrue(masked.contains("- [ ] My own line"))
        assertFalse(masked.contains("Beat the first knight"))
        assertTrue(AchievementMarkers.hasWaiting(achievementChecklist))
        assertFalse(AchievementMarkers.hasWaiting("- [ ] My own line"))
    }
}
