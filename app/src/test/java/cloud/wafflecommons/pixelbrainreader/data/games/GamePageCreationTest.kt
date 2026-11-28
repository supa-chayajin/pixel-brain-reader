package cloud.wafflecommons.pixelbrainreader.data.games

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

/** 13.3 (XB4): a page for each played game no page claims, where and as the owner's pages are. */
class GamePageCreationTest {

    private fun page(path: String, title: String = path.substringAfterLast('/').removeSuffix(".md").replace('_', ' '), vararg aliases: String) =
        GamePage(path, title, aliases.toList(), GameTemplate.OTHER, GameState.BACKLOG, null, null)

    private val library = listOf(
        page("30_Areas/Games/Brick_Wars/Brick_Wars.md", "Brick Wars"),
        page("30_Areas/Games/Ring_of_Ash/Ring_of_Ash.md", "Ring of Ash", "RoA")
    )
    private val evening = Instant.parse("2026-11-27T20:00:00Z")
    private val today = LocalDate.of(2026, 11, 27)

    @Test
    fun `the layout is learned from the pages - own folders under the most common root`() {
        val pages = listOf(
            page("30_Areas/Games/Ring_of_Ash/Ring_of_Ash.md"),
            page("30_Areas/Games/Brick_Wars/Brick_Wars.md"),
            page("30_Areas/Old/Outlaw_Trail_2/Outlaw_Trail_2.md"),
            page("30_Areas/Games/Loose.md")
        )
        assertEquals(GamePageLayout("30_Areas/Games", ownFolder = true, separator = '_'), GamePageCreation.layout(pages))
        // Ties: the alphabetically first root.
        assertEquals("A", GamePageCreation.layout(listOf(page("B/X/X.md"), page("A/Y/Y.md")))!!.root)
        // A page in its own folder at the vault's root: the root is empty.
        assertEquals(GamePageLayout("", ownFolder = true, separator = '_'), GamePageCreation.layout(listOf(page("Ring_of_Ash/Ring_of_Ash.md"))))
        val plan = GamePageCreation.plan(listOf(PageCandidate("1", "Glass Tower")), listOf(page("Ring_of_Ash/Ring_of_Ash.md")), emptyList(), GameConsole.XBOX)
        assertEquals("Glass_Tower/Glass_Tower.md", plan.single().path)
        assertEquals(
            "30_Areas/Games/Glass_Tower/Glass_Tower.md",
            GamePageCreation.plan(listOf(PageCandidate("1", "Glass Tower")), pages, emptyList(), GameConsole.XBOX).single().path
        )
    }

    @Test
    fun `flat pages give a flat layout`() {
        val pages = listOf(
            page("30_Areas/Games/Ring of Ash.md"),
            page("30_Areas/Games/Brick Wars.md"),
            page("30_Areas/Games/Outlaw_Trail_2/Outlaw_Trail_2.md"),
            page("00_Inbox/Stray.md")
        )
        assertEquals(GamePageLayout("30_Areas/Games", ownFolder = false, separator = ' '), GamePageCreation.layout(pages))
        assertEquals(
            "30_Areas/Games/Glass Tower.md",
            GamePageCreation.plan(listOf(PageCandidate("1", "Glass Tower")), pages, emptyList(), GameConsole.PSN).single().path
        )
        // At the vault's root.
        assertEquals("Glass_Tower.md", GamePageCreation.plan(listOf(PageCandidate("1", "Glass Tower")), listOf(page("Ring.md")), emptyList(), GameConsole.PSN).single().path)
    }

    @Test
    fun `no page, no layout, nothing planned`() {
        assertNull(GamePageCreation.layout(emptyList()))
        assertTrue(GamePageCreation.plan(listOf(PageCandidate("PPSA00001_00", "GLASS TOWER")), emptyList(), emptyList(), GameConsole.PSN).isEmpty())
    }

