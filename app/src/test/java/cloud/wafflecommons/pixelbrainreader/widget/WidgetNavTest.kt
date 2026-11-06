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
}
