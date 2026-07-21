package cloud.wafflecommons.pixelbrainreader.ui.main

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.material3.Surface
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material.icons.Icons
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.composable
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloseFullscreen
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.OpenInFull
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.rounded.Visibility

import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.DateRange
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.filled.Mood
import cloud.wafflecommons.pixelbrainreader.ui.settings.SettingsScreen
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import cloud.wafflecommons.pixelbrainreader.ui.components.CortexIconButton
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.adaptive.WindowAdaptiveInfo
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.material3.adaptive.layout.AnimatedPane
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffold
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffoldRole
import androidx.compose.material3.adaptive.layout.calculatePaneScaffoldDirective
import androidx.compose.material3.adaptive.navigation.rememberListDetailPaneScaffoldNavigator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import cloud.wafflecommons.pixelbrainreader.ui.theme.NavBarClearance
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.RadialGradientShader
import androidx.compose.ui.graphics.Shader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.window.core.layout.WindowSizeClass
import kotlinx.coroutines.launch

import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.FilledTonalIconButton
import nl.dionsegijn.konfetti.compose.KonfettiView
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

object Screen {
    const val Home = "home"
    const val Chat = "chat"
    const val HomeOS = "home_os"
    const val Settings = "settings"
    const val Import = "import"
    const val DailyNote = "daily_note"
    const val MoodTracker = "mood"
    const val Stats = "stats"
    const val ROUTE_HOME_CONFIG = "home_config"
}

/** Height of the floating [ExpressiveNavBar]'s tab surface (single source of truth). */
internal val ExpressiveNavBarHeight = 64.dp

/**
 * Bottom clearance that scrollable content must reserve so its last lines are not
 * occluded by the floating [ExpressiveNavBar]. The bar floats over content and
 * reserves no layout space, so = bar height + its 16.dp vertical padding + 8.dp
 * breathing room. The system nav-bar inset is NOT included here (it is already
 * applied by the Home Scaffold's content padding).
 */
internal val ExpressiveNavBarClearance = ExpressiveNavBarHeight + 24.dp

/**
 * Material 3 Expressive floating navigation bar modeled on the new Google Finance
 * app: a rounded floating group of regular tabs + a separate, emphasized "Daily"
 * button (Finance's "Ask" equivalent). Folded shows Repo/Habits/Chores; unfolded
 * reveals all. The active tab shows a spring-animated pill behind its icon.
 */
