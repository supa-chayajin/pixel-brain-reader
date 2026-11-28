package cloud.wafflecommons.pixelbrainreader.data.games

import cloud.wafflecommons.pixelbrainreader.data.utils.VaultFileIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** 13.4 (P5): the owner's own game picture, its `game_cover` key, its file, and how it resolves. */
class GameCoversTest {

    private fun page(path: String, cover: String? = null) = GamePage(
        path, path.substringAfterLast('/').removeSuffix(".md").replace('_', ' '), emptyList(),
        GameTemplate.OTHER, GameState.BACKLOG, null, null, cover = cover
    )

    private val tower = page("30_Areas/Games/Glass_Tower/Glass_Tower.md")
    private val today = LocalDate.of(2026, 11, 28)

    private val content = "---\ntitle: \"Glass Tower\"\ndate_updated: '2026-11-20'\ngame_template: other\n---\n# Glass Tower\n\nNotes.\n"
    private val withCover =
        "---\ntitle: \"Glass Tower\"\ndate_updated: '2026-11-28'\ngame_template: other\ngame_cover: \"[[Glass_Tower (cover).jpg]]\"\n---\n# Glass Tower\n\nNotes.\n"

    @Test
    fun `the file name is the page's key with (cover)_jpg`() {
        assertEquals("Glass_Tower (cover).jpg", GameCovers.fileName("Glass_Tower"))
        assertEquals("Glass Tower (cover).jpg", GameCovers.fileName("Glass Tower"))
        assertEquals("30_Areas/Games/Glass_Tower/Glass_Tower (cover).jpg", GameCovers.ownPath(tower))
        // A page at the vault's root: the name alone.
        assertEquals("Lantern_Bay (cover).jpg", GameCovers.ownPath(page("Lantern_Bay.md")))
        assertEquals("[[Glass_Tower (cover).jpg]]", GameCovers.link("Glass_Tower (cover).jpg"))
        assertEquals("game_cover", GameCovers.KEY)
        assertEquals(" (cover).jpg", GameCovers.SUFFIX)
        assertEquals(setOf("jpg", "jpeg", "png", "webp"), GameCovers.IMAGE_EXTENSIONS)
        // What the app writes reads back as the page's cover.
        assertEquals("Glass_Tower (cover).jpg", GameCovers.target(GameCovers.link(GameCovers.fileName("Glass_Tower"))))
    }

    @Test
    fun `a name a link can't hold is made safe`() {
        assertEquals("Glass Tower 2 (cover).jpg", GameCovers.fileName("Glass [Tower] #2"))
        assertEquals("Tin Orchard a b (cover).jpg", GameCovers.fileName("Tin:Orchard|a*b?"))
        assertEquals("Lantern Bay (cover).jpg", GameCovers.fileName("  Lantern\u0007Bay^  "))
        assertEquals("a b c (cover).jpg", GameCovers.fileName("a\\b/c"))
        assertEquals("x y (cover).jpg", GameCovers.fileName("x\"<>y"))
        assertEquals("Game (cover).jpg", GameCovers.fileName("[]#^"))
        assertEquals("Game (cover).jpg", GameCovers.fileName(""))
        // Whatever the key, the link the app writes reads back.
        listOf("Glass [Tower] #2", "Tin:Orchard|a*b?", "[]#^", "  Lantern\u0007Bay^  ").forEach { key ->
            assertEquals(GameCovers.fileName(key), GameCovers.target(GameCovers.link(GameCovers.fileName(key))))
        }
    }

    @Test
    fun `a bare name resolves in the page's folder first, then anywhere by name`() {
        val index = VaultFileIndex(
            listOf("30_Areas/Games/Glass_Tower/Glass_Tower (cover).jpg", "Assets/Glass_Tower (cover).jpg", "Assets/art/tower.png")
        )
        val own = tower.copy(cover = "Glass_Tower (cover).jpg")
        assertEquals("30_Areas/Games/Glass_Tower/Glass_Tower (cover).jpg", GameCovers.resolve(own, index))
        // Not in the page's folder: the shallowest file of that name anywhere.
        assertEquals("Assets/art/tower.png", GameCovers.resolve(tower.copy(cover = "tower.png"), index))
        val elsewhere = VaultFileIndex(listOf("Assets/deep/Glass_Tower (cover).jpg", "Assets/Glass_Tower (cover).jpg"))
        assertEquals("Assets/Glass_Tower (cover).jpg", GameCovers.resolve(own, elsewhere))
        // Names compare ignoring case, as on the desktops.
        assertEquals(
            "30_Areas/Games/Glass_Tower/Glass_Tower (cover).jpg",
            GameCovers.resolve(tower.copy(cover = "glass_tower (COVER).JPG"), index)
        )
        assertNull(GameCovers.resolve(tower.copy(cover = "missing.jpg"), index))
        assertNull(GameCovers.resolve(tower, index))
    }