    @Test
    fun `separator follows the existing keys`() {
        // As many of each: the underscore.
        assertEquals('_', GamePageCreation.layout(listOf(page("G/Ring_of_Ash/Ring_of_Ash.md"), page("G/Brick Wars/Brick Wars.md")))!!.separator)
        val spaced = listOf(page("G/Ring of Ash/Ring of Ash.md"), page("G/Brick Wars/Brick Wars.md"), page("G/Outlaw_2/Outlaw_2.md"), page("G/Solo/Solo.md"))
        assertEquals(' ', GamePageCreation.layout(spaced)!!.separator)
        assertEquals("G/Glass Tower/Glass Tower.md", GamePageCreation.plan(listOf(PageCandidate("1", "Glass Tower")), spaced, emptyList(), GameConsole.XBOX).single().path)
    }

    @Test
    fun `a console name becomes a title and a key a file system takes`() {
        assertEquals("GLASS TOWER", GamePageCreation.pageTitle("  GLASS TOWER™ "))
        assertEquals("BRICK Wars: The Saga", GamePageCreation.pageTitle("BRICK® Wars™: The   Saga©"))
        assertEquals("Ring of Ash", GamePageCreation.pageTitle("Ring\tof\nAsh"))
        assertEquals("Brick_Wars_The_Saga", GamePageCreation.keyOf("Brick Wars: The Saga", '_'))
        assertEquals("Brick Wars The Saga", GamePageCreation.keyOf("Brick Wars: The Saga", ' '))
        assertEquals("Ash_Ember_Live", GamePageCreation.keyOf("Ash/Ember Live", '_'))
        assertEquals("Outlaw_Trail_2", GamePageCreation.keyOf("Outlaw Trail #2", '_'))
        assertEquals("Ring_of_Ash_DLC_2", GamePageCreation.keyOf("Ring <of> \"Ash\" | [DLC]^2*?\\", '_'))
        assertEquals("Ring_of_Ash", GamePageCreation.keyOf("Ring of Ash...", '_'))
        assertEquals("Ring_of_Ash", GamePageCreation.keyOf("Ring of Ash. .", '_'))
        assertEquals("St._Brick", GamePageCreation.keyOf("St. Brick", '_'))
        assertEquals("Ring_of_Ash", GamePageCreation.keyOf("Ring\u0000of\u0007Ash", '_'))
        // Never a hidden file.
        assertEquals("void_RE", GamePageCreation.keyOf(".void//RE", '_'))
        assertEquals("CON_", GamePageCreation.keyOf("CON", '_'))
        assertEquals("Lpt1_", GamePageCreation.keyOf("Lpt1", '_'))
        assertEquals("nul_", GamePageCreation.keyOf("nul", '_'))
        assertEquals("Console", GamePageCreation.keyOf("Console", '_'))
        assertEquals("", GamePageCreation.keyOf("???", '_'))
        // At most 80 characters, without a separator or a dot left at the cut.
        assertEquals("Word_".repeat(16).dropLast(1), GamePageCreation.keyOf("Word ".repeat(30), '_'))
        assertEquals(80, GamePageCreation.keyOf("W".repeat(120), '_').length)
        // A name that leaves no key gets no page.
        assertTrue(GamePageCreation.plan(listOf(PageCandidate("1", "???")), library, emptyList(), GameConsole.XBOX).isEmpty())
        assertTrue(GamePageCreation.plan(listOf(PageCandidate("1", "™")), library, emptyList(), GameConsole.XBOX).isEmpty())
    }

    @Test
    fun `a console name's line breaks and control characters never reach the page`() {
        // A bell and a line break in the name the console sent.
        val plan = GamePageCreation.plan(listOf(PageCandidate("1", "Glass\u0007 Tower™\n## Notes")), library, emptyList(), GameConsole.XBOX).single()
        assertEquals("Glass Tower ## Notes", plan.title)
        assertEquals(listOf("Glass Tower™ ## Notes"), plan.aliases)
        val content = GamePageCreation.render(plan, template = null, started = null, today = today).content
        assertFalse(content.any { it.isISOControl() && it != '\n' })
        assertTrue(content.contains("\naliases:\n  - \"Glass Tower™ ## Notes\"\n"))
        assertTrue(content.endsWith("\n# Glass Tower ## Notes\n"))
        assertEquals(listOf("Glass Tower™ ## Notes"), GamePages.parse(plan.path, content)!!.aliases)
    }

