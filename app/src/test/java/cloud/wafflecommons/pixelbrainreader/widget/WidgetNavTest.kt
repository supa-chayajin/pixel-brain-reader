package cloud.wafflecommons.pixelbrainreader.widget

import cloud.wafflecommons.pixelbrainreader.ui.main.V11Routes
import cloud.wafflecommons.pixelbrainreader.widget.ui.WidgetNav
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WidgetNavTest {

    @Test
    fun `the projects screen key is whitelisted, unknown keys are not`() {
        assertEquals(V11Routes.PROJECTS, WidgetNav.screenToRoute(WidgetNav.SCREEN_PROJECTS))
        // The Projects widget's hand-off action opens the composer with no project chosen yet.
        assertEquals("handoff", WidgetNav.screenToRoute(WidgetNav.SCREEN_HANDOFF))
        assertEquals(V11Routes.PROJECTS, WidgetNav.screenToRoute(" Projects "))
        assertNull(WidgetNav.screenToRoute("project?root=20_Projects/x"))
        assertNull(WidgetNav.screenToRoute(null))
    }

    @Test
    fun `a meeting's prep opens only on a timeline entry's id`() {
        val id = "3f0c1d2e-4b5a-4c6d-8e7f-901234567890"
        assertEquals("meeting_prep?entry=$id", WidgetNav.screenToRoute(WidgetNav.SCREEN_MEETING_PREP, id))
        assertNull(WidgetNav.screenToRoute(WidgetNav.SCREEN_MEETING_PREP, null))
        assertNull(WidgetNav.screenToRoute(WidgetNav.SCREEN_MEETING_PREP, "settings"))
        assertNull(WidgetNav.screenToRoute(WidgetNav.SCREEN_MEETING_PREP, "$id&root=../x"))
        assertNull(WidgetNav.screenToRoute(WidgetNav.SCREEN_MEETING_PREP, "3f0c1d2e-4b5a-4c6d-8e7f-90123456789")) // too short
    }

    @Test
    fun `a review notification opens a fixed route, never a path`() {
        assertEquals("review_note/weekly", WidgetNav.screenToRoute(WidgetNav.SCREEN_WEEKLY_REVIEW))
        assertEquals("review_note/monthly", WidgetNav.screenToRoute(WidgetNav.SCREEN_MONTHLY_REPORT))
        // A parameter is ignored: the app finds the note itself.
        assertEquals("review_note/weekly", WidgetNav.screenToRoute(WidgetNav.SCREEN_WEEKLY_REVIEW, "3f0c1d2e-4b5a-4c6d-8e7f-901234567890"))
        assertNull(WidgetNav.screenToRoute("review_note/../settings"))
        assertNull(WidgetNav.screenToRoute("review_note/weekly"))
    }
}
