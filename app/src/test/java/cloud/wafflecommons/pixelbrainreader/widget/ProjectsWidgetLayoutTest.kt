package cloud.wafflecommons.pixelbrainreader.widget

import cloud.wafflecommons.pixelbrainreader.widget.ui.ProjectsWidgetLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectsWidgetLayoutTest {

    @Test
    fun `the smallest placement still shows one compact project`() {
        val layout = ProjectsWidgetLayout.forHeight(90f)
        assertEquals(1, layout.rows)
        assertFalse(layout.withNext)
    }

    @Test
    fun `a short widget keeps about three projects by dropping the next action`() {
        val layout = ProjectsWidgetLayout.forHeight(220f)
        assertEquals(3, layout.rows)
        assertFalse(layout.withNext)
    }

    @Test
    fun `a taller widget shows more projects with their next action`() {
        val three = ProjectsWidgetLayout.forHeight(260f)
        assertEquals(3, three.rows)
        assertTrue(three.withNext)
        assertEquals(6, ProjectsWidgetLayout.forHeight(460f).rows)
    }

    @Test
    fun `a stretched widget stops at the cap`() {
        val layout = ProjectsWidgetLayout.forHeight(800f)
        assertEquals(ProjectsWidgetLayout.MAX_ROWS, layout.rows)
        assertTrue(layout.withNext)
    }

    @Test
    fun `every breakpoint is a new layout and the rows grow with the height`() {
        val points = ProjectsWidgetLayout.breakpointsDp()
        val layouts = points.map { ProjectsWidgetLayout.forHeight(it) }
        assertEquals(layouts.size, layouts.distinct().size)
        // Just under each breakpoint the layout is the previous one: nothing changes in between.
        points.drop(1).zip(layouts).forEach { (h, before) ->
            assertEquals(before, ProjectsWidgetLayout.forHeight(h - 0.5f))
        }
        val rows = layouts.map { it.rows }
        assertEquals(rows.sorted(), rows)
        assertEquals(ProjectsWidgetLayout.MAX_ROWS, rows.last())
        // The tallest declared size fits the widget's maxResizeHeight (800 dp).
        assertTrue(points.last() <= 800f)
    }
}