    @Test
    fun `siblings and equal names make one page`() {
        val plans = GamePageCreation.plan(
            listOf(
                PageCandidate("PPSA00002_00", "GLASS TOWER™", siblings = listOf("CUSA00002_00"), lastPlayed = evening),
                PageCandidate("PPSA00003_00", "OUTLAW TRAIL 2", lastPlayed = evening.minusSeconds(3_600)),
                PageCandidate("CUSA00002_00", "Glass Tower", siblings = listOf("PPSA00002_00"), lastPlayed = evening.minusSeconds(86_400)),
                PageCandidate("CUSA00003_00", "Outlaw Trail 2", lastPlayed = evening.minusSeconds(7_200))
            ),
            library, emptyList(), GameConsole.PSN
        )
        assertEquals(2, plans.size)
        val tower = plans[0]
        assertEquals("30_Areas/Games/GLASS_TOWER/GLASS_TOWER.md", tower.path)
        assertEquals("GLASS TOWER", tower.title)
        assertEquals(listOf("GLASS TOWER™", "Glass Tower"), tower.aliases)
        assertEquals(listOf("PPSA00002_00", "CUSA00002_00"), tower.titleIds)
        assertEquals(GameConsole.PSN, tower.console)
        val outlaw = plans[1]
        assertEquals("OUTLAW TRAIL 2", outlaw.title)
        assertEquals(listOf("Outlaw Trail 2"), outlaw.aliases)
        assertEquals(listOf("PPSA00003_00", "CUSA00003_00"), outlaw.titleIds)
        // Xbox's 360 version and its modern one, by their ids only.
        val xbox = GamePageCreation.plan(
            listOf(
                PageCandidate("1915865634", "Glass Tower", siblings = listOf("1234567890")),
                PageCandidate("1234567890", "Glass Tower Classic", siblings = listOf("1915865634")),
                // Two titles pointing at the same modern id not played here.
                PageCandidate("1111111111", "Outlaw Trail 2", siblings = listOf("2222222222")),
                PageCandidate("3333333333", "Outlaw Trail Two", siblings = listOf("2222222222"))
            ),
            library, emptyList(), GameConsole.XBOX
        )
        assertEquals(listOf(listOf("1915865634", "1234567890"), listOf("1111111111", "3333333333")), xbox.map { it.titleIds })
        assertEquals(listOf("Glass Tower Classic"), xbox[0].aliases)
    }

    @Test
    fun `a name some page answers to gets no page`() {
        val plans = GamePageCreation.plan(
            listOf(
                PageCandidate("1", "RING OF ASH™"),
                PageCandidate("2", "Brick Wars: The Saga"),
                PageCandidate("3", "roa"),
                PageCandidate("4", "Glass Tower")
            ),
            library, emptyList(), GameConsole.XBOX
        )
        assertEquals(listOf("Glass Tower"), plans.map { it.title })
        // Two pages tied on the name still answer to it: no third one.
        val twins = listOf(page("A/Ring/Ring.md", "Ring of Ash"), page("B/Ring_2/Ring_2.md", "Ring of Ash"))
        assertTrue(GamePageCreation.plan(listOf(PageCandidate("1", "Ring of Ash")), twins, emptyList(), GameConsole.XBOX).isEmpty())
        // A page marked exact doesn't answer to a longer name.
        val exact = library + page("30_Areas/Games/Glass_Tower/Glass_Tower.md", "Glass Tower").copy(exactName = true)
        assertEquals(
            listOf("Glass Tower Dungeons"),
            GamePageCreation.plan(listOf(PageCandidate("1", "Glass Tower Dungeons")), exact, emptyList(), GameConsole.XBOX).map { it.title }
        )
    }

