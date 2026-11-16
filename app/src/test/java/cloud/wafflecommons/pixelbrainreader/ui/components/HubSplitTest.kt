package cloud.wafflecommons.pixelbrainreader.ui.components

import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.layout.PaneExpansionAnchor
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

/** The stops of a hub's divider on a wide window (13.2.1). */
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
class HubSplitTest {

    @Test
    fun `a wide hub stops at the list hidden, the list's width and half and half`() {
        // A tablet in landscape beside the rail.
        val anchors = HubSplit.anchors(width = 1204.dp, listWidth = 360.dp)
        assertEquals(listOf(HubSplit.HIDDEN, HubSplit.list(360.dp), PaneExpansionAnchor.Proportion(0.5f)), anchors)
    }

    @Test
    fun `half and half isn't a stop when it would sit by the list's width`() {
        // The unfolded Fold beside the rail: half is 388 dp, next to the list's 360.
        val anchors = HubSplit.anchors(width = 776.dp, listWidth = 360.dp)
        assertEquals(listOf(HubSplit.HIDDEN, HubSplit.list(360.dp)), anchors)
    }

    @Test
    fun `hidden means the list has no width`() {
        assertEquals(PaneExpansionAnchor.Proportion(0f), HubSplit.HIDDEN)
    }
}
