package cloud.wafflecommons.pixelbrainreader.data.utils

import cloud.wafflecommons.pixelbrainreader.data.local.entity.DailyDashboardEntity
import cloud.wafflecommons.pixelbrainreader.data.local.entity.DailyTaskEntity
import cloud.wafflecommons.pixelbrainreader.data.local.entity.GratitudeEntity
import cloud.wafflecommons.pixelbrainreader.data.local.entity.ScratchNoteEntity
import cloud.wafflecommons.pixelbrainreader.data.local.entity.TimelineEntryEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * Pre-release safety net for the burn↔parse identity contract behind cold-start recovery
 * (P0.1) and the Google-sync triple-duplication fix.
 *
 * [MarkdownBurner.burn] is the ONLY writer and [DailyMarkdownParser.parse] the ONLY reader
 * of the burned daily note, so any field one side emits and the other drops silently
 * disappears on the next destructive Room migration — exactly how the "items appear in
 * triple" bug shipped (googleEventId/googleTaskId were dropped, so purge/dedup could never
 * reclaim re-parsed rows). This class round-trips a maximally-populated day and pins:
 *
 *  1. every field the round-trip is CONTRACTED to preserve, with explicit assertions on
 *     the external keys (gcal/gtask markers);
 *  2. idempotence — burn(parse(burn(x))) == burn(x) byte-for-byte;
 *  3. the fields the round-trip KNOWINGLY loses, pinned to their current lossy behavior
 *     so a change in either direction (fix or new loss) fails loudly.
 */
class MarkdownBurnerRoundTripTest {

    private val date: LocalDate = LocalDate.of(2026, 7, 17)
    private val dateStr = "2026-07-17"

    // ---------------------------------------------------------------------------------
    // Maximally-populated fixture
    // ---------------------------------------------------------------------------------

    private val dashboard = DailyDashboardEntity(
        date = date,
        dailyMantra = "", // Mantra is round-trip-lossy; pinned separately in its own test.
        ideasContent = "First idea\n\nSecond idea after a blank line\n- a bullet idea",
        notesContent = "Self-care note line 1\nSelf-care note line 2"
    )

    private val timeline = listOf(
        // Google Calendar-sourced entries — the exact class of row the triple-dup bug hit.
        TimelineEntryEntity(
            date = date, time = LocalTime.of(9, 0),
            content = "[Work] Standup", googleEventId = "evt_google_1"
        ),
        TimelineEntryEntity(
            date = date, time = LocalTime.of(14, 30),
            content = "Réunion équipe (unicode)", googleEventId = "evt_google_2"
        ),
        // Purely local entry — must come back with a null key so purges never touch it.
        TimelineEntryEntity(date = date, time = LocalTime.of(11, 15), content = "Local errand")
    )

    private val tasks = listOf(
        // Google task, plain.
        DailyTaskEntity(
            scheduledDate = dateStr, label = "Sync inbox",
            googleTaskId = "gtask_abc", source = "GoogleTasks"
        ),
        // Google task with EVERYTHING on one line: done + priority + time + marker.
        DailyTaskEntity(
            scheduledDate = dateStr, label = "Pay invoice", isDone = true, priority = 2,
            scheduledTime = "16:00", googleTaskId = "gtask_xyz", source = "GoogleTasks"
        ),
        // Local task with completion + priority + scheduled time.
        DailyTaskEntity(
            scheduledDate = dateStr, label = "Write report", isDone = true,
            priority = 2, scheduledTime = "10:15"
        ),
        // Local plain task (all defaults).
        DailyTaskEntity(scheduledDate = dateStr, label = "Buy milk")
    )

    private val scraps = listOf(
        ScratchNoteEntity(content = "random thought"),
        ScratchNoteEntity(content = "scrap with unicode — éà")
    )

    private val gratitudes = listOf(
        GratitudeEntity(date = dateStr, content = "Sunny weather"),
        GratitudeEntity(date = dateStr, content = "Good coffee")
    )

    private fun burnMaximal(frontmatter: String = ""): String =
        MarkdownBurner.burn(dashboard, timeline, tasks, scraps, gratitudes, frontmatter)

    // ---------------------------------------------------------------------------------
    // 1. Field-by-field survival of the maximally-populated day
    // ---------------------------------------------------------------------------------