    @Test
    fun `a taken path, also with another case, is skipped`() {
        val plans = GamePageCreation.plan(
            listOf(PageCandidate("1", "Glass Tower"), PageCandidate("2", "Outlaw Trail 2"), PageCandidate("3", "Coral Reef")),
            library,
            takenPaths = listOf(
                // The same file with another case.
                "30_Areas/Games/glass_tower/glass_tower.md",
                // The folder with another case.
                "30_Areas/Games/CORAL_REEF/notes.md",
                // The same folder, the same case: the page goes in it.
                "30_Areas/Games/Outlaw_Trail_2/Builds.md"
            ),
            console = GameConsole.XBOX
        )
        assertEquals(listOf("30_Areas/Games/Outlaw_Trail_2/Outlaw_Trail_2.md"), plans.map { it.path })
        // Two plans of one call never take the same path either: names cut at 80 characters.
        val stem = "Saga ".repeat(20)
        val twice = GamePageCreation.plan(listOf(PageCandidate("1", stem + "One"), PageCandidate("2", stem + "Two")), library, emptyList(), GameConsole.XBOX)
        assertEquals(listOf(listOf("1")), twice.map { it.titleIds })
    }

    @Test
    fun `at most the limit, most recently played first`() {
        val candidates = (1..5).map { PageCandidate("$it", "Game Number $it", lastPlayed = evening.minusSeconds(it * 3_600L)) } +
            PageCandidate("never", "Never Played Here")
        val plans = GamePageCreation.plan(candidates.reversed(), library, emptyList(), GameConsole.PSN, limit = 3)
        assertEquals(listOf("Game Number 1", "Game Number 2", "Game Number 3"), plans.map { it.title })
        assertEquals(50, GamePageCreation.MAX_PER_SYNC)
        // A title with no last-played time comes last.
        assertEquals("Never Played Here", GamePageCreation.plan(candidates.reversed(), library, emptyList(), GameConsole.PSN).last().title)
        // A dropped game leaves its place to the next one.
        val taken = GamePageCreation.plan(
            candidates.reversed(), library, listOf("30_Areas/Games/Game_Number_1/Game_Number_1.md"), GameConsole.PSN, limit = 3
        )
        assertEquals(listOf("Game Number 2", "Game Number 3", "Game Number 4"), taken.map { it.title })
    }

    private val plan = PagePlan(
        path = "30_Areas/Games/GLASS_TOWER/GLASS_TOWER.md",
        title = "GLASS TOWER",
        aliases = listOf("GLASS TOWER™", "Glass \"Tower\""),
        titleIds = listOf("PPSA00002_00"),
        console = GameConsole.PSN
    )

    @Test
    fun `the template is filled and the game keys set`() {
        val template = """
            ---
            title: "{{title}}"
            date_created: '2026-01-01'
            date_updated: '2026-01-01'
            type: area
            status: active
            tags:
              - games
            aliases: []
            game_template: other
            game_state: next
            played_before: ''
            game_maps: []
            ---
            # 🎮 {{title}}

            ## Notes

            Written {{date}}.
        """.trimIndent() + "\n"
        val made = GamePageCreation.render(plan, template, started = LocalDate.of(2026, 3, 1), today = today)
        assertEquals(
            """
            ---
            title: "GLASS TOWER"
            date_created: '2026-11-27'
            date_updated: '2026-11-27'
            type: area
            status: active
            tags:
              - games
            aliases:
              - "GLASS TOWER™"
              - "Glass \"Tower\""
            game_template: other
            game_state: next
            played_before: ''
            game_maps: []
            game_match: exact
            started: '2026-03-01'
            ---
            # 🎮 GLASS TOWER

            ## Notes

            Written 2026-11-27.
            """.trimIndent() + "\n",
            made.content
        )
        assertEquals(plan, made.plan)
        val page = GamePages.parse(plan.path, made.content)!!
        assertEquals("GLASS TOWER", page.title)
        assertEquals(listOf("GLASS TOWER™", "Glass \"Tower\""), page.aliases)
        assertEquals(GameState.NEXT, page.state)
        assertEquals(LocalDate.of(2026, 3, 1), page.started)
        assertTrue(page.exactName)
        // A template without a state: the backlog. Without aliases or a start: nothing added.
        val bare = "---\ntitle: \"{{title}}\"\ngame_template: souls-like\n---\n# {{title}}\n"
        assertEquals(
            "---\ntitle: \"GLASS TOWER\"\ngame_template: other\ngame_match: exact\ngame_state: backlog\n---\n# GLASS TOWER\n",
            GamePageCreation.render(plan.copy(aliases = emptyList()), bare, started = null, today = today).content
        )
        assertEquals("99_System/Templates/T_Game_Other.md", GamePageCreation.TEMPLATE_PATH)
    }

