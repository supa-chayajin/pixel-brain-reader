package cloud.wafflecommons.pixelbrainreader.data.review

import cloud.wafflecommons.pixelbrainreader.data.gamification.XpGainEntry
import cloud.wafflecommons.pixelbrainreader.data.local.entity.ChoreEntity
import cloud.wafflecommons.pixelbrainreader.data.local.entity.DailyTaskEntity
import cloud.wafflecommons.pixelbrainreader.data.local.entity.MoodEntity
import cloud.wafflecommons.pixelbrainreader.data.model.HabitConfig
import cloud.wafflecommons.pixelbrainreader.data.model.HabitLogEntry
import cloud.wafflecommons.pixelbrainreader.data.model.HabitStatus
import cloud.wafflecommons.pixelbrainreader.data.remote.FileHistory
import cloud.wafflecommons.pixelbrainreader.data.work.LogEntry
import cloud.wafflecommons.pixelbrainreader.data.work.LogKind
import cloud.wafflecommons.pixelbrainreader.data.work.OverloadSignal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class WeeklyReviewTest {

    private val zone = ZoneId.of("Europe/Paris")
    private val w46 = ReviewWeek.of(LocalDate.of(2026, 11, 11))

    private fun millis(at: LocalDateTime) = at.atZone(zone).toInstant().toEpochMilli()

    // --- The ISO week ---

    @Test
    fun `the week of a date is its ISO week, Monday to Sunday`() {
        assertEquals("2026-W46", w46.key)
        assertEquals(LocalDate.of(2026, 11, 9), w46.monday)
        assertEquals(LocalDate.of(2026, 11, 15), w46.sunday)
        assertEquals(7, w46.days.size)
        assertEquals("10_Journal/Weekly/2026-W46.md", w46.path)
        assertEquals(w46, ReviewWeek.fromPath(w46.path))
        assertEquals("2026-W45", w46.previous().key)
    }

    @Test
    fun `the last days of December can belong to the first week of the next year`() {
        val w01 = ReviewWeek.of(LocalDate.of(2025, 12, 29))
        assertEquals("2026-W01", w01.key)
        assertEquals(LocalDate.of(2026, 1, 4), w01.sunday)
        assertEquals("2026-W53", ReviewWeek.of(LocalDate.of(2027, 1, 1)).key)
        assertEquals("2026-W53", ReviewWeek.of(LocalDate.of(2026, 12, 31)).key)
        assertEquals("2025-W52", w01.previous().key)
        assertEquals("Semaine 1 — du 29 décembre 2025 au 4 janvier 2026", WeeklyReviewFormat.title(w01))
    }

    @Test
    fun `only a weekly review path is a review, and a week that does not exist is not`() {
        assertTrue(ReviewWeek.isReview("10_Journal/Weekly/2026-W53.md"))
        assertFalse(ReviewWeek.isReview("10_Journal/Weekly/2026-W54.md"))
        assertFalse(ReviewWeek.isReview("10_Journal/2026-11-15.md"))
        assertFalse(ReviewWeek.isReview("10_Journal/Weekly/2026-W46 (2).md"))
    }

    // --- When a week is due ---

    @Test
    fun `a week is due once its Sunday reaches the nightly run`() {
        assertEquals(w46, ReviewWeek.lastDue(LocalDateTime.of(2026, 11, 15, 23, 50)))
        assertEquals(w46.previous(), ReviewWeek.lastDue(LocalDateTime.of(2026, 11, 15, 8, 0)))
        assertEquals(w46, ReviewWeek.lastDue(LocalDateTime.of(2026, 11, 16, 0, 10)))
        assertEquals(w46, ReviewWeek.lastDue(LocalDateTime.of(2026, 11, 18, 12, 0)))
    }

    // --- The plan's round trip ---

    @Test
    fun `an untouched note has no plan, and the owner's lines read back exactly`() {
        val note = WeeklyReviewFormat.render(w46, WeeklyFacts())
        assertEquals(emptyList<String>(), WeeklyReviewFormat.planLines(note))

        val filled = note.replace(
            WeeklyReviewFormat.PLAN_HEADING + "\n\n-\n",
            WeeklyReviewFormat.PLAN_HEADING + "\n\n- Finir la refonte\n- Appeler la banque\n"
        )
        assertEquals(listOf("- Finir la refonte", "- Appeler la banque"), WeeklyReviewFormat.planLines(filled))
        assertEquals(
            listOf("- Finir la refonte", "- Appeler la banque"),
            WeeklyReviewFormat.planLines(filled.replace("\n", "\r\n"))
        )
    }

    @Test
    fun `last week's plan comes back in the next review and is not its plan`() {
        val plan = listOf("- Finir la refonte", "- [ ] Appeler la banque")
        val next = WeeklyReviewFormat.render(w46.next(), WeeklyFacts(lastWeekPlan = plan))
        assertTrue(next.contains(WeeklyReviewFormat.LAST_PLAN_HEADING + "\n\n- Finir la refonte\n- [ ] Appeler la banque\n"))
        assertEquals(emptyList<String>(), WeeklyReviewFormat.planLines(next))
    }

    @Test
    fun `prompts and placeholders never count as a plan`() {
        val note = WeeklyReviewFormat.render(w46, WeeklyFacts())
            .replace("- Ce qui a marché :", "- Ce qui a marché : la course")
            .replace(WeeklyReviewFormat.PLAN_HEADING + "\n\n-\n", WeeklyReviewFormat.PLAN_HEADING + "\n\n-\n- [ ]\n\n")
        assertEquals(emptyList<String>(), WeeklyReviewFormat.planLines(note))
        assertEquals(emptyList<String>(), WeeklyReviewFormat.planLines("# Une note sans plan\n"))
    }

    // --- The note ---

    @Test
    fun `a section with nothing measured is left out`() {
        val note = WeeklyReviewFormat.render(w46, WeeklyFacts())
        assertTrue(note.startsWith("---\n"))
        assertTrue(note.contains("week: 2026-W46\n"))
        assertTrue(note.contains("\n# Semaine 46 — du 9 au 15 novembre 2026\n"))
        for (absent in listOf("Habitudes", "Humeur", "Sommeil", "Tâches", "XP", "## 📝 Notes", "Travail", "semaine dernière")) {
            assertFalse(absent, note.contains(absent))
        }
        assertTrue(note.contains("## 🪞 Réflexion\n\n- Ce qui a marché :\n- Ce qui a coincé :\n- Ce que je change :\n"))
        assertTrue(note.endsWith(WeeklyReviewFormat.PLAN_HEADING + "\n\n-\n"))
    }

    @Test
    fun `the measured week reads in French`() {
        val facts = WeeklyFacts(
            habits = listOf(HabitWeek("Méditation", 5, 7), HabitWeek("Course", 2, null)),
            mood = MoodWeek(3.84, 6, 3.4),
            health = HealthWeek(sleepMinutesAverage = 425, nights = 6, stepsAverage = 8412, stepDays = 7),
            tasksDone = 18, tasksTotal = 24,
            choresDone = listOf("Lessive", "Poubelles"),
            xpGained = 420, level = 9,
            notes = NoteActivity(listOf("[[30_Areas/Lecture]]"), listOf("[[40_Resources/Kotlin]]"), workCreated = 1, workEdited = 3)
        )
        val note = WeeklyReviewFormat.render(w46, facts)
        assertTrue(note.contains("## 🔁 Habitudes\n\n- Méditation : 5/7 jours\n- Course : 2 fois\n"))
        assertTrue(note.contains("- Moyenne : 3,8/5 sur 6 jours (semaine précédente : 3,4)\n"))
        assertTrue(note.contains("- Sommeil moyen : 7 h 05 (6 nuits)\n- Pas : 8 412 par jour (7 jours)\n"))
        assertTrue(note.contains("- Tâches faites : 18 sur 24\n- Corvées faites : 2 (Lessive, Poubelles)\n"))
        assertTrue(note.contains("- +420 XP · niveau 9\n"))
        assertTrue(note.contains("- Créées (1) : [[30_Areas/Lecture]]\n- Modifiées (1) : [[40_Resources/Kotlin]]\n"))
        assertTrue(note.contains("- Dans les dossiers de travail : 1 créée, 3 modifiées\n"))
    }

    @Test
    fun `a long list of notes is cut at ten`() {
        val many = (1..13).map { "[[30_Areas/Note $it]]" }
        val note = WeeklyReviewFormat.render(w46, WeeklyFacts(notes = NoteActivity(emptyList(), many, 0, 0)))
        assertTrue(note.contains("- Modifiées (13) : " + many.take(10).joinToString(", ") + " et 3 autres\n"))
    }

    @Test
    fun `the work section holds counts and links, never the log's text`() {
        val entries = listOf(
            LogEntry(LocalDateTime.of(2026, 11, 10, 9, 0), LogKind.FACT, "Migration du serveur confidentiel"),
            LogEntry(LocalDateTime.of(2026, 11, 10, 10, 0), LogKind.DECISION, "Base choisie", reference = "TICKET-1 · [[ADR 2026-11-10 Base choisie]]"),
            LogEntry(LocalDateTime.of(2026, 11, 12, 11, 0), LogKind.DECISION, "Un autre choix"),
            LogEntry(LocalDateTime.of(2026, 11, 13, 15, 0), LogKind.REQUEST, "Une demande")
        )
        val work = WeeklyActivity.work(entries, objectivesMoved = 2, objectivesStalled = 1, meetings = 7,
            overload = OverloadSignal(35, 31 * 60, 23 * 60, emptyList()), logLinks = listOf("[[Journal/2026-11]]"))
        assertEquals(1, work.decisionsWithoutRecord)
        val note = WeeklyReviewFormat.render(w46, WeeklyFacts(work = work))
        assertTrue(note.contains("- Journal de mission : 4 entrées (1 fait, 2 décisions, 1 demande) · [[Journal/2026-11]]\n"))
        assertTrue(note.contains("- Décisions sans ADR : 1\n"))
        assertTrue(note.contains("- Objectifs : 2 ont avancé, 1 au point mort\n"))
        assertTrue(note.contains("- Réunions : 7\n"))
        assertTrue(note.contains("- Surcharge : charge +35 % (31 h contre 23 h habituellement)\n"))
        for (text in entries.map { it.text }) assertFalse(text, note.contains(text))
    }

    @Test
    fun `an empty work week leaves the section out`() {
        val work = WeeklyActivity.work(emptyList(), 0, 0, 0, null, emptyList())
        assertTrue(work.isEmpty)
        assertFalse(WeeklyReviewFormat.render(w46, WeeklyFacts(work = work)).contains("Travail"))
    }

    // --- The facts ---

    @Test
    fun `habits count the days done out of the days planned`() {
        val week = w46.days.map { it.toString() }
        val configs = listOf(
            HabitConfig(id = "mwf", title = "Course", frequency = listOf("MON", "WED", "FRI"), sortOrder = 1),
            HabitConfig(id = "daily", title = "Méditation", sortOrder = 0),
            HabitConfig(id = "interval", title = "Vélo", scheduleMode = "INTERVAL", intervalCount = 3, sortOrder = 2),
            HabitConfig(id = "old", title = "Archivée", archived = true),
            HabitConfig(id = "new", title = "Lecture", createdDate = "2026-11-12", sortOrder = 3),
            HabitConfig(id = "none", title = "Jamais prévue", frequency = listOf("SAT"), scheduleMode = "BIWEEKLY", sortOrder = 4)
        )
        val logs = listOf(
            HabitLogEntry("mwf", week[0], 1.0, HabitStatus.COMPLETED),
            HabitLogEntry("mwf", week[2], 1.0, HabitStatus.COMPLETED),
            HabitLogEntry("mwf", week[4], 1.0, HabitStatus.SKIPPED),
            HabitLogEntry("daily", week[1], 1.0, HabitStatus.COMPLETED),
            HabitLogEntry("daily", "2026-11-08", 1.0, HabitStatus.COMPLETED),
            HabitLogEntry("interval", week[3], 1.0, HabitStatus.COMPLETED),
            HabitLogEntry("old", week[3], 1.0, HabitStatus.COMPLETED)
        )
        assertEquals(
            listOf(HabitWeek("Méditation", 1, 7), HabitWeek("Course", 2, 3), HabitWeek("Vélo", 1, null), HabitWeek("Lecture", 0, 4)),
            WeeklyActivity.habits(configs, logs, w46)
        )
    }

    @Test
    fun `mood averages each day first, then compares with the week before`() {
        fun mood(date: String, score: Int, at: Long) = MoodEntity(at, date, "09:00", score, "", "", null)
        val week = listOf(mood("2026-11-09", 3, 1), mood("2026-11-09", 5, 2), mood("2026-11-10", 2, 3))
        assertEquals(MoodWeek(3.0, 2, 4.0), WeeklyActivity.mood(week, listOf(mood("2026-11-03", 4, 4))))
        assertNull(WeeklyActivity.mood(emptyList(), emptyList()))
    }

    @Test
    fun `sleep and steps average only the days that measured them`() {
        val days = listOf(DayHealth(420, 8000), DayHealth(0, 10000), DayHealth(480, 0), DayHealth(0, 0))
        assertEquals(HealthWeek(450, 2, 9000, 2), WeeklyActivity.health(days))
        assertNull(WeeklyActivity.health(listOf(DayHealth(0, 0))))
    }

    @Test
    fun `tasks are counted on the week's boards, chores by their last done date`() {
        fun task(done: Boolean) = DailyTaskEntity(scheduledDate = "2026-11-10", label = "t", isDone = done)
        assertEquals(2 to 3, WeeklyActivity.tasks(listOf(listOf(task(true), task(false)), emptyList(), listOf(task(true)))))
        fun chore(name: String, last: String, archived: Boolean = false) =
            ChoreEntity(id = name, name = name, roomId = "r", baseEffort = 1, frequencyDays = 7, lastDoneDate = last, archived = archived)
        val chores = listOf(chore("Poubelles", "2026-11-14"), chore("Lessive", "2026-11-09"), chore("Vitres", "2026-11-08"),
            chore("Cave", "2026-11-16"), chore("Garage", "2026-11-10", archived = true))
        assertEquals(listOf("Lessive", "Poubelles"), WeeklyActivity.chores(chores, w46))
    }

    @Test
    fun `XP counts the gains inside the week`() {
        fun gain(at: LocalDateTime, amount: Double) = XpGainEntry(millis(at), amount, "test", null)
        val history = listOf(
            gain(LocalDateTime.of(2026, 11, 9, 0, 0), 10.0),
            gain(LocalDateTime.of(2026, 11, 15, 23, 59), 25.4),
            gain(LocalDateTime.of(2026, 11, 16, 0, 0), 100.0),
            gain(LocalDateTime.of(2026, 11, 8, 23, 59), 100.0)
        )
        assertEquals(35, WeeklyActivity.xp(history, w46, zone))
        assertNull(WeeklyActivity.xp(emptyList(), w46, zone))
    }

    @Test
    fun `notes come from git, personal ones by name, work ones counted, generated and private ones left out`() {
        val inWeek = millis(LocalDateTime.of(2026, 11, 12, 10, 0))
        val later = millis(LocalDateTime.of(2026, 11, 14, 10, 0))
        val before = millis(LocalDateTime.of(2026, 10, 1, 10, 0))
        val paths = mapOf(
            "30_Areas/Nouvelle.md" to (inWeek to inWeek),
            "40_Resources/Kotlin.md" to (before to later),
            "40_Resources/Ancienne.md" to (before to before),
            "Travail/Compte rendu.md" to (inWeek to inWeek),
            "Travail/Plan.md" to (before to inWeek),
            "10_Journal/2026-11-12.md" to (inWeek to inWeek),
            "10_Journal/Weekly/2026-W45.md" to (inWeek to inWeek),
            "10_Journal/data/habits/2026.json" to (inWeek to inWeek),
            "99_Private/Secret.md.enc" to (inWeek to inWeek),
            "99_Private/People/Quelqu'un.md" to (inWeek to inWeek),
            "99_System/presets.md" to (before to inWeek),
            "20_Projects/21_App/Worklog/2026-11-12.md" to (inWeek to inWeek),
            "20_Projects/21_App/Repo/README.md" to (inWeek to inWeek),
            "20_Projects/21_App/Claude/Memory/notes.md" to (inWeek to inWeek),
            "20_Projects/21_App/Handoff.md" to (before to inWeek),
            "20_Projects/21_App/TODO.md" to (before to inWeek),
            "40_Resources/Supprimée.md" to (inWeek to inWeek)
        )
        val history = FileHistory(
            lastChanged = paths.mapValues { it.value.second },
            firstSeen = paths.mapValues { it.value.first },
            changes = paths.mapValues { 1 }
        )
        val notes = WeeklyActivity.notes(
            history, w46, zone,
            isWork = { it.startsWith("Travail/") },
            keep = { it != "40_Resources/Supprimée.md" && it != "20_Projects/21_App/TODO.md" }
        )
        assertEquals(listOf("[[30_Areas/Nouvelle]]"), notes.created)
        assertEquals(listOf("[[40_Resources/Kotlin]]"), notes.edited)
        assertEquals(1, notes.workCreated)
        assertEquals(1, notes.workEdited)
    }
}
