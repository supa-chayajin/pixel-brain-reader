package cloud.wafflecommons.pixelbrainreader.ui.theme

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Bottom space every scrollable screen must reserve so its last item clears the floating
 * ExpressiveNavBar (ui/main/MainScreen.kt), which overlays content Finance-style rather than
 * insetting it. The bar occupies ~80dp (64dp surface + 8dp*2 padding) above the system nav inset
 * (which the bar applies itself). 100dp = that footprint on gesture-nav + a small breathing gap;
 * matches the value the Daily screen already used successfully.
 */
val NavBarClearance: Dp = 100.dp