    @Test
    fun `without a template the same frontmatter is built`() {
        val made = GamePageCreation.render(plan, template = null, started = LocalDate.of(2026, 3, 1), today = today)
        assertEquals(
            "---\n" +
                "title: \"GLASS TOWER\"\n" +
                "date_created: '2026-11-27'\n" +
                "date_updated: '2026-11-27'\n" +
                "type: area\n" +
                "status: 🟢 done\n" +
                "tags: []\n" +
                "aliases:\n" +
                "  - \"GLASS TOWER™\"\n" +
                "  - \"Glass \\\"Tower\\\"\"\n" +
                "game_template: other\n" +
                "game_state: backlog\n" +
                "game_match: exact\n" +
                "started: '2026-03-01'\n" +
                "played_before: ''\n" +
                "game_maps: []\n" +
                "---\n" +
                "\n" +
                "# GLASS TOWER\n",
            made.content
        )
        val page = GamePages.parse(plan.path, made.content)!!
        assertEquals(GameTemplate.OTHER, page.template)
        assertEquals(GameState.BACKLOG, page.state)
        assertTrue(page.exactName)
        assertNull(page.playedBefore)
        assertTrue(page.maps.isEmpty())
        // Without a start date or aliases, and a blank template read as none.
        val plain = GamePageCreation.render(plan.copy(aliases = emptyList()), template = "  \n", started = null, today = today).content
        assertFalse(plain.contains("started:"))
        assertTrue(plain.contains("\naliases: []\n"))
        assertTrue(plain.endsWith("---\n\n# GLASS TOWER\n"))
    }

    // --- 13.4 ------------------------------------------------------------------------------

    private val sequelPage = page("30_Areas/Games/Glass_Tower_2/Glass_Tower_2.md", "Glass Tower 2")

    @Test
    fun `a lookalike is held back and doesn't count toward the limit`() {
        val candidates = listOf(
            PageCandidate("1", "Glass Tower", lastPlayed = evening),
            PageCandidate("2", "Tin Orchard", lastPlayed = evening.minusSeconds(3_600)),
            PageCandidate("3", "Lantern Bay", lastPlayed = evening.minusSeconds(7_200))
        )
        val pages = library + sequelPage
        // "Glass Tower" is the first words of "Glass Tower 2": the owner says in the fold whether it is that game.
        assertEquals(listOf("Tin Orchard", "Lantern Bay"), GamePageCreation.plan(candidates, pages, emptyList(), GameConsole.XBOX, limit = 2).map { it.title })
        // Without the hold it is planned like any other.
        assertEquals(
            listOf("Glass Tower", "Tin Orchard"),
            GamePageCreation.plan(candidates, pages, emptyList(), GameConsole.XBOX, limit = 2, holdLookalikes = false).map { it.title }
        )
    }

