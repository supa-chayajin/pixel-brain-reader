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
        // Achievements first (13.4): the trophies go right before them, so the page reads 🏆 then 🎖️ as every other one.
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
    // --- 13.4: the owner's lines in and below the derived sections (GA2) -------------------

    private val tower = TrophySetFile(
        "NPWR00009_00", "trophy2", title = "Glass Tower",
        trophies = listOf(
            TrophyRecord(1, "First Floor", grade = "bronze", earnedAt = "2026-11-02T20:10:00Z"),
            TrophyRecord(2, "Top Floor", grade = "gold")
        )
    )
    private val towerLines get() = TrophySection.lines(listOf(tower), zone)
    private val towerAchievements = achievements(
        AchievementRecord("1", "Ground Floor", gamerscore = 10, earnedAt = "2026-11-02T20:10:00Z"),
        titleId = "1915865690", title = "Glass Tower"
    )
    private val towerAchievementLines get() = AchievementSection.lines(listOf(towerAchievements), zone)

    @Test
    fun `a paragraph after the last section survives two syncs byte for byte`() {
        val page = "---\ngame_template: other\n---\n# Glass Tower\n\n## Notes\n\nMine.\n\n" +
            "## 🏆 Trophies\n\n*0 of 2 trophies · 0 %*\n\nA paragraph I wrote below the trophies.\n"
        val once = TrophySection.apply(page, towerLines)
        assertEquals(
            "---\ngame_template: other\n---\n# Glass Tower\n\n## Notes\n\nMine.\n\n" +
                "## 🏆 Trophies\n\n*1 of 2 trophies · 50 %*\n\n- 🥉 First Floor — 2026-11-02\n\nA paragraph I wrote below the trophies.\n",
            once
        )
        assertEquals(once, TrophySection.apply(once, towerLines))
        // A sync writes both sections: the paragraph still comes through once.
        val sync = AchievementSection.apply(TrophySection.apply(once, towerLines), emptyList())
        assertEquals(once, sync)
    }

    @Test
    fun `a ### block with marker lines under the trophies survives and is never duplicated`() {
        val page = "---\ngame_template: other\n---\n# Glass Tower\n\n## 🏆 Trophies\n\n*0 of 2 trophies · 0 %*\n\n" +
            "### Still to get\n\n- [ ] Reach the top <!--trophy:NPWR00009_00#2-->\n- [ ] Find the cellar\n"
        val once = TrophySection.apply(page, towerLines)
        assertEquals(
            "---\ngame_template: other\n---\n# Glass Tower\n\n## 🏆 Trophies\n\n*1 of 2 trophies · 50 %*\n\n- 🥉 First Floor — 2026-11-02\n\n" +
                "### Still to get\n\n- [ ] Reach the top <!--trophy:NPWR00009_00#2-->\n- [ ] Find the cellar\n",
            once
        )
        assertEquals(once, TrophySection.apply(once, towerLines))
        assertEquals(1, once.split("### Still to get").size - 1)
        // Its marker still ticks once the trophy is earned, and the block stays.
        val ticked = TrophyMarkers.tick(TrophySection.apply(once, towerLines)) { _, id -> if (id == 2) LocalDate.of(2026, 11, 3) else null }
        assertTrue(ticked.contains("### Still to get\n\n- [x] Reach the top ✅ 2026-11-03 <!--trophy:NPWR00009_00#2-->\n- [ ] Find the cellar\n"))
    }

    @Test
    fun `an owner line between two earned lines is kept once, after the section`() {
        val both = tower.copy(trophies = tower.trophies.map { if (it.id == 2) it.copy(earnedAt = "2026-11-03T20:10:00Z") else it })
        val lines = TrophySection.lines(listOf(both), zone)
        val page = "# Glass Tower\n\n## 🏆 Trophies\n\n*1 of 2 trophies · 50 %*\n\n- 🥉 First Floor — 2026-11-02\n" +
            "My note on the first floor.\n- 🥇 Top Floor — 2026-11-03\n\n## Builds\n\nNone yet.\n"
        val once = TrophySection.apply(page, lines)
        assertEquals(
            "# Glass Tower\n\n## 🏆 Trophies\n\n*2 of 2 trophies · 100 %*\n\n- 🥉 First Floor — 2026-11-02\n- 🥇 Top Floor — 2026-11-03\n\n" +
                "My note on the first floor.\n\n## Builds\n\nNone yet.\n",
            once
        )
        assertEquals(once, TrophySection.apply(once, lines))
        assertEquals(1, once.split("- 🥇 Top Floor").size - 1)
        assertEquals(1, once.split("My note on the first floor.").size - 1)
    }

    @Test
    fun `text between the trophies and the achievements is kept`() {
        val page = "# Glass Tower\n\n## 🏆 Trophies\n\n*0 of 2 trophies · 0 %*\n\nBetween the two.\n\n$heading\n\n*0 of 1 achievements · 0 of 10 G · 0 %*\n"
        val once = AchievementSection.apply(TrophySection.apply(page, towerLines), towerAchievementLines)
        assertEquals(
            "# Glass Tower\n\n## 🏆 Trophies\n\n*1 of 2 trophies · 50 %*\n\n- 🥉 First Floor — 2026-11-02\n\nBetween the two.\n\n" +
                "$heading\n\n*1 of 1 achievements · 10 of 10 G · 100 %*\n\n- $icon Ground Floor — 2026-11-02 · 10 G\n",
            once
        )
        assertEquals(once, AchievementSection.apply(TrophySection.apply(once, towerLines), towerAchievementLines))
    }

    @Test
    fun `a footnote definition at the end of the page survives`() {
        val page = "# Glass Tower\n\nThe tower[^1] is tall.\n\n$heading\n\n*0 of 1 achievements · 0 of 10 G · 0 %*\n\n[^1]: Seen from the bay.\n"
        val once = AchievementSection.apply(page, towerAchievementLines)
        assertEquals(
            "# Glass Tower\n\nThe tower[^1] is tall.\n\n$heading\n\n" + towerAchievementLines.joinToString("\n") + "\n\n[^1]: Seen from the bay.\n",
            once
        )
        assertEquals(once, AchievementSection.apply(once, towerAchievementLines))
    }

    @Test
    fun `removing a section keeps the owner's lines where they were`() {
        val page = "# Glass Tower\n\n## 🏆 Trophies\n\n*1 of 2 trophies · 50 %*\n\n- 🥉 First Floor — 2026-11-02\nKeep this line.\n\n## Builds\n\nNone yet.\n"
        assertEquals("# Glass Tower\n\nKeep this line.\n\n## Builds\n\nNone yet.\n", TrophySection.apply(page, emptyList()))
        // At the end of the note too.
        val last = "# Glass Tower\n\n## 🏆 Trophies\n\n*1 of 2 trophies · 50 %*\n\n- 🥉 First Floor — 2026-11-02\n\nKeep this line.\n"
        assertEquals("# Glass Tower\n\nKeep this line.\n", TrophySection.apply(last, emptyList()))
        // And the achievements' own lines with or without the variation selector go, the owner's stay.
        val bare = "# Glass Tower\n\n## 🎖 Achievements\n\n*1 of 1 achievements · 10 of 10 G · 100 %*\n\n- 🎖 Ground Floor — 2026-11-02 · 10 G\nMine.\n"
        assertEquals("# Glass Tower\n\nMine.\n", AchievementSection.apply(bare, emptyList()))
    }

    @Test
    fun `the trophies go right before an existing achievements section`() {
        val page = "# Glass Tower\n\n## Notes\n\nMine.\n\n$heading\n\n" + towerAchievementLines.joinToString("\n") + "\n\n## Later\n\nMore.\n"
        val withTrophies = TrophySection.apply(page, towerLines)
        assertEquals(
            "# Glass Tower\n\n## Notes\n\nMine.\n\n## 🏆 Trophies\n\n" + towerLines.joinToString("\n") + "\n\n$heading\n\n" +
                towerAchievementLines.joinToString("\n") + "\n\n## Later\n\nMore.\n",
            withTrophies
        )
        assertEquals(withTrophies, TrophySection.apply(withTrophies, towerLines))
        assertEquals(withTrophies, AchievementSection.apply(withTrophies, towerAchievementLines))
        // Right under the heading's own line too, with a blank line before it.
        val tight = "# Glass Tower\nMine.\n$heading\n\n" + towerAchievementLines.joinToString("\n") + "\n"
        assertEquals(
            "# Glass Tower\nMine.\n\n## 🏆 Trophies\n\n" + towerLines.joinToString("\n") + "\n\n$heading\n\n" + towerAchievementLines.joinToString("\n") + "\n",
            TrophySection.apply(tight, towerLines)
        )
    }

    @Test
    fun `an oversized trophy number leaves its line as written`() {
        val content = "- [ ] Boss <!--trophy:NPWR00009_00#12345678901-->\n- [ ] Floor <!--trophy:NPWR00009_00#1-->\n"
        assertEquals(
            "- [ ] Boss <!--trophy:NPWR00009_00#12345678901-->\n- [x] Floor ✅ 2026-11-02 <!--trophy:NPWR00009_00#1-->\n",
            TrophyMarkers.tick(content) { _, _ -> LocalDate.of(2026, 11, 2) }
        )
    }

    // --- 13.4: past days ---------------------------------------------------------------------

    private val towerDay = "# 2026-11-20\n\n## 🗓️ Timeline\n\n- 08:30 Coffee\n- 21:00 🎮 Glass Tower · 1 h <!--game:Glass_Tower-->\n" +
        "- 22:15 Call\n\n## 📝 Journal\n\n*No tasks*\n"

    @Test
    fun `a CRLF past note gets its game line replaced, in time order, not duplicated`() {
        val crlf = towerDay.replace("\n", "\r\n")
        val lines = listOf(TimelineLine(LocalTime.of(20, 30), "🎮 Glass Tower · 2 h 35 <!--game:Glass_Tower-->"))
        val edited = GameTimeline.applyToNote(crlf, lines)
        assertEquals(
            towerDay.replace(
                "- 08:30 Coffee\n- 21:00 🎮 Glass Tower · 1 h <!--game:Glass_Tower-->\n",
                "- 08:30 Coffee\n- 20:30 🎮 Glass Tower · 2 h 35 <!--game:Glass_Tower-->\n"
            ).replace("\n", "\r\n"),
            edited
        )
        assertEquals(edited, GameTimeline.applyToNote(edited, lines))
        assertEquals(1, edited.split("<!--game:Glass_Tower-->").size - 1)
        // The same note in LF is edited the same way.
        assertEquals(edited.replace("\r\n", "\n"), GameTimeline.applyToNote(towerDay, lines))
    }

    @Test
    fun `a game line whose page is gone stays in a past note`() {
        val note = "# 2026-11-20\n\n## 🗓️ Timeline\n\n- 19:00 🎮 Tin Orchard · 45 min <!--game:Tin_Orchard-->\n" +
            "- 21:00 🎮 Glass Tower · 1 h <!--game:Glass_Tower-->\n\n## 📝 Journal\n\n*No tasks*\n"
        val lines = listOf(TimelineLine(LocalTime.of(21, 0), "🎮 Glass Tower · 2 h <!--game:Glass_Tower-->"))
        // Tin Orchard's page was renamed on the desktop: its key names no current page, its line is the day's history.
        val edited = GameTimeline.applyToNote(note, lines, knownKeys = setOf("Glass_Tower", "Lantern_Bay"))
        assertEquals(note.replace("Glass Tower · 1 h", "Glass Tower · 2 h"), edited)
        assertEquals(edited, GameTimeline.applyToNote(edited, lines, knownKeys = setOf("Glass_Tower", "Lantern_Bay")))
        // Without the pages' keys, every derived line is the sessions' to rewrite.
        assertFalse(GameTimeline.applyToNote(note, lines).contains("Tin Orchard"))
        assertEquals("Tin_Orchard", GameTimeline.keyOf("- 19:00 🎮 Tin Orchard · 45 min <!--game: Tin_Orchard -->"))
        assertNull(GameTimeline.keyOf("- 19:00 Dinner"))
        assertNull(GameTimeline.keyOf("- 19:00 🎮 <!--game:  -->"))
    }

    // --- 13.4: console text (GA4) --------------------------------------------------------------

    @Test
    fun `console text loses bidi and invisible characters, joiners and emoji tags kept`() {
        assertEquals("Glass Tower", oneLine("Gla\u200Bss\u00AD Tower\u202E"))
        assertEquals("Tower 2 h", oneLine("\u202ETower\u202C 2 h\u2066\u2069"))
        assertEquals("Lantern Bay", oneLine("\uFEFFLantern\u2060 Bay\u200E\u200F\u061C"))
        // A joiner (an emoji family, a script's ligature) and an emoji flag's tag characters carry meaning.
        val family = "\uD83D\uDC69\u200D\uD83D\uDC67"
        assertEquals(family, oneLine(family))
        assertEquals("a\u200Cb", oneLine("a\u200Cb"))
        val flag = "\uD83C\uDFF4\uDB40\uDC67\uDB40\uDC62\uDB40\uDC65\uDB40\uDC6E\uDB40\uDC67\uDB40\uDC7F"
        assertEquals(flag, oneLine(flag))
        assertTrue(isInvisibleFormat('\u202E'))
        assertFalse(isInvisibleFormat('\u200D'))
        // Down to the page's lines: a name with an override reads the right way round.
        val reversed = achievements(AchievementRecord("1", "Top\u202E Floor", gamerscore = 10, earnedAt = "2026-11-02T20:10:00Z"))
        assertEquals("- $icon Top Floor — 2026-11-02 · 10 G", AchievementSection.lines(listOf(reversed), zone).last())
        val trophies = TrophySection.lines(listOf(tower.copy(trophies = listOf(TrophyRecord(1, "First\u200B Floor\u2067", grade = "bronze", earnedAt = "2026-11-02T20:10:00Z")))), zone)
        assertEquals("- 🥉 First Floor — 2026-11-02", trophies.last())
    }
}
