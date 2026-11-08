package cloud.wafflecommons.pixelbrainreader.ui.theme

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * True while MainScreen shows the side navigation rail (a wide window) instead of the floating
 * bottom bar: then no bar overlays the bottom of the screens.
 */
val LocalNavRail = staticCompositionLocalOf { false }

/**
 * Bottom space every scrollable screen must reserve so its last item clears the floating
 * ExpressiveNavBar (ui/main/MainScreen.kt), which overlays content Finance-style rather than
 * insetting it. The bar occupies ~80dp (64dp surface + 8dp*2 padding) above the system nav inset
 * (which the bar applies itself). 100dp = that footprint on gesture-nav + a small breathing gap;
 * matches the value the Daily screen already used successfully. Beside the side rail (a wide
 * window) only the system navigation bar is left to clear.
 */
val NavBarClearance: Dp
    @Composable get() = if (LocalNavRail.current) railBottomClearance() else 100.dp

/** The bottom gap beside the side rail: the system navigation bar and a small breathing gap. */
@Composable
fun railBottomClearance(): Dp = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 16.dp