@Composable
private fun ExpressiveNavBar(
    currentRoute: String?,
    isViewingDailyNote: Boolean,
    isLargeScreen: Boolean,
    order: List<String>,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current

    // The floating bar's elevation shadow is tinted with the primary (sage) accent. On a dark
    // surface that bright tint reads as an ugly glowing halo — but a shadow dimmed all the way to
    // black just vanishes against the dark page. So on dark themes we keep the shadow COLOURED and
    // at full elevation, only dialling its brightness partway down toward black: still clearly a
    // shadow, just a subtler, darker green. Derived from the resolved surface luminance so it
    // tracks the ACTUAL theme (not just the system setting).
    val isDarkSurface = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val navShadowColor = if (isDarkSurface) lerp(MaterialTheme.colorScheme.primary, Color.Black, 0.4f)
        else MaterialTheme.colorScheme.primary
    val navShadowElevation = 6.dp

    data class NavDest(val route: String, val icon: ImageVector, val label: String, val selected: Boolean)

    // First three = the folded set (Repo, Habits, Chores). Unfolded reveals the rest.
    val allRegular = listOf(
        NavDest(Screen.Home, Icons.Rounded.Dashboard, "Dépôt", currentRoute == Screen.Home && !isViewingDailyNote),
        NavDest("habits", Icons.Rounded.DateRange, "Habitudes", currentRoute == "habits"),
        NavDest(Screen.HomeOS, Icons.Rounded.CleaningServices, "Corvées", currentRoute == Screen.HomeOS),
        NavDest(Screen.Chat, Icons.Rounded.Psychology, "Chat", currentRoute == Screen.Chat),
        NavDest(Screen.MoodTracker, Icons.Default.Mood, "Humeur", currentRoute == Screen.MoodTracker),
        NavDest(Screen.Stats, Icons.Default.Star, "Stats", currentRoute == Screen.Stats)
    )
    // Apply the user's custom order; any destination not present in `order` (e.g. added in a
    // later app version) is appended so nothing ever disappears from the bar.
    val ordered = order.mapNotNull { route -> allRegular.find { it.route == route } }
        .plus(allRegular.filter { dest -> order.none { it == dest.route } })
    val regular = if (isLargeScreen) ordered else ordered.take(3)
    val dailySelected = currentRoute == Screen.DailyNote

    Row(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Shared geometry so the group, the Daily button and the selection pill
        // are all concentric: outerRadius on the bar, (outerRadius - innerPad) on
        // the selection pill so it looks like it FILLS the bar's inner area.
        val barRadius = 28.dp
        val innerPad = 6.dp
        val pillRadius = barRadius - innerPad

        // --- Main floating group of regular tabs ---
        // SOLID pill (elevated surface) so it reads as a defined floating bar;
        // the page shows through around it (nav floats over content).
        Surface(
            shape = RoundedCornerShape(barRadius),
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            modifier = Modifier
                .weight(1f)
                .height(ExpressiveNavBarHeight)
                // Theme-coloured (primary) elevation shadow. The default BLACK shadow gets
                // inverted into an ugly white halo under a device force-dark override, so we
                // set the spot/ambient colours explicitly to the accent.
                .shadow(
                    elevation = navShadowElevation,
                    shape = RoundedCornerShape(barRadius),
                    spotColor = navShadowColor,
                    ambientColor = navShadowColor
                )
        ) {
            val selectedIndex = regular.indexOfFirst { it.selected }
            val hasSelection = selectedIndex >= 0
            // Park the sliding pill at the last selected slot; keep it there (faded out)
            // while a non-group tab (Daily) is active, so it slides back correctly on return.
            var parkedIndex by remember { mutableIntStateOf(selectedIndex.coerceAtLeast(0)) }
            LaunchedEffect(selectedIndex) { if (selectedIndex >= 0) parkedIndex = selectedIndex }

            BoxWithConstraints(modifier = Modifier.fillMaxSize().padding(innerPad)) {
                val count = regular.size
                val itemWidthPx = constraints.maxWidth.toFloat() / count
                // The shared selection pill SLIDES to the active slot (spring) instead of
                // each tab fading its own pill in and out.
                val indicatorX by animateFloatAsState(
                    targetValue = parkedIndex * itemWidthPx,
                    // Snappy but visible glide with a gentle settle. (StiffnessMediumLow=400
                    // finished in ~250ms and read as a snap; StiffnessLow=200 felt a touch slow.)
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioLowBouncy,
                        stiffness = 320f
                    ),
                    label = "navIndicatorX"
                )
                val indicatorAlpha by animateFloatAsState(
                    targetValue = if (hasSelection) 1f else 0f,
                    animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                    label = "navIndicatorAlpha"
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth(1f / count)
                        .fillMaxHeight()
                        .graphicsLayer {
                            translationX = indicatorX
                            alpha = indicatorAlpha
                        }
                        .clip(RoundedCornerShape(pillRadius))
                        .background(MaterialTheme.colorScheme.secondaryContainer)
                )

                Row(
                    modifier = Modifier.fillMaxSize(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    regular.forEach { item ->
                        NavPiece(
                            icon = item.icon,
                            label = item.label,
                            selected = item.selected,
                            onClick = { if (!item.selected) onSelect(item.route) },
                            modifier = Modifier.weight(1f).fillMaxHeight()
                        )
                    }
                }
            }
        }

        // --- Separate, emphasized "Daily" button (à la Finance's "Ask") ---
        // Same radius + same dark surface as the group, distinguished only by a
        // green GLOW (colored shadow + accent border), like Finance's Ask button.
        val accent = MaterialTheme.colorScheme.primary
        // Smoothly fade the Daily icon/label between active (bright) and idle (dimmed).
        val dailyContent by animateColorAsState(
            targetValue = if (dailySelected) accent else accent.copy(alpha = 0.5f),
            animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
            label = "dailyContent"
        )
        val surfaceHi = MaterialTheme.colorScheme.surfaceContainerHighest
        // Soft spring fade-in of the top-right glow + accent border when Daily is selected,
        // plus a gentle scale-pop — the "expressive" flourish.
        val glowAlpha by animateFloatAsState(
            targetValue = if (dailySelected) 0.45f else 0f,
            animationSpec = spring(stiffness = Spring.StiffnessLow),
            label = "dailyGlow"
        )
        val borderAlpha by animateFloatAsState(
            targetValue = if (dailySelected) 0.9f else 0f,
            animationSpec = spring(stiffness = Spring.StiffnessLow),
            label = "dailyBorder"
        )
        val dailyScale by animateFloatAsState(
            targetValue = if (dailySelected) 1.06f else 1f,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessMedium
            ),
            label = "dailyScale"
        )
        val dailyGlow = remember(glowAlpha, accent) {
            object : ShaderBrush() {
                override fun createShader(size: Size): Shader =
                    RadialGradientShader(
                        center = Offset(size.width, 0f),
                        radius = size.maxDimension,
                        colors = listOf(accent.copy(alpha = glowAlpha), Color.Transparent)
                    )
            }
        }
        Box(
            modifier = Modifier
                .graphicsLayer { scaleX = dailyScale; scaleY = dailyScale }
                .height(64.dp)
                // Match the nav group with a theme-coloured (accent) elevation shadow so
                // the emphasized Daily button doesn't read as flat — and never inverts to
                // a white halo under force-dark.
                .shadow(
                    elevation = navShadowElevation,
                    shape = RoundedCornerShape(barRadius),
                    spotColor = navShadowColor,
                    ambientColor = navShadowColor
                )
                .clip(RoundedCornerShape(barRadius))
                // Opaque dark base so no content bleeds through the pill...
                .background(surfaceHi)
                // ...then a green glow on top, anchored to the top-right corner
                // (fading to transparent) so it reads as a directional accent.
                .background(dailyGlow)
                .border(
                    BorderStroke(1.5.dp, accent.copy(alpha = borderAlpha)),
                    RoundedCornerShape(barRadius)
                )
                .clickable {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    if (!dailySelected) onSelect(Screen.DailyNote)
                }
                .padding(horizontal = 28.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Rounded.AutoAwesome, contentDescription = "Quotidien", tint = dailyContent, modifier = Modifier.size(24.dp))
                Spacer(Modifier.height(3.dp))
                Text("Quotidien", style = MaterialTheme.typography.labelMedium, color = dailyContent, maxLines = 1)
            }
        }
    }
}