    @Test
    fun `round-trip preserves every timeline field including google event keys`() {
        val parsed = DailyMarkdownParser.parse(date, burnMaximal())

        assertEquals(3, parsed.timeline.size)
        val byContent = parsed.timeline.associateBy { it.content }

        val standup = byContent.getValue("[Work] Standup")
        assertEquals(date, standup.date)
        assertEquals(LocalTime.of(9, 0), standup.time)
        assertEquals("evt_google_1", standup.googleEventId)

        val reunion = byContent.getValue("Réunion équipe (unicode)")
        assertEquals(date, reunion.date)
        assertEquals(LocalTime.of(14, 30), reunion.time)
        assertEquals("evt_google_2", reunion.googleEventId)

        val local = byContent.getValue("Local errand")
        assertEquals(date, local.date)
        assertEquals(LocalTime.of(11, 15), local.time)
        assertNull("A local entry must NOT gain a google key", local.googleEventId)
    }

    @Test
    fun `round-trip preserves every task field including google task keys and source`() {
        val parsed = DailyMarkdownParser.parse(date, burnMaximal())

        assertEquals(4, parsed.tasks.size)
        val byLabel = parsed.tasks.associateBy { it.label }

        val sync = byLabel.getValue("Sync inbox")
        assertEquals(dateStr, sync.scheduledDate)
        assertEquals(false, sync.isDone)
        assertEquals(1, sync.priority)
        assertNull(sync.scheduledTime)
        assertEquals("gtask_abc", sync.googleTaskId)
        assertEquals("GoogleTasks", sync.source)

        val invoice = byLabel.getValue("Pay invoice")
        assertEquals(dateStr, invoice.scheduledDate)
        assertEquals(true, invoice.isDone)
        assertEquals(2, invoice.priority)
        assertEquals("16:00", invoice.scheduledTime)
        assertEquals("gtask_xyz", invoice.googleTaskId)
        assertEquals("GoogleTasks", invoice.source)

        val report = byLabel.getValue("Write report")
        assertEquals(dateStr, report.scheduledDate)
        assertEquals(true, report.isDone)
        assertEquals(2, report.priority)
        assertEquals("10:15", report.scheduledTime)
        assertNull(report.googleTaskId)
        assertEquals("Local", report.source)

        val milk = byLabel.getValue("Buy milk")
        assertEquals(dateStr, milk.scheduledDate)
        assertEquals(false, milk.isDone)
        assertEquals(1, milk.priority)
        assertNull(milk.scheduledTime)
        assertNull(milk.googleTaskId)
        assertEquals("Local", milk.source)
        assertEquals("Journal", milk.section)
    }

    @Test
    fun `round-trip preserves multi-line ideas and notes verbatim modulo outer trim`() {
        val parsed = DailyMarkdownParser.parse(date, burnMaximal())

        // Internal blank lines and bullet formatting inside the free-text sections survive.
        assertEquals(dashboard.ideasContent, parsed.ideas)
        assertEquals(dashboard.notesContent, parsed.notes)
    }

    @Test
    fun `round-trip preserves scraps and gratitude content`() {
        val parsed = DailyMarkdownParser.parse(date, burnMaximal())

        assertEquals(listOf("random thought", "scrap with unicode — éà"), parsed.scraps)
        assertEquals(listOf("Sunny weather", "Good coffee"), parsed.gratitude)
    }

    @Test
    fun `external keys are physically present in the markdown and never leak into display text`() {
        val md = burnMaximal()

        // The invisible markers MUST be in the file — this is the triple-dup regression pin.
        assertTrue(md.contains("<!--gcal:evt_google_1-->"))
        assertTrue(md.contains("<!--gcal:evt_google_2-->"))
        assertTrue(md.contains("<!--gtask:gtask_abc-->"))
        assertTrue(md.contains("<!--gtask:gtask_xyz-->"))

        val parsed = DailyMarkdownParser.parse(date, md)
        // …and MUST be stripped back out of every visible value.
        assertFalse(parsed.timeline.any { it.content.contains("<!--") })
        assertFalse(parsed.tasks.any { it.label.contains("<!--") })
        assertFalse(parsed.ideas.contains("<!--"))
        assertFalse(parsed.notes.contains("<!--"))
    }

    @Test
    fun `a second parse of the same file yields exactly one row per google key - no stacking`() {
        // The bug's signature: every rehydrate stacked another orphan copy. Parsing the SAME
        // burned file twice (rehydrate after rehydrate) must keep yielding one row per key.
        val md = burnMaximal()
        repeat(2) {
            val parsed = DailyMarkdownParser.parse(date, md)
            assertEquals(
                listOf("evt_google_1", "evt_google_2"),
                parsed.timeline.mapNotNull { it.googleEventId }.sorted()
            )
            assertEquals(
                listOf("gtask_abc", "gtask_xyz"),
                parsed.tasks.mapNotNull { it.googleTaskId }.sorted()
            )
            assertEquals(3, parsed.timeline.size)
            assertEquals(4, parsed.tasks.size)
        }
    }

