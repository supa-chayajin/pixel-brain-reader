package cloud.wafflecommons.pixelbrainreader.ui.utils

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.window.core.layout.WindowSizeClass

/** The widest a column of forms, lists, settings or chat grows before it centres itself. */
val ContentMaxWidth: Dp = 840.dp

/** The widest a note's text grows: about 80 characters a line. */
val ReadingMaxWidth: Dp = 720.dp

/**
 * True on a wide window (840 dp and more: a tablet in landscape, an unfolded Fold). There the
 * navigation is a side rail, the Daily screen has two columns and the Projects, Boards and
 * Runbooks hubs show their list and the open page side by side. Phones, the folded Fold and a
 * tablet in portrait stay below it.
 */
@Composable
fun isWideLayout(): Boolean =
    currentWindowAdaptiveInfo().windowSizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_EXPANDED_LOWER_BOUND)

/** True from a medium window on (600 dp and more): any tablet, an unfolded Fold. Not a phone. */
@Composable
fun isMediumOrWider(): Boolean =
    currentWindowAdaptiveInfo().windowSizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND)

/** The narrowest a card of the Chores grid gets on a tablet or an unfolded Fold. */
val CardMinWidth: Dp = 300.dp

/**
 * Fills the width up to [max], centred in what is left. Chained after a `verticalScroll`, the
 * scroll still answers across the whole width while the content keeps a readable measure.
 */
fun Modifier.centeredMaxWidth(max: Dp = ContentMaxWidth): Modifier =
    fillMaxWidth()
        .wrapContentWidth(Alignment.CenterHorizontally)
        .widthIn(max = max)
        .fillMaxWidth()
