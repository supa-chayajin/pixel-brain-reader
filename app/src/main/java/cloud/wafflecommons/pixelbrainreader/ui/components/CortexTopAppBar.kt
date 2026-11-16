package cloud.wafflecommons.pixelbrainreader.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * The app's header (13.2, Material 3 Expressive, the owner's call: "make it really beautiful" and
 * used everywhere), in two modes:
 *
 *  - **A tab's page** (default): the screen's emblem ([icon]) on an expressive shape in its accent
 *    tone, the emphasized headline, the [subtitle] as a tonal pill, all centred, at a small bar's
 *    height: the tabs' layout stays the owner's.
 *  - **[large]**: the large flexible app bar, the emblem and the headline big at the top of the page
 *    and collapsing as it scrolls ([scrollBehavior], `exitUntilCollapsed`). Every [FormScaffold]
 *    page uses it.
 *
 * Every colour is a role of the device's dynamic scheme.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun CortexTopAppBar(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    /** The emblem's shape and tone come from this key: the tab's name on a tab's page (13.2). */
    emblemKey: String = title,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
    scrollBehavior: TopAppBarScrollBehavior? = null,
    large: Boolean = false
) {
    val colors = TopAppBarDefaults.topAppBarColors(
        containerColor = MaterialTheme.colorScheme.surface,
        scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer
    )
    if (large) {
        LargeFlexibleTopAppBar(
            title = { Heading(title, icon, emblemKey, badgeSize = 40.dp, large = true) },
            subtitle = subtitle?.let { line -> { SubtitlePill(line) } },
            navigationIcon = navigationIcon,
            actions = actions,
            titleHorizontalAlignment = Alignment.CenterHorizontally,
            scrollBehavior = scrollBehavior,
            colors = colors,
            modifier = modifier
        )
        return
    }
    if (subtitle != null) {
        // The owner's review (13.2): taller and larger, the pill clear of the emblem.
        TopAppBar(
            title = { Heading(title, icon, emblemKey, badgeSize = 44.dp, large = false) },
            subtitle = { SubtitlePill(subtitle, modifier = Modifier.padding(top = 8.dp)) },
            navigationIcon = navigationIcon,
            actions = actions,
            titleHorizontalAlignment = Alignment.CenterHorizontally,
            expandedHeight = 104.dp,
            scrollBehavior = scrollBehavior,
            colors = colors,
            modifier = modifier
        )
    } else {
        CenterAlignedTopAppBar(
            title = { Heading(title, icon, emblemKey, badgeSize = 44.dp, large = false) },
            navigationIcon = navigationIcon,
            actions = actions,
            expandedHeight = 80.dp,
            scrollBehavior = scrollBehavior,
            colors = colors,
            modifier = modifier
        )
    }
}

/**
 * The emblem on its expressive shape, then the emphasized headline. The open tab's page shows its
 * tab's emblem, drawn for this visit (13.2.1).
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun Heading(title: String, icon: ImageVector?, key: String, badgeSize: androidx.compose.ui.unit.Dp, large: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) {
            val openTab = LocalOpenTabEmblem.current?.takeIf { it.label == key }
            if (openTab != null) {
                ExpressiveBadge(
                    icon = icon,
                    key = key,
                    size = badgeSize,
                    tone = placeTone(openTab.place),
                    polygon = TabEmblems.shapes[openTab.shape]
                )
            } else {
                ExpressiveBadge(icon = icon, key = key, size = badgeSize)
            }
            Spacer(Modifier.width(if (large) 14.dp else 12.dp))
        }
        Text(
            text = title,
            style = if (large) MaterialTheme.typography.headlineLargeEmphasized else MaterialTheme.typography.headlineMediumEmphasized,
            maxLines = if (large) 2 else 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** The subtitle as a tonal pill: the date, a count, a state. */
@Composable
private fun SubtitlePill(text: String, modifier: Modifier = Modifier) {
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer, modifier = modifier) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 3.dp)
        )
    }
}