    @Test
    fun `a vault path resolves by path`() {
        val index = VaultFileIndex(listOf("Assets/covers/tower.jpg", "Other/tower.jpg"))
        assertEquals("Other/tower.jpg", GameCovers.resolve(tower.copy(cover = "Other/tower.jpg"), index))
        assertEquals("Assets/covers/tower.jpg", GameCovers.resolve(tower.copy(cover = "Assets/covers/tower.jpg"), index))
        // A page at the vault's root looks there first.
        val root = page("Glass_Tower.md", cover = "tower.jpg")
        assertEquals("Other/tower.jpg", GameCovers.resolve(root, VaultFileIndex(listOf("Other/tower.jpg"))))
        assertEquals("tower.jpg", GameCovers.resolve(root, VaultFileIndex(listOf("Other/tower.jpg", "tower.jpg"))))
    }

    @Test
    fun `traversal, private, hidden and non-image targets never resolve`() {
        listOf(
            "../outside.jpg", "art/../../outside.jpg", "/root.jpg", "a\\b.jpg", "99_private/me.jpg", "99_Private/People/x.png",
            ".obsidian/icon.png", "art/.hidden.jpg", "notes.md", "secret.md.enc", "tower.gif", "tower", "a//b.jpg", "art/",
            "./", "x\u0000.jpg", "[[x.svg]]", "   "
        ).forEach { assertNull(it, GameCovers.target(it)) }
        assertNull(GameCovers.target(null))
        // A file the index finds is refused too when it is private, hidden or not a picture.
        val cover = tower.copy(cover = "tower.jpg")
        assertNull(GameCovers.resolve(cover, VaultFileIndex(listOf("99_Private/tower.jpg"))))
        assertNull(GameCovers.resolve(cover, VaultFileIndex(listOf(".hidden/tower.jpg"))))
        assertNull(GameCovers.resolve(tower.copy(cover = "tower"), VaultFileIndex(listOf("tower.md"))))
    }

    @Test
    fun `setting writes one frontmatter line and moves date_updated`() {
        val set = GameCovers.withCover(content, "Glass_Tower (cover).jpg", today)
        assertEquals(withCover, set)
        assertEquals("Glass_Tower (cover).jpg", GamePages.parse(tower.path, set)!!.cover)
        // The same picture again: nothing moves, the date included.
        assertEquals(set, GameCovers.withCover(set, "Glass_Tower (cover).jpg", LocalDate.of(2026, 12, 1)))
        // A key the owner typed is pointed at the app's file, in its place.
        val typed = content.replace("game_template: other\n", "game_template: other\ngame_cover: \"[[art/mine.png]]\"\n")
        assertEquals(withCover, GameCovers.withCover(typed, "Glass_Tower (cover).jpg", today))
        // Without date_updated, only the key.
        assertEquals(
            "---\ngame_template: other\ngame_cover: \"[[X (cover).jpg]]\"\n---\n",
            GameCovers.withCover("---\ngame_template: other\n---\n", "X (cover).jpg", today)
        )
    }

    @Test
    fun `removing drops the key only`() {
        val removed = GameCovers.withoutCover(withCover, LocalDate.of(2026, 11, 29))
        assertEquals(content.replace("'2026-11-20'", "'2026-11-29'"), removed)
        // Nothing to remove: the page as it was, the date included.
        assertEquals(removed, GameCovers.withoutCover(removed, LocalDate.of(2026, 12, 1)))
        assertNull(GamePages.parse(tower.path, removed)!!.cover)
    }

    @Test
    fun `namesOwnFile tells the app's file from the owner's`() {
        assertTrue(GameCovers.namesOwnFile(tower, "Glass_Tower (cover).jpg"))
        assertTrue(GameCovers.namesOwnFile(tower, "glass_tower (COVER).jpg"))
        assertTrue(GameCovers.namesOwnFile(tower, "30_Areas/Games/Glass_Tower/Glass_Tower (cover).jpg"))
        assertFalse(GameCovers.namesOwnFile(tower, "art/Glass_Tower (cover).jpg"))
        assertFalse(GameCovers.namesOwnFile(tower, "mine.png"))
        assertFalse(GameCovers.namesOwnFile(page("30_Areas/Games/Tin_Orchard/Tin_Orchard.md"), "Glass_Tower (cover).jpg"))
    }
}
