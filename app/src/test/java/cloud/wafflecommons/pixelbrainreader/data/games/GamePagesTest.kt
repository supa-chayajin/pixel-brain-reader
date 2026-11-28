package cloud.wafflecommons.pixelbrainreader.data.games

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class GamePagesTest {

    private val page = """
        ---
        title: "A Souls-like"
        date_created: '2026-11-14'
        date_updated: '2026-11-14'
        type: area
        status: active
        tags:
          - games
        aliases:
          - Souls DLC
          - ASL
        game_template: souls-like
        game_state: playing
        started: '2026-01-20'
        ---
        # A Souls-like

        Notes.
    """.trimIndent() + "\n"

    @Test
    fun `a page with game_template is a game page with its keys`() {
        val game = GamePages.parse("30_Areas/Games/A_Souls_Like/A_Souls_Like.md", page)!!
        assertEquals("A Souls-like", game.title)
        assertEquals(listOf("Souls DLC", "ASL"), game.aliases)
        assertEquals(GameTemplate.SOULS_LIKE, game.template)
        assertEquals(GameState.PLAYING, game.state)
        assertEquals(LocalDate.of(2026, 1, 20), game.started)
        assertNull(game.finished)
        assertEquals("A_Souls_Like", game.key)
        assertEquals("30_Areas/Games/A_Souls_Like", game.folder)
        assertEquals(listOf("A Souls-like", "Souls DLC", "ASL"), game.names)
    }

    @Test
    fun `a note without game_template is not a game page`() {
        assertNull(GamePages.parse("x.md", page.replace("game_template: souls-like\n", "")))
        assertNull(GamePages.parse("x.txt", page))
    }

    @Test
    fun `unknown or missing values fall back to Other and Backlog`() {
        val odd = page.replace("souls-like", "metroidvania").replace("game_state: playing\n", "")
        val game = GamePages.parse("g.md", odd)!!
        assertEquals(GameTemplate.OTHER, game.template)
        assertEquals(GameState.BACKLOG, game.state)
        assertEquals(GameState.HUNDRED, GameState.fromKey("100%"))
        assertEquals(GameState.HUNDRED, GameState.fromKey("'100'".trim('\'')))
    }

    @Test
    fun `the title falls back to the heading then the file name`() {
        val noTitle = page.replace("title: \"A Souls-like\"\n", "")
        assertEquals("A Souls-like", GamePages.parse("g.md", noTitle)!!.title)
        val bare = "---\ngame_template: lego\n---\nNo heading.\n"
        assertEquals("Brick Saga", GamePages.parse("x/Brick_Saga.md", bare)!!.title)
    }

    @Test
    fun `setting the state changes that line and date_updated only, and dates a finish`() {
        val done = GamePages.withState(page, GameState.DONE, LocalDate.of(2026, 11, 20))
        assertTrue(done.contains("game_state: done\n"))
        assertTrue(done.contains("date_updated: '2026-11-20'\n"))
        assertTrue(done.contains("finished: '2026-11-20'\n"))
        assertTrue(done.contains("started: '2026-01-20'\n"))
        // Every other line as written.
        val untouched = page.lines().filterNot { it.startsWith("game_state") || it.startsWith("date_updated") }
        assertTrue(untouched.all { it in done.lines() })
        assertEquals(GameState.HUNDRED, GamePages.parse("g.md", GamePages.withState(page, GameState.HUNDRED, LocalDate.of(2026, 11, 20)))!!.state)
    }

    @Test
    fun `starting a backlog game dates its start once`() {
        val backlog = page.replace("game_state: playing\n", "game_state: backlog\n").replace("started: '2026-01-20'\n", "")
        val playing = GamePages.withState(backlog, GameState.PLAYING, LocalDate.of(2026, 12, 24))
        assertTrue(playing.contains("started: '2026-12-24'"))
        assertEquals(playing, GamePages.withState(playing, GameState.PLAYING, LocalDate.of(2026, 12, 24)))
    }

    @Test
    fun `the tab groups paused games first in Backlog`() {
        fun g(name: String, state: GameState) = GamePage("$name.md", name, emptyList(), GameTemplate.OTHER, state, null, null)
        val groups = GamePages.grouped(listOf(g("Zeta", GameState.BACKLOG), g("Alpha", GameState.BACKLOG), g("Mid", GameState.PAUSED), g("Won", GameState.HUNDRED), g("Now", GameState.PLAYING)))
        assertEquals(listOf("Mid", "Alpha", "Zeta"), groups.getValue(GameGroup.BACKLOG).map { it.title })
        assertEquals(listOf("Won"), groups.getValue(GameGroup.DONE).map { it.title })
        assertEquals(listOf("Now"), groups.getValue(GameGroup.PLAYING).map { it.title })
        assertEquals(emptyList<GamePage>(), groups.getValue(GameGroup.NEXT))
    }

    @Test
    fun `the page's prose leaves out what the cards show`() {
        val content = """
            ---
            game_template: souls-like
            tags:
              - [ ] not a checklist
            ---
            # Ring of Ash

            My notes on this run: [[Build_Rakshasa]].

            ## Bosses
            - [ ] The first knight <!--trophy:NPWR00001_00#3-->
            - [x] The gatekeeper

            ## Builds
            ### Bleed
            Katana and frost.

            ## 🏆 Trophies

            *1 of 2 trophies · 50 %*

            - 🥉 First Steps — 2026-11-02
        """.trimIndent() + "\n"
        assertEquals(
            "My notes on this run: [[Build_Rakshasa]].\n\n## Builds\n### Bleed\nKatana and frost.",
            GamePages.prose(content)
        )
        assertEquals("", GamePages.prose("---\ngame_template: lego\n---\n# Brick Wars\n\n## Episodes\n- [ ] Episode I\n"))
    }

    @Test
    fun `the 13_1 templates read their keys and the vault's other is Other`() {
        fun template(key: String) = GamePages.parse("30_Areas/G/G.md", "---\ngame_template: $key\n---\n# G\n")!!.template
        assertEquals(GameTemplate.PLATFORMER, template("platformer"))
        assertEquals("Platformer", GameTemplate.PLATFORMER.label)
        assertEquals(GameTemplate.ACTION_ADVENTURE, template("action-adventure"))
        assertEquals("Action-adventure", GameTemplate.ACTION_ADVENTURE.label)
        assertEquals(GameTemplate.OTHER, template("other"))
        assertEquals(GameTemplate.OTHER, template("fighting"))
    }

    @Test
    fun `a template in 99_System is never a game, wherever else the same note would be one`() {
        val template = "---\ntitle: \"{{title}}\"\ngame_template: souls-like\ngame_state: backlog\n---\n# 🎮 {{title}}\n"
        assertNull(GamePages.parse("99_System/Templates/T_Game_Souls_Like.md", template))
        assertEquals(GameTemplate.SOULS_LIKE, GamePages.parse("30_Areas/33_Gaming_Hub/X/X.md", template)!!.template)
    }

    @Test
    fun `played_before reads the hours as the owner types them`() {
        assertEquals(228.0, GamePages.hours("228")!!, 0.0)
        assertEquals(228.5, GamePages.hours("228.5")!!, 0.0)
        assertEquals(228.5, GamePages.hours("228,5")!!, 0.0)
        assertEquals(228.0, GamePages.hours("228 h")!!, 0.0)
        assertEquals(228.0, GamePages.hours("228H")!!, 0.0)
        assertEquals(228.5, GamePages.hours("228h30")!!, 0.0)
        assertEquals(228.25, GamePages.hours("228:15")!!, 0.0)
        assertEquals(0.0, GamePages.hours("0")!!, 0.0)
        assertNull(GamePages.hours(""))
        assertNull(GamePages.hours(null))
        assertNull(GamePages.hours("-3"))
        assertNull(GamePages.hours("228:75"))
        assertNull(GamePages.hours("about 200"))
        val game = GamePages.parse("30_Areas/G/G.md", "---\ngame_template: lego\nplayed_before: '41h30'\n---\n# G\n")!!
        assertEquals(41.5, game.playedBefore!!, 0.0)
        assertNull(GamePages.parse("30_Areas/G/G.md", "---\ngame_template: lego\nplayed_before: ''\n---\n")!!.playedBefore)
    }

    @Test
    fun `played_before is one line, dated, and the rest of the page stays as it was`() {
        val today = LocalDate.of(2026, 11, 16)
        val written = GamePages.withPlayedBefore(page, 228.0, today)
        assertTrue(written.contains("\nplayed_before: 228\n---\n"))
        assertTrue(written.contains("date_updated: '2026-11-16'"))
        assertEquals(page.replace("date_updated: '2026-11-14'", "date_updated: '2026-11-16'"), written.replace("played_before: 228\n", ""))
        val decimal = GamePages.withPlayedBefore(written, 41.5, today)
        assertTrue(decimal.contains("\nplayed_before: 41.5\n"))
        assertTrue(GamePages.withPlayedBefore(decimal, null, today).contains("\nplayed_before: ''\n"))
        // Nothing to change: nothing moves, date included.
        assertEquals(written, GamePages.withPlayedBefore(written, 228.0, LocalDate.of(2026, 12, 1)))
    }

    // --- 13.3 ------------------------------------------------------------------------------

    @Test
    fun `prose leaves out the achievements section too`() {
        val content = "---\ngame_template: souls-like\n---\n# Ring of Ash\n\nMy notes.\n\n" +
            "## 🏆 Trophies\n\n*1 of 2 trophies · 50 %*\n\n" +
            "${AchievementSection.HEADING}\n\n*1 of 2 achievements · 10 of 60 G · 50 %*\n\n" +
            "- ${AchievementSection.ICON} First Steps — 2026-11-02 · 10 G\n\n" +
            "## Builds\n\nKatana.\n"
        assertEquals("My notes.\n\n## Builds\n\nKatana.", GamePages.prose(content))
        // Its heading without the variation selector too.
        assertEquals(
            "My notes.",
            GamePages.prose("---\ngame_template: lego\n---\n# G\n\nMy notes.\n\n## 🎖 Achievements\n\n*0 of 1 achievements · 0 of 10 G · 0 %*\n")
        )
    }

    @Test
    fun `game_match exact marks a page for exact names`() {
        assertTrue(GamePages.parse("g.md", "---\ngame_template: other\ngame_match: exact\n---\n")!!.exactName)
        assertTrue(GamePages.parse("g.md", "---\ngame_template: other\ngame_match: 'Exact'\n---\n")!!.exactName)
        assertFalse(GamePages.parse("g.md", "---\ngame_template: other\n---\n")!!.exactName)
        assertFalse(GamePages.parse("g.md", "---\ngame_template: other\ngame_match: prefix\n---\n")!!.exactName)
        assertEquals("game_match", GamePages.MATCH_KEY)
        assertEquals("exact", GamePages.MATCH_EXACT)
    }

    // --- 13.4 ------------------------------------------------------------------------------

    @Test
    fun `game_cover reads a wikilink, an embed, a plain path, and refuses the rest`() {
        fun cover(value: String) =
            GamePages.parse("30_Areas/Games/Glass_Tower/Glass_Tower.md", "---\ngame_template: other\ngame_cover: $value\n---\n# Glass Tower\n")!!.cover
        assertEquals("Glass_Tower (cover).jpg", cover("\"[[Glass_Tower (cover).jpg]]\""))
        assertEquals("art/tower.png", cover("\"![[art/tower.png|200]]\""))
        assertEquals("Shots/tower.jpeg", cover("\"[[Shots/tower.jpeg#top]]\""))
        assertEquals("tower.webp", cover("'./tower.webp'"))
        assertEquals("tower.JPG", cover("tower.JPG"))
        // Never outside the vault, private, hidden, or anything but a picture.
        assertNull(cover("\"[[../outside.jpg]]\""))
        assertNull(cover("\"/absolute.jpg\""))
        assertNull(cover("\"99_Private/me.jpg\""))
        assertNull(cover("\"[[.hidden/x.jpg]]\""))
        assertNull(cover("\"[[notes.md]]\""))
        assertNull(cover("\"[[a//b.jpg]]\""))
        assertNull(cover("\"tower.gif\""))
        assertNull(cover("''"))
        assertNull(GamePages.parse("g.md", "---\ngame_template: other\n---\n")!!.cover)
    }

    @Test
    fun `prose keeps an owner's line inside a derived section`() {
        val content = "---\ngame_template: other\n---\n# Glass Tower\n\nMy notes.\n\n" +
            "## 🏆 Trophies\n\n*1 of 2 trophies · 50 %*\n\n- 🥉 First Floor — 2026-11-02\nThe owner's line.\n\n" +
            "### Later\n\nMore text.\n\n" +
            "${AchievementSection.HEADING}\n\n*1 of 1 achievements · 10 of 10 G · 100 %*\n\n- ${AchievementSection.ICON} Ground Floor — 2026-11-02 · 10 G\n" +
            "- [ ] Reach the roof <!--achievement:1915865690#2-->\n"
        assertEquals("My notes.\n\nThe owner's line.\n### Later\n\nMore text.", GamePages.prose(content))
        // An achievement's shape under the trophies isn't the trophies' own: it shows, as the sync keeps it.
        val mixed = "---\ngame_template: other\n---\n# Glass Tower\n\n## 🏆 Trophies\n\n- ${AchievementSection.ICON} Ground Floor — 2026-11-02 · 10 G\n"
        assertEquals("- ${AchievementSection.ICON} Ground Floor — 2026-11-02 · 10 G", GamePages.prose(mixed))
    }

    @Test
    fun `an earlier start fills a blank started or the day creation wrote, never an owner's date`() {
        val created = "---\ntitle: \"Glass Tower\"\ndate_updated: '2026-11-27'\ngame_template: other\ngame_match: exact\nstarted: '2026-11-27'\n---\n# Glass Tower\n"
        val unlock = LocalDate.of(2025, 3, 1)
        val lastPlayed = LocalDate.of(2026, 11, 27)
        val moved = GamePages.withEarlierStart(created, unlock, sameAs = lastPlayed)
        // The start moved, and nothing else: date_updated stays, the owner changed nothing.
        assertEquals(created.replace("started: '2026-11-27'", "started: '2025-03-01'"), moved)
        // Once moved, it stays, whatever a later file says.
        assertEquals(moved, GamePages.withEarlierStart(moved, LocalDate.of(2025, 2, 1), sameAs = lastPlayed))
        // A blank or missing start is filled.
        val blank = created.replace("started: '2026-11-27'", "started: ''")
        assertEquals(moved, GamePages.withEarlierStart(blank, unlock, sameAs = lastPlayed))
        val missing = created.replace("started: '2026-11-27'\n", "")
        assertTrue(GamePages.withEarlierStart(missing, unlock, sameAs = null).contains("\nstarted: '2025-03-01'\n---\n"))
        // An owner's date stays, earlier or later than the unlock.
        val owners = created.replace("started: '2026-11-27'", "started: '2026-11-20'")
        assertEquals(owners, GamePages.withEarlierStart(owners, unlock, sameAs = lastPlayed))
        val earlier = created.replace("started: '2026-11-27'", "started: '2024-01-05'")
        assertEquals(earlier, GamePages.withEarlierStart(earlier, unlock, sameAs = lastPlayed))
        // A start that isn't earlier changes nothing.
        assertEquals(created, GamePages.withEarlierStart(created, LocalDate.of(2026, 11, 28), sameAs = lastPlayed))
        assertEquals(created, GamePages.withEarlierStart(created, unlock, sameAs = null))
    }
}
