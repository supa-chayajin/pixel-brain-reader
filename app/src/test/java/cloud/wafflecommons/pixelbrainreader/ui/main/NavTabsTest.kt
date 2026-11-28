package cloud.wafflecommons.pixelbrainreader.ui.main

import cloud.wafflecommons.pixelbrainreader.data.modes.ModeTabs
import cloud.wafflecommons.pixelbrainreader.data.repository.UserPreferencesRepository
import cloud.wafflecommons.pixelbrainreader.ui.feeds.FeedsRoutes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The bar's tabs (13.4): one list names and draws them for the bar, the rail and Settings › Navigation. */
class NavTabsTest {

    @Test
    fun `the tabs' keys are the bar's default order`() {
        assertEquals(UserPreferencesRepository.DEFAULT_NAVBAR_ORDER, NavTabs.ALL.map { it.key })
        // The Feeds tab's key is persisted: never renamed, and the last one.
        assertEquals(FeedsRoutes.FEEDS, NavTabs.ALL.last().key)
        assertEquals("feeds", FeedsRoutes.FEEDS)
    }

    @Test
    fun `every tab has a name and both icons`() {
        NavTabs.ALL.forEach { tab ->
            assertTrue("${tab.key} has no name", tab.label.isNotBlank())
            assertTrue("${tab.key} has no open icon", tab.icon.name.isNotBlank())
            assertTrue("${tab.key} has no outlined icon", tab.iconOff.name.isNotBlank())
            assertEquals(tab, NavTabs.of(tab.key))
        }
        // Each tab its own name, and an unknown key no tab.
        assertEquals(NavTabs.ALL.size, NavTabs.ALL.map { it.label }.toSet().size)
        assertNull(NavTabs.of("gone"))
        assertEquals("Feeds", NavTabs.of(FeedsRoutes.FEEDS)?.label)
        assertEquals("Repo", NavTabs.label(Screen.Home, "Workbench"))
    }

    @Test
    fun `the toolkit chip is named after the toolkit`() {
        assertEquals("Workbench (Daily's chip)", NavTabs.label(ModeTabs.TOOLKIT, "Workbench"))
        // Every key a mode can hide is a tab or the toolkit chip: each has a name in Settings.
        ModeTabs.KEYS.filterNot { it == ModeTabs.TOOLKIT }.forEach { key -> assertNotNull(key, NavTabs.of(key)) }
        assertEquals("gone", NavTabs.label("gone", "Workbench"))
    }
}
