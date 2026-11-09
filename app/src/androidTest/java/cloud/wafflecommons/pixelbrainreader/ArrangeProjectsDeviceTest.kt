package cloud.wafflecommons.pixelbrainreader

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import cloud.wafflecommons.pixelbrainreader.data.work.ProjectArrangement
import cloud.wafflecommons.pixelbrainreader.data.work.ProjectSummary
import cloud.wafflecommons.pixelbrainreader.ui.work.ArrangeProjectsContent
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Projects page's arrange sheet on a device: each button reports its project. Draws the
 * sheet's body only, with made-up projects — no vault, no app state is touched.
 */
@RunWith(AndroidJUnit4::class)
class ArrangeProjectsDeviceTest {

    @get:Rule
    val compose = createComposeRule()

    private fun project(name: String) = ProjectSummary(
        root = "20_Projects/$name", title = name, status = null, lastActivity = null, days = emptyList(),
        actions = emptyList(), claudeQueue = emptyList(), keyNotes = emptyList(), recentNotes = emptyList(),
        linkedFrom = emptyList(), todoNote = null
    )

    @Test
    fun everyButtonReportsItsProject() {
        val projects = listOf("21_Garden_Planner", "22_Recipe_Box", "23_Atlas", "24_Kiln").map(::project)
        val arranged = ProjectArrangement(
            pinned = listOf("20_Projects/23_Atlas", "20_Projects/21_Garden_Planner"),
            hidden = listOf("20_Projects/24_Kiln")
        ).arrange(projects)
        val calls = mutableListOf<String>()
        compose.setContent {
            MaterialTheme {
                Box(Modifier.size(width = 480.dp, height = 900.dp)) {
                    ArrangeProjectsContent(
                        arranged = arranged,
                        onPin = { calls += "pin $it" },
                        onUnpin = { calls += "unpin $it" },
                        onHide = { calls += "hide $it" },
                        onShow = { calls += "show $it" },
                        onSwapPinned = { a, b -> calls += "swap $a $b" }
                    )
                }
            }
        }

        // The first pinned can't go up; the last can't go down.
        compose.onNodeWithContentDescription("Move Atlas up").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Move Garden Planner down").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Move Garden Planner up").performClick()
        compose.onNodeWithContentDescription("Unpin Atlas").performClick()
        compose.onNodeWithContentDescription("Pin Recipe Box").performClick()
        compose.onNodeWithContentDescription("Hide Recipe Box").performClick()
        compose.onNodeWithContentDescription("Show Kiln").assertIsDisplayed().performClick()

        assertEquals(
            listOf(
                "swap 20_Projects/21_Garden_Planner 20_Projects/23_Atlas",
                "unpin 20_Projects/23_Atlas",
                "pin 20_Projects/22_Recipe_Box",
                "hide 20_Projects/22_Recipe_Box",
                "show 20_Projects/24_Kiln"
            ),
            calls
        )
    }
}
