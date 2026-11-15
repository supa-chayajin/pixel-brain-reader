package cloud.wafflecommons.pixelbrainreader.data.work

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BoardFlowTest {

    @Test
    fun `a swipe walks the flow one column at a time, never into the archive`() {
        assertEquals(StatusColumn.TODO, BoardFlow.next(null))
        assertEquals(StatusColumn.WIP, BoardFlow.next(StatusColumn.TODO))
        assertEquals(StatusColumn.DONE, BoardFlow.next(StatusColumn.PAUSED))
        assertNull(BoardFlow.next(StatusColumn.DONE))
        assertNull(BoardFlow.next(StatusColumn.ARCHIVE))
        assertEquals(StatusColumn.TODO, BoardFlow.previous(StatusColumn.WIP))
        assertNull(BoardFlow.previous(StatusColumn.TODO))
        assertNull(BoardFlow.previous(null))
        assertNull(BoardFlow.previous(StatusColumn.ARCHIVE))
    }

    @Test
    fun `a limit is set per board and column, and zero removes it`() {
        var config = BoardsConfig(boards = listOf("20_Projects/A"))
        config = BoardFlow.withLimit(config, "20_Projects/A", StatusColumn.WIP, 3)
        assertEquals(3, BoardFlow.limitOf(config, "20_Projects/A", StatusColumn.WIP))
        assertNull(BoardFlow.limitOf(config, "20_Projects/B", StatusColumn.WIP))
        assertTrue(BoardFlow.over(config, "20_Projects/A", StatusColumn.WIP, 4))
        assertFalse(BoardFlow.over(config, "20_Projects/A", StatusColumn.WIP, 3))
        assertFalse(BoardFlow.over(config, "20_Projects/A", StatusColumn.TODO, 40))
        config = BoardFlow.withLimit(config, "20_Projects/A", StatusColumn.WIP, 0)
        assertTrue(config.wip.isEmpty())
    }

    @Test
    fun `boards json keeps the limits and still reads an older file`() {
        val json = Json { ignoreUnknownKeys = true }
        val config = BoardFlow.withLimit(BoardsConfig(boards = listOf("A")), "A", StatusColumn.WIP, 2)
        val text = json.encodeToString(BoardsConfig.serializer(), config)
        assertEquals(config, json.decodeFromString(BoardsConfig.serializer(), text))
        assertEquals(BoardsConfig(boards = listOf("A")), json.decodeFromString(BoardsConfig.serializer(), """{"boards":["A"]}"""))
    }
}