    @Test
    fun `custom frontmatter passed with delimiters is emitted verbatim and does not confuse the parser`() {
        val frontmatter = "---\ndate: $dateStr\ntags:\n  - daily\ncustom_key: kept\n---"
        val md = burnMaximal(frontmatter)

        assertTrue(md.startsWith(frontmatter))

        val parsed = DailyMarkdownParser.parse(date, md)
        // Frontmatter lines must not bleed into any parsed section.
        assertEquals(3, parsed.timeline.size)
        assertEquals(4, parsed.tasks.size)
        assertFalse(parsed.ideas.contains("custom_key"))
        assertFalse(parsed.notes.contains("custom_key"))
    }

    // ---------------------------------------------------------------------------------
    // 2. Idempotence: burn(parse(burn(x))) == burn(x)
    // ---------------------------------------------------------------------------------

    @Test
    fun `burn parse burn is byte-identical - idempotent round-trip`() {
        val burn1 = burnMaximal()
        val parsed = DailyMarkdownParser.parse(date, burn1)

        // Reconstruct the entities exactly the way rehydrateTodayFromDiskIfEmpty does.
        val dashboard2 = DailyDashboardEntity(
            date = date,
            dailyMantra = "",
            ideasContent = parsed.ideas,
            notesContent = parsed.notes
        )
        val scraps2 = parsed.scraps.map { ScratchNoteEntity(content = it) }
        val gratitudes2 = parsed.gratitude.map { GratitudeEntity(date = dateStr, content = it) }

        val burn2 = MarkdownBurner.burn(
            dashboard2, parsed.timeline, parsed.tasks, scraps2, gratitudes2, ""
        )

        assertEquals(burn1, burn2)
    }

    @Test
    fun `burn parse burn is idempotent for an empty board too`() {
        val emptyDash = DailyDashboardEntity(date = date)
        val burn1 = MarkdownBurner.burn(emptyDash, emptyList(), emptyList())
        val parsed = DailyMarkdownParser.parse(date, burn1)
        val burn2 = MarkdownBurner.burn(
            DailyDashboardEntity(date = date, ideasContent = parsed.ideas, notesContent = parsed.notes),
            parsed.timeline,
            parsed.tasks,
            parsed.scraps.map { ScratchNoteEntity(content = it) },
            parsed.gratitude.map { GratitudeEntity(date = dateStr, content = it) },
            ""
        )
        assertEquals(burn1, burn2)
    }

    // ---------------------------------------------------------------------------------
    // 3. Pinned KNOWN-LOSSY fields (documented, not endorsed — see test comments).
    //    If any of these starts surviving (a fix) or a new loss appears, this fails and
    //    forces the contract to be re-examined instead of drifting silently.
    // ---------------------------------------------------------------------------------

    @Test
    fun `LOSSY PIN - dailyMantra is burned into the file but never parsed back`() {
        // MarkdownBurner.kt:40-42 writes the mantra as "*mantra*"; DailyMarkdownParser has
        // no mantra field in ParsedDaily and both ingest() and rehydrateTodayFromDiskIfEmpty()
        // hardcode dailyMantra = "" (DailyDashboardRepository.kt:166 and :199).
        val withMantra = dashboard.copy(dailyMantra = "Carpe diem")
        val md = MarkdownBurner.burn(withMantra, timeline, tasks, scraps, gratitudes, "")

        assertTrue("Mantra must still be written to disk", md.contains("*Carpe diem*"))
        // ParsedDaily exposes no mantra — the loss is structural. We pin that the mantra
        // text at least never corrupts any parsed section.
        val parsed = DailyMarkdownParser.parse(date, md)
        assertFalse(parsed.ideas.contains("Carpe diem"))
        assertFalse(parsed.notes.contains("Carpe diem"))
        assertFalse(parsed.tasks.any { it.label.contains("Carpe diem") })
        assertFalse(parsed.timeline.any { it.content.contains("Carpe diem") })
    }

    @Test
    fun `LOSSY PIN - AI briefing cache fields are never burned at all`() {
        // MarkdownBurner only reads dailyMantra/ideasContent/notesContent off the dashboard;
        // aiWeatherBriefing / aiQuoteOfTheDay / lastAiGenerationTimestamp never reach disk
        // (acceptable: they are a regenerable cache, not user content).
        val withAi = dashboard.copy(
            aiWeatherBriefing = "UNIQUE_WEATHER_STRING",
            aiQuoteOfTheDay = "UNIQUE_QUOTE_STRING",
            lastAiGenerationTimestamp = 1234567890L
        )
        val md = MarkdownBurner.burn(withAi, timeline, tasks, scraps, gratitudes, "")
        assertFalse(md.contains("UNIQUE_WEATHER_STRING"))
        assertFalse(md.contains("UNIQUE_QUOTE_STRING"))
    }

