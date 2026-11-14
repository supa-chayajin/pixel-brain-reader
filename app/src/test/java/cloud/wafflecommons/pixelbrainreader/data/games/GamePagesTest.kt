package cloud.wafflecommons.pixelbrainreader.data.games

import org.junit.Assert.assertEquals
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
}
