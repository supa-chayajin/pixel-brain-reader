package cloud.wafflecommons.pixelbrainreader

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import cloud.wafflecommons.pixelbrainreader.ui.mood.MoodTagIcons
import cloud.wafflecommons.pixelbrainreader.ui.mood.TagIconPickerContent
import cloud.wafflecommons.pixelbrainreader.ui.mood.TagIconPickerTags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The mood-tag icon picker on a device: every icon draws, a tap reports its key, "Default" reports
 * the reset. Draws the picker's body only — no vault, no app state is touched.
 */
@RunWith(AndroidJUnit4::class)
class TagIconPickerDeviceTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun everyIconHasAVector() {
        assertTrue(MoodTagIcons.ALL.all { it.image.defaultWidth > 0.dp && it.image.root.size > 0 })
    }

    @Test
    fun aTapReportsTheIconAndDefaultReportsTheReset() {
        var picked: String? = null
        var resets = 0
        compose.setContent {
            MaterialTheme {
                Box(Modifier.size(width = 420.dp, height = 640.dp)) {
                    TagIconPickerContent(
                        title = "Icon for “Gaming”",
                        selectedKey = "piano",
                        defaultKey = "sports_esports",
                        onPick = { picked = it },
                        onUseDefault = { resets++ }
                    )
                }
            }
        }

        compose.onNodeWithText("Icon for “Gaming”").assertIsDisplayed()
        compose.onNodeWithTag(TagIconPickerTags.ICON_PREFIX + "code").assertIsNotSelected().performClick()
        assertEquals("code", picked)

        // The picked icon shows as selected; Default does not.
        compose.onNodeWithTag(TagIconPickerTags.GRID).performScrollToKey("piano")
        compose.onNodeWithTag(TagIconPickerTags.ICON_PREFIX + "piano").assertIsSelected()
        compose.onNodeWithTag(TagIconPickerTags.DEFAULT_CHIP).assertIsNotSelected().performClick()
        assertEquals(1, resets)

        // The last group is reachable too.
        compose.onNodeWithTag(TagIconPickerTags.GRID).performScrollToKey(MoodTagIcons.DEFAULT_KEY)
        compose.onNodeWithTag(TagIconPickerTags.ICON_PREFIX + MoodTagIcons.DEFAULT_KEY).assertIsDisplayed().performClick()
        assertEquals(MoodTagIcons.DEFAULT_KEY, picked)
    }
}