    @Test
    fun `LOSSY PIN - timeline originalMarkdown and entity ids do not survive`() {
        // TimelineEntryEntity.originalMarkdown is never written by MarkdownBurner (only
        // time+content+marker, MarkdownBurner.kt:49-55), so the parser rebuilds it as null,
        // and primary-key UUIDs are regenerated on parse (identity is the google key).
        val entry = TimelineEntryEntity(
            id = "fixed-id-123", date = date, time = LocalTime.of(9, 0),
            content = "Standup", originalMarkdown = "- 09:00 Standup (original)",
            googleEventId = "evt_1"
        )
        val md = MarkdownBurner.burn(dashboard, listOf(entry), emptyList())
        val parsed = DailyMarkdownParser.parse(date, md).timeline.single()

        assertNull("originalMarkdown is a pinned round-trip loss", parsed.originalMarkdown)
        assertFalse("PK UUIDs are regenerated, never round-tripped", parsed.id == "fixed-id-123")
        assertEquals("evt_1", parsed.googleEventId) // …but the EXTERNAL key must survive.
    }

    @Test
    fun `LOSSY PIN - sub-minute timeline precision is truncated to HH-mm`() {
        // MarkdownBurner.kt:50 formats with pattern "HH:mm" — seconds/nanos are dropped.
        val entry = TimelineEntryEntity(date = date, time = LocalTime.of(9, 0, 45), content = "Standup")
        val md = MarkdownBurner.burn(dashboard, listOf(entry), emptyList())
        val parsed = DailyMarkdownParser.parse(date, md).timeline.single()
        assertEquals(LocalTime.of(9, 0), parsed.time)
    }

    @Test
    fun `LOSSY PIN - task priority above 2 collapses to 2 and custom section collapses to Journal`() {
        // MarkdownBurner.kt:71 encodes any priority>1 as a single "‼️" mark and the parser
        // (DailyMarkdownParser.kt:77) decodes "contains ‼️" as exactly 2. Similarly the
        // section column is not serialized, so the parser default "Journal" always wins.
        val task = DailyTaskEntity(
            scheduledDate = dateStr, label = "Urgent thing", priority = 3, section = "LifeOS"
        )
        val md = MarkdownBurner.burn(dashboard, emptyList(), listOf(task))
        val parsed = DailyMarkdownParser.parse(date, md).tasks.single()

        assertEquals("Urgent thing", parsed.label)
        assertEquals(2, parsed.priority)
        assertEquals("Journal", parsed.section)
    }

    @Test
    fun `LOSSY PIN - a custom source without a google key collapses to Local`() {
        // Source is derived purely from googleTaskId presence (DailyMarkdownParser.kt:100),
        // so any third-party source string without a gtask marker degrades to "Local".
        val task = DailyTaskEntity(scheduledDate = dateStr, label = "Imported", source = "SomeOtherSync")
        val md = MarkdownBurner.burn(dashboard, emptyList(), listOf(task))
        val parsed = DailyMarkdownParser.parse(date, md).tasks.single()
        assertEquals("Local", parsed.source)
        assertNull(parsed.googleTaskId)
    }

    @Test
    fun `LOSSY PIN - scrap metadata and gratitude metadata are content-only on the way back`() {
        // ParsedDaily models scraps/gratitude as List<String>: ScratchNoteEntity.color /
        // isPromoted / createdAt and GratitudeEntity.createdAt are pinned losses (the
        // rehydrate path mints fresh entities from bare content strings).
        val scrap = ScratchNoteEntity(content = "colored scrap", color = 0xFFFF0000.toInt(), createdAt = 42L)
        val gratitude = GratitudeEntity(date = dateStr, content = "old gratitude", createdAt = 42L)
        val md = MarkdownBurner.burn(dashboard, emptyList(), emptyList(), listOf(scrap), listOf(gratitude))
        val parsed = DailyMarkdownParser.parse(date, md)

        assertEquals(listOf("colored scrap"), parsed.scraps)
        assertEquals(listOf("old gratitude"), parsed.gratitude)
    }

    @Test
    fun `date formats agree between burner and parser fixtures`() {
        // Guards the implicit contract that the burner's ISO_DATE header/scheduledDate and
        // the parser's ISO_LOCAL_DATE reconstruction are the same calendar day format.
        val md = burnMaximal()
        assertTrue(md.contains("# ${date.format(DateTimeFormatter.ISO_DATE)}"))
        val parsed = DailyMarkdownParser.parse(date, md)
        assertTrue(parsed.tasks.all { it.scheduledDate == dateStr })
        assertTrue(parsed.timeline.all { it.date == date })
    }
}