    @Test
    fun `planOne creates a held-back title's page`() {
        val pages = library + sequelPage
        val plan = GamePageCreation.planOne(PageCandidate("1915865690", "Glass Tower™"), pages, emptyList(), GameConsole.XBOX)!!
        assertEquals("30_Areas/Games/Glass_Tower/Glass_Tower.md", plan.path)
        assertEquals("Glass Tower", plan.title)
        assertEquals(listOf("Glass Tower™"), plan.aliases)
        assertEquals(listOf("1915865690"), plan.titleIds)
        assertEquals(GameConsole.XBOX, plan.console)
        // A title a page answers still gets none, nor one whose path is taken.
        assertNull(GamePageCreation.planOne(PageCandidate("2", "Ring of Ash"), pages, emptyList(), GameConsole.XBOX))
        assertNull(GamePageCreation.planOne(PageCandidate("3", "Glass Tower"), pages, listOf("30_Areas/Games/glass_tower/glass_tower.md"), GameConsole.XBOX))
        assertNull(GamePageCreation.planOne(PageCandidate("4", "Glass Tower"), emptyList(), emptyList(), GameConsole.XBOX))
    }

    @Test
    fun `recheck drops plans linked, hidden, answered or taken with another case meanwhile`() {
        fun planned(id: String, title: String) =
            GamePageCreation.render(GamePageCreation.planOne(PageCandidate(id, title), library, emptyList(), GameConsole.XBOX)!!, null, null, today)
        val tower = planned("1", "Glass Tower")
        val orchard = planned("2", "Tin Orchard")
        val bay = planned("3", "Lantern Bay")
        val coral = planned("4", "Coral Reef")
        val grey = planned("5", "Grey Reef")
        val all = listOf(tower, orchard, bay, coral, grey)
        // Nothing happened during the sync: every plan stays.
        assertEquals(all, GamePageCreation.recheck(all, GameLinks(), GameConsole.XBOX, library, emptyList()))
        // Glass Tower linked and Tin Orchard hidden from the fold, a Lantern Bay page made by the other
        // console's sync, and Coral Reef's folder there with another case.
        val links = GameLinks().linkConsole(GameConsole.XBOX, "1", "elsewhere.md").hideConsole(GameConsole.XBOX, "2")
        val pagesNow = library + page("30_Areas/Games/Lantern_Bay/Lantern_Bay.md", "Lantern Bay")
        val taken = listOf("30_Areas/Games/CORAL_REEF/notes.md", "30_Areas/Games/Lantern_Bay/Lantern_Bay.md")
        assertEquals(listOf(grey), GamePageCreation.recheck(all, links, GameConsole.XBOX, pagesNow, taken))
        // The same id on PlayStation's side is another title.
        assertEquals(all, GamePageCreation.recheck(all, GameLinks().link("1", "elsewhere.md"), GameConsole.XBOX, library, emptyList()))
        // The path itself taken with another case.
        assertEquals(
            listOf(tower, orchard, bay, grey),
            GamePageCreation.recheck(all, GameLinks(), GameConsole.XBOX, library, listOf("30_areas/games/coral_reef/coral_reef.md"))
        )
        // A page made meanwhile that a title looks like holds it back too.
        val online = page("30_Areas/Games/Glass_Tower_Online/Glass_Tower_Online.md", "Glass Tower Online")
        assertEquals(listOf(orchard, bay, coral, grey), GamePageCreation.recheck(all, GameLinks(), GameConsole.XBOX, library + online, emptyList()))
    }

    @Test
    fun `a key loses invisible characters`() {
        assertEquals("Glass_Tower", GamePageCreation.keyOf("Gla\u200Bss Tower\u202E", '_'))
        assertEquals("Lantern_Bay", GamePageCreation.keyOf("\uFEFFLantern\u00AD Bay\u2066\u2069", '_'))
        assertEquals("Glass Tower", GamePageCreation.pageTitle("Gla\u200Bss\u202E Tower™"))
        assertEquals(
            "30_Areas/Games/Glass_Tower/Glass_Tower.md",
            GamePageCreation.plan(listOf(PageCandidate("1", "Gla\u200Bss Tower")), library, emptyList(), GameConsole.XBOX).single().path
        )
    }
}
