package cloud.wafflecommons.pixelbrainreader.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.focusable
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.layout.AnimatedPane
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffold
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffoldRole
import androidx.compose.material3.adaptive.navigation.BackNavigationBehavior
import androidx.compose.material3.adaptive.navigation.rememberListDetailPaneScaffoldNavigator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * A hub (Projects, Boards, Runbooks): its list and the page of the entry opened from it.
 *
 * On a wide window (a tablet in landscape, an unfolded Fold) both show side by side: the open entry
 * is highlighted in the list, opening another one replaces the page, and back closes the page before
 * it leaves the hub. On a phone or a tablet in portrait the page opens over the list, and back
 * returns to it, as when the page was a screen of its own. A page open side by side fills the
 * screen when the window narrows (a Fold folding, a rotation), and the list comes back beside it
 * when the window widens again.
 *
 * [list] gets the open entry's key (null when none) and the function that opens one; [detail] gets
 * the key and the function that closes the page; [emptyDetail] fills the page pane until then.
 * Each entry's page starts from fresh local state.
 */
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
fun HubListDetail(
    list: @Composable (open: String?, onOpen: (String) -> Unit) -> Unit,
    detail: @Composable (key: String, onClose: () -> Unit) -> Unit,
    emptyDetail: @Composable () -> Unit
) {
    val navigator = rememberListDetailPaneScaffoldNavigator<String>()
    val scope = rememberCoroutineScope()
    val open = navigator.currentDestination?.takeIf { it.pane == ListDetailPaneScaffoldRole.Detail }?.contentKey
    // "Until the content changes", not the default "until the panes change": side by side the panes
    // never change, so back would skip the open page and leave the hub. Closing the page is a change.
    val closePage: () -> Unit = { scope.launch { navigator.navigateBack(BackNavigationBehavior.PopUntilContentChange) } }
    BackHandler(enabled = navigator.canNavigateBack(BackNavigationBehavior.PopUntilContentChange)) { closePage() }

    // The plain scaffold follows navigator.scaffoldValue, recomputed from the window on every change.
    // (NavigableListDetailPaneScaffold follows its own animated state, which only moves on a navigation:
    // after the window narrowed, it kept both panes squeezed into a phone-width screen.)
    ListDetailPaneScaffold(
        directive = navigator.scaffoldDirective,
        value = navigator.scaffoldValue,
        listPane = {
            AnimatedPane {
                list(open) { key ->
                    scope.launch {
                        // Beside an open page, the new one replaces it rather than stacking up
                        // behind it: back then closes the page instead of walking every entry seen.
                        if (navigator.currentDestination?.pane == ListDetailPaneScaffoldRole.Detail) {
                            navigator.navigateBack(BackNavigationBehavior.PopLatest)
                        }
                        navigator.navigateTo(ListDetailPaneScaffoldRole.Detail, key)
                    }
                }
            }
        },
        detailPane = {
            AnimatedPane {
                Box {
                    PaneFocusSink()
                    if (open != null) {
                        key(open) { detail(open, closePage) }
                    } else {
                        emptyDetail()
                    }
                }
            }
        }
    )
}

/**
 * The adaptive scaffold moves focus into the current pane each time its layout changes (a fold, a
 * rotation), for keyboard users. Under a finger, buttons and cards don't take focus, so it landed on
 * the page's first text field (a project page's hand-off box), whose keyboard then popped up unasked.
 * This empty target, first in the pane and out of TalkBack's way, takes that focus instead; tapping
 * a field still focuses it, and a keyboard user tabs on from here.
 */
@Composable
private fun PaneFocusSink() {
    Box(Modifier.size(1.dp).semantics { hideFromAccessibility() }.focusable())
}

/** What the page pane of a hub shows on a wide window before an entry is opened. */
@Composable
fun HubEmptyPane(icon: ImageVector, text: String) {
    Box(modifier = Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(48.dp))
            Spacer(Modifier.height(12.dp))
            Text(
                text,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}