@Composable
private fun NavPiece(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    // Cross-fade the icon/label colour instead of snapping.
    val tint by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.onSecondaryContainer
            else MaterialTheme.colorScheme.onSurfaceVariant,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "navTint"
    )
    // Springy scale-pop on the icon when a tab becomes active — the "expressive" bounce.
    val iconScale by animateFloatAsState(
        targetValue = if (selected) 1.18f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "navIconScale"
    )
    // No per-tab pill — the shared sliding indicator (in ExpressiveNavBar) draws it.
    Box(
        modifier = modifier
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onClick()
                }
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                icon,
                contentDescription = label,
                tint = tint,
                modifier = Modifier
                    .size(20.dp)
                    .graphicsLayer { scaleX = iconScale; scaleY = iconScale }
            )
            Spacer(Modifier.height(2.dp))
            Text(label, style = MaterialTheme.typography.labelSmall, color = tint, maxLines = 1)
        }
    }
}

@OptIn(
    ExperimentalMaterial3AdaptiveApi::class,
    ExperimentalMaterial3Api::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class
)
@Composable
fun MainScreen(
    onLogout: () -> Unit,
    onExitApp: () -> Unit,
    viewModel: MainViewModel = hiltViewModel(),
    moodViewModel: cloud.wafflecommons.pixelbrainreader.ui.mood.MoodViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // Window size + pane directive are computed BEFORE the navigator so its
    // scaffoldValue is derived from the SAME (focus-aware) directive the scaffold
    // uses. Otherwise focus mode sets maxHorizontalPartitions=1 on the scaffold only,
    // while the navigator keeps the base 2-partition directive → both panes stay
    // Expanded and the detail never takes the full width.
    val windowAdaptiveInfo: WindowAdaptiveInfo = currentWindowAdaptiveInfo()
    val windowSizeClass = windowAdaptiveInfo.windowSizeClass
    val isLargeScreen = windowSizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND)
    val baseDirective = calculatePaneScaffoldDirective(windowAdaptiveInfo)
    val finalDirective = if (uiState.isFocusMode && isLargeScreen) {
        baseDirective.copy(
            maxHorizontalPartitions = 1,
            horizontalPartitionSpacerSize = 0.dp,
            verticalPartitionSpacerSize = 0.dp
        )
    } else {
        baseDirective.copy(
            horizontalPartitionSpacerSize = 8.dp,
            defaultPanePreferredWidth = uiState.listPaneWidth.dp // DYNAMIC WIDTH
        )
    }

    val navigator = rememberListDetailPaneScaffoldNavigator<Any>(scaffoldDirective = finalDirective)
    val scope = rememberCoroutineScope()
    // context is used for Toasts and Activity control. Ensure single declaration.
    val context = androidx.compose.ui.platform.LocalContext.current
    val haptic = LocalHapticFeedback.current
    
    // --- Global Sensory Polish (RFC-008) ---
    // Inject via Hilt manually if not provided in constructor, or use EntryPoint. 
    // Since MainScreen is a Composable, better to inject in ViewModel or pass it.
    // However, MainScreen is the root. We can get it from MainViewModel if we inject it there?
    // Or just ask Hilt to provide it if we change signature. 
    // Let's assume we can add it to signature.
    
    // But changing signature breaks preview/callers if any. 
    // It is called from MainActivity. 
    // Let's add it to signature with default value if possible, or better:
    // Observe it from MainViewModel which should inject it.
    
    // Let's modify MainViewModel to expose the effects or just inject it here.
    // Changing MainScreen signature:
    // viewModel: MainViewModel = hiltViewModel(),
    // moodViewModel: ...
    // uiEffectManager: cloud.wafflecommons.pixelbrainreader.ui.utils.UiEffectManager = hiltViewModel() // No, it's not a VM.
    
    // We should inject it in MainViewModel and expose it, OR use an EntryPoint.
    // Simpler: Inject in MainViewModel, expose `effects` flow.
    // Let's modify MainViewModel first or assume it has it.
    // The instructions said "Inject UiEffectManager (via MainViewModel or direct inject)".
    // I will inject it here using a temporary Hilt EntryPoint workaround or just add to MainViewModel.
    // Adding to MainViewModel is cleanest.
    
    // Wait, I can't modify MainViewModel in this `replace_file_content`.
    // I will use a local variable for now or modify the code logic to assume MainViewModel exposes it.
    // Actually, let's look at `MainViewModel`.
    
    // PROPOSAL: I will modify `MainViewModel` to inject `UiEffectManager` and expose `globalEffects`.
    // For this step, I will prepare `MainScreen` to consume it from `viewModel.globalEffects`.
    
    var partyState by remember { mutableStateOf<List<nl.dionsegijn.konfetti.core.Party>>(emptyList()) }
    
    LaunchedEffect(viewModel) {
         viewModel.globalEffects.collect { effect ->
             when(effect) {
                 is cloud.wafflecommons.pixelbrainreader.ui.utils.GlobalEffect.Confetti -> {
                     partyState = when(effect.type) {
                         cloud.wafflecommons.pixelbrainreader.ui.utils.ConfettiType.LEVEL_UP -> {
                             // Reset after 3s
                             scope.launch { 
                                 kotlinx.coroutines.delay(3000)
                                 partyState = emptyList() 
                             }
                             listOf(
                                 nl.dionsegijn.konfetti.core.Party(
                                     speed = 0f,
                                     maxSpeed = 30f,
                                     damping = 0.9f,
                                     spread = 360,
                                     colors = listOf(0xfce18a, 0xff726d, 0xf4306d, 0xb48def),
                                     emitter = nl.dionsegijn.konfetti.core.emitter.Emitter(duration = 100, TimeUnit.MILLISECONDS).max(100),
                                     position = nl.dionsegijn.konfetti.core.Position.Relative(0.5, 0.3)
                                 )
                             )
                         }
                         cloud.wafflecommons.pixelbrainreader.ui.utils.ConfettiType.GOAL_REACHED -> {
                              // Reset after 4s (duration 2000 + buffer)
                             scope.launch { 
                                 kotlinx.coroutines.delay(4000)
                                 partyState = emptyList() 
                             }
                              listOf(
                                 nl.dionsegijn.konfetti.core.Party(
                                     speed = 10f,
                                     maxSpeed = 30f,
                                     damping = 0.9f,
                                     spread = 360,
                                     colors = listOf(0xfce18a, 0xff726d, 0xf4306d, 0xb48def),
                                     emitter = nl.dionsegijn.konfetti.core.emitter.Emitter(duration = 2000, TimeUnit.MILLISECONDS).max(200),
                                     position = nl.dionsegijn.konfetti.core.Position.Relative(0.5, 0.5) // Center
                                 )
                             )
                         }
                         else -> emptyList()
                     }
                 }
             }
         }
    }

    LaunchedEffect(viewModel.uiEvent) {
        viewModel.uiEvent.collect { event ->
            when(event) {
                is cloud.wafflecommons.pixelbrainreader.ui.utils.UiEvent.ShowToast -> {
                    android.widget.Toast.makeText(context, event.message, android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
    val navController = androidx.navigation.compose.rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    // Hide the navigation bar while the keyboard is open. NavigationSuiteScaffold
    // otherwise reserves the app nav-bar height for content, and because imePadding
    // uses the full IME inset, the input floats a nav-bar-height above the keyboard
    // on EVERY text surface. layoutType = None reclaims that space (and gives more
    // room to type); the nav returns when the keyboard closes.
    // Also hide it while editing a file in the detail pane: editor mode wants the full
    // height for writing, and the floating bar would otherwise overlay the editor.
    val isEditingFile = uiState.isEditing && uiState.selectedFileName != null
    val navLayoutType = if (WindowInsets.isImeVisible || isEditingFile) {
        androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType.None
    } else {
        // Custom ExpressiveNavBar is a bottom bar in all sizes (Finance-style).
        androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType.NavigationBar
    }

    // SAF launcher: export the whole vault as a ZIP to a user-picked location (outside the app).
    val vaultExportLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/zip")
    ) { uri: android.net.Uri? -> uri?.let { viewModel.exportVault(it) } }

    // Live repo-sync step label for the Repo / File-detail pull-to-refresh indicators.
    val repoSyncStatus by viewModel.repoSyncStatus.collectAsStateWithLifecycle()

    // Custom nav-bar order (persisted); drives the order of the regular tabs.
    val navBarOrder by viewModel.navBarOrder.collectAsStateWithLifecycle()

    // Smart Active State Logic
    val todayName = remember {
        val now = LocalDateTime.now()
        val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
        "${now.format(formatter)}.md"
    }
    val isViewingDailyNote = uiState.selectedFileName == todayName

    // -- NAVIGATION LOGIC --
    // Mobile (Compact): Default to List. Navigate to Detail only if file selected.
    // Tablet (Expanded): Default to List+Detail.
    
    LaunchedEffect(isLargeScreen, uiState.selectedFileName) {
        if (!isLargeScreen) {
            // Mobile Mode
            if (uiState.selectedFileName != null) {
                navigator.navigateTo(ListDetailPaneScaffoldRole.Detail)
            } else {
                navigator.navigateTo(ListDetailPaneScaffoldRole.List)
            }
        } else {
            // Tablet/Expanded Mode
            if (uiState.isFocusMode) {
                 navigator.navigateTo(ListDetailPaneScaffoldRole.Detail)
            } else {
                 // Even if nothing selected, we show List+Detail (Detail is Welcome Screen)
                 // Ensure navigator understands we want to show both if space allows (Directive handles this),
                 // but we need to ensure we aren't stuck in "List Only" mode if the framework thinks so.
                 // Actually, ListDetailPaneScaffold handles the dual view automatically based on directive.
                 // We only need to force Detail if Focus Mode.
                 if (navigator.currentDestination?.pane == ListDetailPaneScaffoldRole.List && uiState.selectedFileName != null) {
                      navigator.navigateTo(ListDetailPaneScaffoldRole.Detail)
                 }
            }
        }
    }

    LaunchedEffect(uiState.isFocusMode) {
        if (uiState.isFocusMode && isLargeScreen) {
            navigator.navigateTo(ListDetailPaneScaffoldRole.Detail)
        }
    }

    // Folder-insight ready → open the Detail pane. A one-shot event (not a selectedFileName diff)
    // so re-analyzing while an insight is already open still navigates to the fresh result.
    LaunchedEffect(Unit) {
        viewModel.openDetailEvent.collect {
            navigator.navigateTo(ListDetailPaneScaffoldRole.Detail)
        }
    }
    
    // Auto-navigate to Import if state present
    LaunchedEffect(uiState.importState) {
        if (uiState.importState != null) {
            navController.navigate("import")
        }
    }



    // Hotfix: Programmatic Navigation (Daily Note)
    LaunchedEffect(uiState.navigationTrigger) {
        uiState.navigationTrigger?.let { route ->
            navController.navigate(route) {
                popUpTo(navController.graph.findStartDestination().id) {
                    saveState = true
                }
                launchSingleTop = true
                restoreState = true
            }
            viewModel.consumeNavigationTrigger()
        }
    }
    
    // Auto-Close logic for External Imports
    LaunchedEffect(uiState.isExitPending) {
        if (uiState.isExitPending) {
            (context as? android.app.Activity)?.finish()
        }
    }

    // Mood Event Listening
    LaunchedEffect(moodViewModel.uiEvent) {
        moodViewModel.uiEvent.collect { event ->
             when(event) {
                is cloud.wafflecommons.pixelbrainreader.ui.utils.UiEvent.ShowToast -> {
                    android.widget.Toast.makeText(context, event.message, android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // Mood Sheet State for Daily Note
    var showMoodSheet by remember { mutableStateOf(false) }


    if (showMoodSheet) {
        cloud.wafflecommons.pixelbrainreader.ui.mood.MoodCheckInSheet(
            onDismiss = { showMoodSheet = false },
            viewModel = moodViewModel
        )
    }
    
    // Delete Confirmation Dialog
    if (uiState.showDeleteConfirmation) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { viewModel.dismissDeleteConfirmation() },
            title = { Text("Supprimer ce fichier ?") },
            text = { Text("Cette action est irréversible et supprimera le fichier du coffre et du dépôt Git.") },
            icon = { Icon(Icons.Rounded.Delete, contentDescription = null) },
            confirmButton = {
                androidx.compose.material3.TextButton(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        viewModel.confirmDeleteFile()
                    },
                    colors = androidx.compose.material3.ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Supprimer")
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { viewModel.dismissDeleteConfirmation() }) {
                    Text("Annuler")
                }
            }
        )
    }

    // The nav bar FLOATS over the content (Finance-style): content fills the whole
    // screen and the bar is overlaid at the bottom, so the page shows through around
    // and behind it — no reserved dark strip. (Content behind the bar is accepted.)
    Box(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.fillMaxSize()) {
            androidx.navigation.compose.NavHost(
            navController = navController,
            startDestination = Screen.DailyNote,
            modifier = Modifier.fillMaxSize(),
            // Expressive fade-through between top-level destinations (fade + subtle scale).
            enterTransition = {
                androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(280, 40)) +
                    androidx.compose.animation.scaleIn(
                        initialScale = 0.94f,
                        animationSpec = androidx.compose.animation.core.tween(280, 40)
                    )
            },
            exitTransition = { androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(180)) },
            popEnterTransition = {
                androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(280, 40)) +
                    androidx.compose.animation.scaleIn(
                        initialScale = 0.94f,
                        animationSpec = androidx.compose.animation.core.tween(280, 40)
                    )
            },
            popExitTransition = { androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(180)) }
        ) {
            composable(Screen.Home) {
                // Home with ListDetailPaneScaffold, Breadcrumbs, and Global TopBar
                
                val canNavigateBack = navigator.canNavigateBack()
                val isSubFolder = uiState.currentPath.isNotEmpty()

                if (uiState.showCreateFileDialog) {
                    cloud.wafflecommons.pixelbrainreader.ui.components.NewFileBottomSheet(
                        availableTemplates = uiState.availableTemplates,
                        onDismiss = { viewModel.dismissCreateFileDialog() },
                        onCreate = { name, template -> viewModel.createNewFile(name, template) }
                    )
                }

                // Back Handler for Home Logic
                val isBackHandlerEnabled = uiState.isFocusMode || canNavigateBack || isSubFolder
                
                BackHandler(enabled = isBackHandlerEnabled) {
                    when {
                        uiState.isFocusMode -> viewModel.toggleFocusMode()
                        canNavigateBack -> scope.launch { navigator.navigateBack() }
                        isSubFolder -> viewModel.navigateUp()
                    }
                }

                val snackbarHostState = remember { SnackbarHostState() }
                
                LaunchedEffect(uiState.userMessage) {
                    uiState.userMessage?.let { message ->
                        snackbarHostState.showSnackbar(message)
                        viewModel.userMessageShown()
                    }
                }

                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    snackbarHost = {
                        // Lift above the floating ExpressiveNavBar, which would otherwise cover it.
                        SnackbarHost(
                            hostState = snackbarHostState,
                            modifier = Modifier.padding(bottom = NavBarClearance)
                        )
                    },
                    topBar = {
                        // Local state to manage Search Bar visibility (toggles UI mode)
                        var isSearching by remember { mutableStateOf(false) }

                        TopAppBar(
                            title = { 
                                if (isSearching) {
                                    // SEARCH MODE: Input Field
                                    androidx.compose.material3.TextField(
                                        value = uiState.searchQuery,
                                        onValueChange = { viewModel.onSearchQueryChanged(it) },
                                        placeholder = { Text("Rechercher...") },
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth().padding(end = 8.dp),
                                        shape = androidx.compose.foundation.shape.CircleShape,
                                        colors = androidx.compose.material3.TextFieldDefaults.colors(
                                            focusedIndicatorColor = Color.Transparent,
                                            unfocusedIndicatorColor = Color.Transparent
                                        ),
                                        // Clear/Close Icon inside the text field
                                        trailingIcon = {
                                             if (uiState.searchQuery.isNotEmpty()) {
                                                 CortexIconButton(onClick = { viewModel.onSearchQueryChanged("") }) {
                                                     Icon(Icons.Default.SearchOff, "Effacer")
                                                 }
                                             }
                                        }
                                    )
                                } else {
                                    // VIEW MODE: Breadcrumbs
                                    cloud.wafflecommons.pixelbrainreader.ui.components.BreadcrumbBar(
                                        currentPath = uiState.currentPath,
                                        onPathClick = { path -> viewModel.loadFolder(path) },
                                        onHomeClick = { viewModel.loadFolder("") },
                                        isLargeScreen = isLargeScreen
                                    )
                                }
                            },
                            actions = {
                                if (isSearching) {
                                    // SEARCH MODE ACTION: Exit Search
                                    FilledTonalIconButton(
                                        onClick = {
                                            isSearching = false
                                            viewModel.onSearchQueryChanged("")
                                        },
                                        colors = IconButtonDefaults.filledTonalIconButtonColors(
                                            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    ) {
                                        Icon(Icons.Default.SearchOff, "Quitter la recherche")
                                    }
                                } else {
                                    // VIEW MODE ACTIONS
                                    // 1. Existing Actions
                                    if (uiState.selectedFileName != null) {
                                        // Edit/View Toggle
                                        FilledTonalIconButton(
                                            onClick = { viewModel.toggleEditMode() },
                                            colors = IconButtonDefaults.filledTonalIconButtonColors(
                                                containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                                                contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        ) {
                                            Icon(
                                                imageVector = if (uiState.isEditing) Icons.Rounded.Visibility else Icons.Rounded.Edit,
                                                contentDescription = if (uiState.isEditing) "Afficher" else "Modifier"
                                            )
                                        }

                                        // Delete button removed from here

                                        // Focus Mode (Large Screen Only)
                                        if (isLargeScreen) {
                                            FilledTonalIconButton(
                                                onClick = { viewModel.toggleFocusMode() },
                                                colors = IconButtonDefaults.filledTonalIconButtonColors(
                                                    containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                                                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            ) {
                                                Icon(
                                                    imageVector = if (uiState.isFocusMode) Icons.Rounded.CloseFullscreen else Icons.Rounded.OpenInFull,
                                                    contentDescription = "Mode concentration"
                                                )
                                            }
                                        }

                                        // Close File
                                        FilledTonalIconButton(
                                            onClick = {
                                                viewModel.closeFile()
                                                if (navigator.canNavigateBack()) {
                                                    scope.launch { navigator.navigateBack() }
                                                }
                                            },
                                            colors = IconButtonDefaults.filledTonalIconButtonColors(
                                                containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                                                contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        ) {
                                            Icon(Icons.Rounded.Close, "Fermer")
                                        }

                                    } else {
                                        // Search Trigger
                                        FilledTonalIconButton(
                                            onClick = { isSearching = true },
                                            colors = IconButtonDefaults.filledTonalIconButtonColors(
                                                containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                                                contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        ) {
                                            Icon(Icons.Rounded.Search, "Rechercher")
                                        }

                                        // Browser Actions
                                        FilledTonalIconButton(
                                            onClick = { viewModel.openCreateFileDialog() },
                                            colors = IconButtonDefaults.filledTonalIconButtonColors(
                                                containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                                                contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        ) {
                                            Icon(Icons.Rounded.Add, "Nouveau fichier")
                                        }

                                        // Export the whole vault as a ZIP to phone storage (outside the app).
                                        FilledTonalIconButton(
                                            onClick = {
                                                vaultExportLauncher.launch("PixelBrainVault_${java.time.LocalDate.now()}.zip")
                                            },
                                            colors = IconButtonDefaults.filledTonalIconButtonColors(
                                                containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                                                contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        ) {
                                            Icon(Icons.Rounded.Download, "Exporter le coffre")
                                        }
                                    }
                                }
                            }
                        )
                    },

                    contentWindowInsets = androidx.compose.material3.ScaffoldDefaults.contentWindowInsets
                ) { padding ->
                    Box(
                        modifier = Modifier
                            .padding(padding) // Applies TopBar padding
                            .consumeWindowInsets(padding) 
                            .fillMaxSize()
                    ) {
                        Column(modifier = Modifier.fillMaxSize()) {
                            // Main Content
                            ListDetailPaneScaffold(
                                directive = finalDirective,
                                value = navigator.scaffoldValue,
                                listPane = {
                                    // The scaffold's directive/scaffoldValue now hide this pane
                                    // entirely in focus mode (List = AdaptStrategy.Hide), so the
                                    // AnimatedPane transition is all that's needed — no inner
                                    // AnimatedVisibility (which would double the exit animation).
                                    AnimatedPane {
                                        Row(modifier = Modifier.fillMaxSize()) {
                                            Box(modifier = Modifier.weight(1f)) {
                                                FileListPane(
                                                    files = uiState.files,
                                                    isLoading = uiState.isLoading,
                                                    isRefreshing = uiState.isRefreshing,
                                                    statusText = repoSyncStatus,
                                                    error = uiState.error,
                                                    currentPath = uiState.currentPath,
                                                    searchQuery = uiState.searchQuery, // PASS QUERY
                                                    showMenuIcon = false,
                                                    onFileClick = { file ->
                                                        viewModel.loadFile(file)
                                                        scope.launch { navigator.navigateTo(ListDetailPaneScaffoldRole.Detail) }
                                                    },
                                                    onFolderClick = { path -> viewModel.loadFolder(path) },
                                                    onNavigateUp = { viewModel.navigateUp() },
                                                    onMenuClick = { },
                                                    onRefresh = { viewModel.refreshCurrentFolder() },
                                                    onCreateFile = { viewModel.openCreateFileDialog() },
                                                    onRenameFile = { newName, file -> viewModel.renameFile(newName, file) },
                                                    onDeleteFile = { file ->
                                                        // Delete the swiped file directly — do NOT open it first.
                                                        viewModel.requestDeleteFile(file)
                                                    },
                                                    onAnalyzeFolder = { viewModel.analyzeCurrentFolder() }
                                                )
                                            }
                                            
                                            // Resizable Handle (Only Large Screen)
                                            if (isLargeScreen && !uiState.isFocusMode) {
                                                cloud.wafflecommons.pixelbrainreader.ui.components.SplitPaneHandle(
                                                    onDrag = { delta ->
                                                        val newWidth = uiState.listPaneWidth + delta
                                                        viewModel.updateListPaneWidth(newWidth.coerceIn(200f, 600f))
                                                    }
                                                )
                                            }
                                        }
                                    }
                                },
                                detailPane = {
                                    AnimatedPane {
                                    if (uiState.selectedFileName != null) {
                                        FileDetailPane(
                                            content = uiState.unsavedContent
                                                ?: uiState.selectedFileContent,
                                            onContentChange = { viewModel.onContentChanged(it) },
                                            fileName = uiState.selectedFileName,
                                            isLoading = uiState.isLoading,
                                            isRefreshing = uiState.isRefreshing,
                                            statusText = repoSyncStatus,
                                            onRefresh = { viewModel.refreshCurrentFile() },
                                            isExpandedScreen = isLargeScreen,
                                            isEditing = uiState.isEditing,
                                            hasUnsavedChanges = uiState.hasUnsavedChanges,
                                            saveState = uiState.saveState,
                                            onWikiLinkClick = { target -> viewModel.onWikiLinkClick(target) },
                                            onCreateNew = { viewModel.createNewFile() },
                                            moodViewModel = moodViewModel,
                                            onSave = { viewModel.saveFile() }
                                        )
                                    } else {
                                        cloud.wafflecommons.pixelbrainreader.ui.components.WelcomeScreen()
                                    }
                                    }
                                }
                            )
                        }

                        // Persistent Sync Indicator (Overlay)
                        androidx.compose.animation.AnimatedVisibility(
                            visible = uiState.isSyncing,
                            enter = androidx.compose.animation.expandVertically(),
                            exit = androidx.compose.animation.shrinkVertically(),
                            modifier = Modifier.align(androidx.compose.ui.Alignment.TopCenter)
                        ) {
                            androidx.compose.material3.LinearProgressIndicator(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(4.dp),
                                color = MaterialTheme.colorScheme.tertiary,
                                trackColor = Color.Transparent
                            )
                        }
                    }
                }
            }

            composable(Screen.Chat) {
                val snackbarHostState = remember { SnackbarHostState() }
                
                LaunchedEffect(uiState.userMessage) {
                    uiState.userMessage?.let { message ->
                        snackbarHostState.showSnackbar(message)
                        viewModel.userMessageShown()
                    }
                }
                
                Scaffold(
                    snackbarHost = {
                        // Lift above the floating ExpressiveNavBar, which would otherwise cover it.
                        SnackbarHost(
                            hostState = snackbarHostState,
                            modifier = Modifier.padding(bottom = NavBarClearance)
                        )
                    },
                    contentWindowInsets = WindowInsets(0,0,0,0) // ChatPanel handles its own insets
                ) { padding ->
                    Box(modifier = Modifier.padding(padding)) {
                         cloud.wafflecommons.pixelbrainreader.ui.ai.ChatPanel(
                            onInsertContent = { text ->
                                android.util.Log.d("PixelBrain", "ChatPanel onInsertContent triggered. Saving to Inbox.")
                                viewModel.saveChatToInbox(text)
                            },
                            onSourceClick = { path -> viewModel.openVaultFile(path) }
                        )
                    }
                }
            }

            composable(Screen.Settings) {
                SettingsScreen(
                    onBack = { navController.popBackStack() },
                    onNavigateToHabitConfig = { navController.navigate("habit_config") },
                    onNavigateToHomeConfig = { navController.navigate(Screen.ROUTE_HOME_CONFIG) },
                    onNavigateToReminders = { navController.navigate("reminders") },
                    onNavigateToNavBarReorder = { navController.navigate("navbar_reorder") },
                    onNavigateToMoodTags = { navController.navigate("mood_tags") }
                )
            }

            composable("reminders") {
                cloud.wafflecommons.pixelbrainreader.ui.settings.RemindersScreen(
                    onBack = { navController.popBackStack() }
                )
            }

            composable("habit_config") {
                cloud.wafflecommons.pixelbrainreader.ui.settings.HabitConfigScreen(
                    onNavigateBack = { navController.popBackStack() }
                )
            }

            composable("navbar_reorder") {
                cloud.wafflecommons.pixelbrainreader.ui.settings.NavBarReorderScreen(
                    order = navBarOrder,
                    onOrderChange = { viewModel.setNavBarOrder(it) },
                    onNavigateBack = { navController.popBackStack() }
                )
            }

            composable("mood_tags") {
                cloud.wafflecommons.pixelbrainreader.ui.settings.MoodTagSettingsScreen(
                    onNavigateBack = { navController.popBackStack() }
                )
            }


            composable(Screen.HomeOS) {
                cloud.wafflecommons.pixelbrainreader.ui.homeos.ChoreDashboardScreen(
                    onNavigateToStats = {
                        if (navController.previousBackStackEntry?.destination?.route == Screen.Stats) navController.popBackStack()
                        else navController.navigate(Screen.Stats)
                    }
                )
            }

            composable(Screen.Import) {
                // Intercept System Back to clear state
                BackHandler {
                    viewModel.dismissImport()
                    navController.popBackStack()
                }

                if (uiState.importState != null) {
                    cloud.wafflecommons.pixelbrainreader.ui.components.ImportScreen(
                        initialTitle = uiState.importState!!.title,
                        initialContent = uiState.importState!!.content,
                        onDismiss = { 
                            viewModel.dismissImport()
                            navController.popBackStack()
                        },
                        onSave = { name, folder, content -> 
                            viewModel.confirmImport(name, folder, content)
                            navController.popBackStack()
                        }
                    )
                } else {
                    // Fallback if state lost
                    LaunchedEffect(Unit) { navController.popBackStack() }
                }
            }

            composable(Screen.DailyNote) {
                val dailyViewModel: cloud.wafflecommons.pixelbrainreader.ui.daily.DailyNoteViewModel = hiltViewModel()
                
                // Refresh Trigger: When Sync finishes, reload Daily View
                // We use isSyncing going from True -> False
                val isSyncing = uiState.isSyncing
                val currentIsSyncing by androidx.compose.runtime.rememberUpdatedState(isSyncing)
                
                LaunchedEffect(isSyncing) {
                     if (!isSyncing) { 
                         // Sync Finished. Room Flow naturally updates UI.
                         moodViewModel.refreshData() 
                     }
                }
            
                cloud.wafflecommons.pixelbrainreader.ui.daily.DailyNoteScreen(
                    onCheckInClicked = { showMoodSheet = true },
                    viewModel = dailyViewModel,
                    onNavigateToSettings = { navController.navigate(Screen.Settings) }
                )
            }
            
            composable("habits") {
                cloud.wafflecommons.pixelbrainreader.ui.lifeos.HabitDashboardScreen(
                    onNavigateBack = { navController.popBackStack() },
                    onNavigateToStats = {
                        if (navController.previousBackStackEntry?.destination?.route == Screen.Stats) navController.popBackStack()
                        else navController.navigate(Screen.Stats)
                    }
                )
            }
            
            composable(Screen.MoodTracker) {
                cloud.wafflecommons.pixelbrainreader.ui.mood.MoodHistoryScreen()
            }
            
            composable(Screen.Stats) {
                 cloud.wafflecommons.pixelbrainreader.ui.lifestats.LifeStatsScreen(
                     onNavigateBack = { navController.popBackStack() },
                     onNavigateToHabits = {
                         if (navController.previousBackStackEntry?.destination?.route == "habits") navController.popBackStack()
                         else navController.navigate("habits")
                     },
                     onNavigateToChores = {
                         if (navController.previousBackStackEntry?.destination?.route == Screen.HomeOS) navController.popBackStack()
                         else navController.navigate(Screen.HomeOS)
                     }
                 )
            }
            

            composable(Screen.ROUTE_HOME_CONFIG) {
                cloud.wafflecommons.pixelbrainreader.ui.homeconfig.HomeConfigScreen(
                    onNavigateBack = { navController.popBackStack() }
                )
            }
        }

        // --- Global Effects Overlay ---
        if (partyState.isNotEmpty()) {
                KonfettiView(
                parties = partyState,
                modifier = Modifier.fillMaxSize()
            )
        }
    }

        // --- Floating nav bar, overlaid on the content (Finance-style) ---
        // Hidden while the keyboard is open (navLayoutType == None) so it doesn't
        // float above the IME on text surfaces.
        if (navLayoutType != androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType.None) {
            ExpressiveNavBar(
                currentRoute = currentRoute,
                isViewingDailyNote = isViewingDailyNote,
                isLargeScreen = isLargeScreen,
                order = navBarOrder,
                onSelect = { route ->
                    navController.navigate(route) {
                        popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
                modifier = Modifier.align(Alignment.BottomCenter)
            )
        }
    }
}


