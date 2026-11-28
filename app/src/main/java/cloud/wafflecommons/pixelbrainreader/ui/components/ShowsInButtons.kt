@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package cloud.wafflecommons.pixelbrainreader.ui.components

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import cloud.wafflecommons.pixelbrainreader.data.modes.ContextMode
import cloud.wafflecommons.pixelbrainreader.data.modes.ModeAppearance
import cloud.wafflecommons.pixelbrainreader.ui.modes.ModeIcons

/**
 * "Shows in" (13.4, F4 and S3): a connected group, one toggle per mode, each named and drawn as the
 * owner calls that mode ([appearance], `ModeIcons`): never a hard-coded mode name. Several can be on.
 * [keepOne]: the last mode on stays on (a feed shows in at least one mode); without it every mode
 * can go (a tab hidden everywhere).
 */
@Composable
fun ShowsInButtons(
    shown: Set<ContextMode>,
    appearance: ModeAppearance,
    onChange: (Set<ContextMode>) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    keepOne: Boolean = true
) {
    val modes = ContextMode.entries
    ConnectedButtonRow(modifier = modifier.semantics { contentDescription = SHOWS_IN }) {
        modes.forEachIndexed { index, mode ->
            ToggleButton(
                checked = mode in shown,
                onCheckedChange = {
                    val next = ShowsIn.toggled(shown, mode, keepOne)
                    if (next != shown) onChange(next)
                },
                enabled = enabled,
                shapes = connectedShapes(index, modes.size),
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    ModeIcons.of(appearance, mode),
                    contentDescription = null,
                    modifier = Modifier.size(ToggleButtonDefaults.IconSize)
                )
                Spacer(Modifier.size(ToggleButtonDefaults.IconSpacing))
                Text(appearance.label(mode), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** The group's name for screen readers. */
private const val SHOWS_IN = "Shows in"

/** "Shows in"'s rule (13.4), pure. */
object ShowsIn {

    /**
     * [shown] with [mode] switched on or off, in the modes' order; with [keepOne], switching off
     * the last mode on changes nothing.
     */
    fun toggled(shown: Set<ContextMode>, mode: ContextMode, keepOne: Boolean): Set<ContextMode> {
        val next = if (mode in shown) shown - mode else shown + mode
        if (keepOne && next.isEmpty()) return shown
        return ContextMode.entries.filterTo(LinkedHashSet()) { it in next }
    }
}
