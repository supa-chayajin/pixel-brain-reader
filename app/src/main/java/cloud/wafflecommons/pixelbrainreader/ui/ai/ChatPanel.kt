@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package cloud.wafflecommons.pixelbrainreader.ui.ai

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.SaveAlt
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.KeyboardDoubleArrowDown
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.*
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LoadingIndicator
import cloud.wafflecommons.pixelbrainreader.ui.components.connectedShapes
import cloud.wafflecommons.pixelbrainreader.ui.components.ConnectedButtonRow
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.intl.Locale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cloud.wafflecommons.pixelbrainreader.data.ai.ChatScope
import cloud.wafflecommons.pixelbrainreader.data.ai.NanoState
import cloud.wafflecommons.pixelbrainreader.data.ai.PromptPreset
import cloud.wafflecommons.pixelbrainreader.data.ai.PromptPresetFormat
import cloud.wafflecommons.pixelbrainreader.data.ai.ThenVsNow
import cloud.wafflecommons.pixelbrainreader.ui.components.CortexIconButton
import cloud.wafflecommons.pixelbrainreader.ui.components.rememberCopyToClipboard
import cloud.wafflecommons.pixelbrainreader.ui.main.MarkwonContent
import cloud.wafflecommons.pixelbrainreader.ui.theme.NavBarClearance
import cloud.wafflecommons.pixelbrainreader.ui.utils.centeredMaxWidth
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * The chat (12.20's revamp, the owner's C1–C4): questions and answers both in bubbles, in the
 * active persona's colors (Cortex primary, the preset secondary, Spark tertiary), with Material 3
 * Expressive's loading indicator while the model thinks and its spring motion for new bubbles.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ChatPanel(
    modifier: Modifier = Modifier,
    viewModel: ChatViewModel = hiltViewModel(),
    onInsertContent: (text: String, sources: List<String>) -> Unit = { _, _ -> },
    onSourceClick: (String) -> Unit = {}
) {
    // rememberSaveable so an unsent draft survives process death (config changes are
    // already handled without recreation via the Activity's configChanges).
    var textState by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue("")) }
    val listState = rememberLazyListState()
    val haptic = LocalHapticFeedback.current

    val currentMode by viewModel.currentMode.collectAsStateWithLifecycle()
    val presets by viewModel.presets.collectAsStateWithLifecycle()
    val activePreset by viewModel.activePreset.collectAsStateWithLifecycle()
    val persistedHistory by viewModel.chatHistory.collectAsStateWithLifecycle()
    val streaming by viewModel.streamingMessage.collectAsStateWithLifecycle()

    // The streaming bubble joins once Nano's first words arrive; until then the thinking bubble
    // stands in for it. The saved answer keeps its id: once the history holds it, it replaces it.
    val displayedMessages = remember(persistedHistory, streaming) {
        val live = streaming?.takeIf { live -> live.content.isNotBlank() && persistedHistory.none { it.id == live.id } }
        if (live != null) persistedHistory + live else persistedHistory
    }
    val scope by viewModel.scope.collectAsStateWithLifecycle()
    val grounded = currentMode == ChatMode.ORACLE || (currentMode == ChatMode.PRESET && activePreset.useNotes)
    // The scope a grounded question answers from (K2); the other surfaces ignore it.
    val activeScope = scope?.takeIf { grounded }
    val thinking = viewModel.loadingStage != null && streaming?.content.isNullOrBlank()

    // Confirmation state for the top-bar "clear conversation" action.
    var showClearConfirm by remember { mutableStateOf(false) }

    // Follow the conversation: a new bubble, or the thinking bubble, scrolls into view.
    val itemCount = displayedMessages.size + if (thinking) 1 else 0
    LaunchedEffect(itemCount) {
        if (itemCount > 0) listState.animateScrollToItem(itemCount - 1)
    }

    val persona = animatedPersonaColors(currentMode)
    val nanoState by viewModel.nanoState.collectAsStateWithLifecycle()

    // A time-travel answer's note, then against now (K4).
    viewModel.thenVsNow?.let { view ->
        ThenVsNowSheet(
            view = view,
            onDismiss = viewModel::closeThenVsNow,
            onOpenNote = { path ->
                viewModel.closeThenVsNow()
                onSourceClick(path)
            }
        )
    }

    if (showClearConfirm) {
        ClearChatDialog(
            onConfirm = {
                viewModel.resetChat()
                showClearConfirm = false
            },
            onDismiss = { showClearConfirm = false }
        )
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            Column(modifier = Modifier.background(MaterialTheme.colorScheme.surface)) {
                cloud.wafflecommons.pixelbrainreader.ui.components.CortexTopAppBar(
                    title = "Neural Interface",
                emblemKey = "Chat",
                    icon = Icons.Rounded.Psychology,
                    actions = {
                        CortexIconButton(
                            onClick = { showClearConfirm = true },
                            enabled = displayedMessages.isNotEmpty()
                        ) {
                            Icon(
                                Icons.Rounded.DeleteSweep,
                                contentDescription = "Clear conversation"
                            )
                        }
                    }
                )

                // --- MODE SWITCHER ---
                BrainModeSwitch(
                    currentMode = currentMode,
                    presets = presets,
                    activePreset = activePreset,
                    onModeChanged = { viewModel.setMode(it) },
                    onPresetSelected = viewModel::selectPreset,
                    onPresetMenuOpened = viewModel::reloadPresets,
                    onEditPresets = { viewModel.editPresets(onSourceClick) },
                    activeColor = persona.accent,
                    activeContentColor = persona.onAccent
                )
                Spacer(modifier = Modifier.height(8.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            }
        },
        containerColor = Color.Transparent,
        contentWindowInsets = ScaffoldDefaults.contentWindowInsets.exclude(WindowInsets.ime),
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                // Consume the scaffold insets so imePadding() adds only (ime − navBar)
                // rather than stacking the nav-bar inset again → no gap above keyboard.
                .consumeWindowInsets(innerPadding)
                .imePadding()
                // One conversation column on a large screen: messages and input share its width.
                .centeredMaxWidth()
        ) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                if (displayedMessages.isEmpty() && !thinking) {
                    EmptyStatePlaceholder(mode = currentMode, preset = activePreset, persona = persona)
                } else {
                    val runs = remember(displayedMessages) { BubbleRun.of(displayedMessages.map { it.isUser }) }
                    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                        val questionMaxWidth = maxWidth * 0.82f
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                            contentPadding = PaddingValues(top = 16.dp, bottom = 24.dp)
                        ) {
                            itemsIndexed(displayedMessages, key = { _, message -> message.id }) { index, message ->
                                val run = runs.getOrElse(index) { BubbleRun.ALONE }
                                val gap = when {
                                    index == 0 -> 0.dp
                                    run.continuesAbove -> 4.dp
                                    else -> 18.dp
                                }
                                Box(modifier = Modifier.animateItem().padding(top = gap)) {
                                    if (message.isUser) {
                                        QuestionBubble(message = message, run = run, persona = persona, maxWidth = questionMaxWidth)
                                    } else {
                                        AnswerBubble(
                                            message = message,
                                            run = run,
                                            persona = persona,
                                            mode = currentMode,
                                            preset = activePreset,
                                            onInsert = onInsertContent,
                                            onSourceClick = onSourceClick,
                                            onThenVsNow = viewModel::openThenVsNow,
                                            onContinue = if (message.id == viewModel.continuableAnswerId) viewModel::continueAnswer else null
                                        )
                                    }
                                }
                            }
                            if (thinking) {
                                item(key = "thinking") {
                                    Box(modifier = Modifier.animateItem().padding(top = if (displayedMessages.isEmpty()) 0.dp else 18.dp)) {
                                        ThinkingBubble(
                                            stage = viewModel.loadingStage,
                                            persona = persona,
                                            mode = currentMode,
                                            preset = activePreset
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Input Area — lift above the floating nav bar, except while the keyboard is open
            // (the bar hides on IME, mirroring MainScreen's navLayoutType gating).
            Column(modifier = Modifier.padding(bottom = if (WindowInsets.isImeVisible) 0.dp else NavBarClearance)) {
                // Nano availability indicator — proactive, always visible
                NanoStatusIndicator(
                    state = nanoState,
                    modifier = Modifier.padding(start = 24.dp, top = 4.dp, bottom = 2.dp)
                )

                if (activeScope != null) {
                    ScopeChip(
                        scope = activeScope,
                        persona = persona,
                        onClear = viewModel::clearScope,
                        modifier = Modifier.padding(start = 16.dp, top = 6.dp)
                    )
                }

                StealthInputBar(
                    textState = textState,
                    onTextChange = { textState = it },
                    onSend = {
                        // Fires for BOTH the send IconButton and the IME Send action,
                        // since both route through this shared callback.
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        if (textState.text.isNotBlank()) {
                            viewModel.sendMessage(textState.text)
                            textState = TextFieldValue("")
                        }
                    },
                    isLoading = viewModel.loadingStage != null,
                    hint = when {
                        activeScope != null -> "Ask about ${activeScope.label}..."
                        currentMode == ChatMode.ORACLE -> "Ask your Second Brain..."
                        currentMode == ChatMode.PRESET -> "Message ${PromptPresetFormat.plainTitle(activePreset.title)}..."
                        else -> "Spark a creative idea..."
                    },
                    accentColor = persona.accent,
                    accentContentColor = persona.onAccent
                )
            }
        }
    }
}

// --- THE PERSONA (C2) ---

/**
 * A persona's colors, from the dynamic scheme so every text keeps the scheme's contrast: its accent
 * pair, its container pair (your questions, its avatar) and the tinted surface its answers sit on.
 */
@Immutable
data class PersonaColors(
    val accent: Color,
    val onAccent: Color,
    val container: Color,
    val onContainer: Color,
    val answer: Color
)

@Composable
private fun animatedPersonaColors(mode: ChatMode): PersonaColors {
    val scheme = MaterialTheme.colorScheme
    val target = when (mode) {
        ChatMode.ORACLE -> PersonaColors(scheme.primary, scheme.onPrimary, scheme.primaryContainer, scheme.onPrimaryContainer, scheme.primary)
        ChatMode.PRESET -> PersonaColors(scheme.secondary, scheme.onSecondary, scheme.secondaryContainer, scheme.onSecondaryContainer, scheme.secondary)
        ChatMode.SCRIBE -> PersonaColors(scheme.tertiary, scheme.onTertiary, scheme.tertiaryContainer, scheme.onTertiaryContainer, scheme.tertiary)
    }
    val accent by animateColorAsState(target.accent, label = "personaAccent")
    val onAccent by animateColorAsState(target.onAccent, label = "personaOnAccent")
    val container by animateColorAsState(target.container, label = "personaContainer")
    val onContainer by animateColorAsState(target.onContainer, label = "personaOnContainer")
    // A light wash of the accent over the low container surface: the answer reads as the persona's
    // without leaving the surface roles, so onSurface text keeps its contrast.
    val answer = accent.copy(alpha = ANSWER_TINT).compositeOver(scheme.surfaceContainerLow)
    return PersonaColors(accent, onAccent, container, onContainer, answer)
}

private fun personaIcon(mode: ChatMode, preset: PromptPreset): ImageVector = when (mode) {
    ChatMode.ORACLE -> Icons.Rounded.Psychology
    ChatMode.PRESET -> if (preset.id == "boardroom") Icons.Rounded.Groups else Icons.Rounded.Tune
    ChatMode.SCRIBE -> Icons.Rounded.AutoAwesome
}

private fun personaName(mode: ChatMode, preset: PromptPreset): String = when (mode) {
    ChatMode.ORACLE -> "Cortex"
    // The presets' tab, whichever preset is chosen (D9): the chosen one shows in its menu.
    ChatMode.PRESET -> "Council"
    ChatMode.SCRIBE -> "Spark"
}

@Composable
private fun PersonaAvatar(
    mode: ChatMode,
    preset: PromptPreset,
    persona: PersonaColors,
    size: Dp,
    shape: Shape = CircleShape
) {
    Surface(
        shape = shape,
        color = persona.container,
        contentColor = persona.onContainer,
        modifier = Modifier.size(size)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(personaIcon(mode, preset), contentDescription = null, modifier = Modifier.size(size * 0.55f))
        }
    }
}

@Composable
private fun PersonaHeader(mode: ChatMode, preset: PromptPreset, persona: PersonaColors) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 6.dp)) {
        PersonaAvatar(mode = mode, preset = preset, persona = persona, size = 28.dp)
        Spacer(Modifier.width(8.dp))
        Text(
            text = personaName(mode, preset),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// --- THE BUBBLES (C1, C3) ---

private val BUBBLE_LARGE = 24.dp
private val BUBBLE_SMALL = 6.dp
private const val ANSWER_TINT = 0.10f

/** A saved message younger than this springs in; older ones (the history on opening) don't. */
private const val FRESH_MS = 8_000L

/**
 * Large corners, a small one on the sender's side at the bottom, and the sender-side top corner
 * tightened under another bubble from the same side, like Google Messages.
 */
private fun bubbleShape(fromUser: Boolean, run: BubbleRun): Shape {
    val senderTop = if (run.continuesAbove) BUBBLE_SMALL else BUBBLE_LARGE
    return if (fromUser) {
        RoundedCornerShape(topStart = BUBBLE_LARGE, topEnd = senderTop, bottomEnd = BUBBLE_SMALL, bottomStart = BUBBLE_LARGE)
    } else {
        RoundedCornerShape(topStart = senderTop, topEnd = BUBBLE_LARGE, bottomEnd = BUBBLE_LARGE, bottomStart = BUBBLE_SMALL)
    }
}

/**
 * A new bubble arrives on the Expressive spatial spring: it grows from its sender's corner and
 * rises into place. [fresh] false (the history on opening) shows it at once.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SpringIn(fresh: Boolean, fromEnd: Boolean, content: @Composable () -> Unit) {
    val progress = remember { Animatable(if (fresh) 0f else 1f) }
    val spec = MaterialTheme.motionScheme.defaultSpatialSpec<Float>()
    LaunchedEffect(Unit) {
        if (progress.value < 1f) progress.animateTo(1f, spec)
    }
    Box(
        modifier = Modifier.graphicsLayer {
            val p = progress.value
            alpha = p.coerceIn(0f, 1f)
            val scale = 0.88f + 0.12f * p
            scaleX = scale
            scaleY = scale
            translationY = (1f - p) * 16.dp.toPx()
            transformOrigin = TransformOrigin(if (fromEnd) 1f else 0f, 1f)
        }
    ) {
        content()
    }
}

@Composable
private fun QuestionBubble(message: ChatMessage, run: BubbleRun, persona: PersonaColors, maxWidth: Dp) {
    val fresh = remember(message.id) { message.isFresh() }
    // The note or folder the question was asked about (K2, D7), kept in its sources.
    val askedIn = remember(message.sources) { ChatScope.decode(message.sources) }
    Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
        SpringIn(fresh = fresh, fromEnd = true) {
            Surface(
                shape = bubbleShape(fromUser = true, run = run),
                color = persona.container,
                contentColor = persona.onContainer,
                modifier = Modifier.widthIn(max = maxWidth)
            ) {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    if (askedIn != null) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 4.dp)) {
                            Icon(
                                if (askedIn.isFolder) Icons.Outlined.Folder else Icons.Outlined.Description,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = askedIn.label,
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    // A question typed with a trailing newline shows no empty line.
                    Text(text = message.content.trimEnd(), style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
        if (!run.continuesBelow) MessageTime(message)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AnswerBubble(
    message: ChatMessage,
    run: BubbleRun,
    persona: PersonaColors,
    mode: ChatMode,
    preset: PromptPreset,
    onInsert: (text: String, sources: List<String>) -> Unit,
    onSourceClick: (String) -> Unit,
    onThenVsNow: (path: String, day: java.time.LocalDate) -> Unit,
    onContinue: (() -> Unit)?
) {
    // A time-travel answer (K4) keeps the day it looked back to among its sources.
    val lookedBack = remember(message.sources) { ThenVsNow.dayOf(message.sources) }
    val notes = remember(message.sources) { ThenVsNow.notesOf(message.sources) }
    // An answer that is only a warning (the model missing, a timeout) sits in the error colors.
    val problem = message.content.startsWith("⚠️") && notes.isEmpty()
    val container = if (problem) MaterialTheme.colorScheme.errorContainer else persona.answer
    val content = if (problem) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurface
    Column(modifier = Modifier.fillMaxWidth().padding(end = 24.dp)) {
        if (!run.continuesAbove) PersonaHeader(mode = mode, preset = preset, persona = persona)
        // The streaming bubble springs in when Nano's first words arrive; a saved answer replaces
        // it in place.
        SpringIn(fresh = message.isStreaming, fromEnd = false) {
            Surface(
                shape = bubbleShape(fromUser = false, run = run),
                color = container,
                contentColor = content,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 6.dp)) {
                    if (message.isStreaming) {
                        // Plain text while streaming: re-parsing Markdown at every token janks, and
                        // half-written markup shows half-formatted. Markdown on the final paint.
                        Text(text = message.content, style = MaterialTheme.typography.bodyLarge)
                    } else {
                        MarkwonContent(
                            content = message.content,
                            onWikiLinkClick = { target -> onSourceClick(target) }
                        )
                    }

                    if (notes.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(10.dp))
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f))
                        Spacer(modifier = Modifier.height(4.dp))
                        if (lookedBack != null) {
                            Text(
                                text = "Then and now: tap a note to compare it with " + shortDate(lookedBack),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            notes.forEach { source ->
                                SourceChip(
                                    source = source,
                                    persona = persona,
                                    onClick = { if (lookedBack != null) onThenVsNow(source, lookedBack) else onSourceClick(source) }
                                )
                            }
                        }
                    }

                    if (!message.isStreaming && message.content.isNotBlank() && !problem) {
                        AnswerActions(
                            message = message,
                            persona = persona,
                            onSave = { onInsert(message.content, notes) },
                            onContinue = onContinue
                        )
                    } else {
                        Spacer(Modifier.height(6.dp))
                    }
                }
            }
        }
        if (!run.continuesBelow && !message.isStreaming) MessageTime(message)
    }
}

/** A source as its note's title and folder ("README · Garden Planner"); a tap opens the note. */
@Composable
private fun SourceChip(source: String, persona: PersonaColors, onClick: () -> Unit) {
    AssistChip(
        onClick = onClick,
        label = {
            Text(
                text = SourceLabel.of(source),
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 240.dp)
            )
        },
        leadingIcon = {
            Icon(Icons.Outlined.Description, contentDescription = null, modifier = Modifier.size(AssistChipDefaults.IconSize))
        },
        colors = AssistChipDefaults.assistChipColors(
            containerColor = MaterialTheme.colorScheme.surface,
            labelColor = MaterialTheme.colorScheme.onSurface,
            leadingIconContentColor = persona.accent
        ),
        border = AssistChipDefaults.assistChipBorder(enabled = true, borderColor = MaterialTheme.colorScheme.outlineVariant)
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AnswerActions(
    message: ChatMessage,
    persona: PersonaColors,
    onSave: () -> Unit,
    onContinue: (() -> Unit)?
) {
    val copy = rememberCopyToClipboard()
    val colors = ButtonDefaults.textButtonColors(contentColor = persona.accent)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(top = 2.dp)) {
        // The answer stopped at the model's output cap: one tap asks for the rest (K2).
        if (onContinue != null) {
            FilledTonalButton(
                onClick = onContinue, shapes = ButtonDefaults.shapes(),
                colors = ButtonDefaults.filledTonalButtonColors(containerColor = persona.container, contentColor = persona.onContainer)
            ) {
                Icon(Icons.Rounded.KeyboardDoubleArrowDown, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                Text("Continue")
            }
        }
        TextButton(onClick = { copy(message.content) }, shapes = ButtonDefaults.shapes(), colors = colors) {
            Icon(Icons.Outlined.ContentCopy, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
            Spacer(Modifier.width(ButtonDefaults.IconSpacing))
            Text("Copy")
        }
        TextButton(onClick = onSave, shapes = ButtonDefaults.shapes(), colors = colors) {
            Icon(Icons.Outlined.SaveAlt, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
            Spacer(Modifier.width(ButtonDefaults.IconSpacing))
            Text("Save to Inbox")
        }
    }
}

/** A day as the chat shows it, in the device's medium date format. */
@Composable
private fun shortDate(day: java.time.LocalDate): String {
    val locale = Locale.current.platformLocale
    return remember(day, locale) {
        DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale).format(day)
    }
}

/** A time-travel answer's note as it stood then against now, word by word (K4). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ThenVsNowSheet(view: ThenVsNowView, onDismiss: () -> Unit, onOpenNote: (String) -> Unit) {
    val sheetState = rememberBottomSheetState(
        initialValue = SheetValue.Hidden,
        enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded)
    )
    val day = shortDate(view.day)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = MaterialTheme.colorScheme.surface) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.85f)
                .padding(horizontal = 20.dp, vertical = 8.dp)
        ) {
            Text(
                text = view.path.substringAfterLast('/').removeSuffix(".md"),
                style = MaterialTheme.typography.titleLarge
            )
            Text(
                text = if (view.existedThen) "$day, then today" else "Not written yet on $day: all of it is new",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))
            if (view.diff.rows.isEmpty()) {
                Text(
                    "Unchanged since then.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
            } else {
                cloud.wafflecommons.pixelbrainreader.ui.components.NoteDiffList(
                    rows = view.diff.rows,
                    words = view.diff.words,
                    modifier = Modifier.weight(1f)
                )
            }
            TextButton(onClick = { onOpenNote(view.path) }, shapes = ButtonDefaults.shapes(), modifier = Modifier.align(Alignment.End)) {
                Text("Open the note")
            }
        }
    }
}

/** The note or folder grounded questions answer from (K2); a tap goes back to the whole vault. */
@Composable
private fun ScopeChip(scope: ChatScope, persona: PersonaColors, onClear: () -> Unit, modifier: Modifier = Modifier) {
    InputChip(
        selected = true,
        onClick = onClear,
        label = {
            Text(
                text = "Asking: ${scope.label}",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 280.dp)
            )
        },
        leadingIcon = {
            Icon(
                if (scope.isFolder) Icons.Outlined.Folder else Icons.Outlined.Description,
                contentDescription = null,
                modifier = Modifier.size(InputChipDefaults.IconSize)
            )
        },
        trailingIcon = {
            Icon(Icons.Rounded.Close, contentDescription = "Ask the whole vault", modifier = Modifier.size(InputChipDefaults.IconSize))
        },
        colors = InputChipDefaults.inputChipColors(
            selectedContainerColor = persona.container,
            selectedLabelColor = persona.onContainer,
            selectedLeadingIconColor = persona.onContainer,
            selectedTrailingIconColor = persona.onContainer
        ),
        modifier = modifier
    )
}

/** Until Nano's first words: Material 3 Expressive's loading indicator and what is happening. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ThinkingBubble(stage: String?, persona: PersonaColors, mode: ChatMode, preset: PromptPreset) {
    Column(modifier = Modifier.fillMaxWidth().padding(end = 24.dp)) {
        PersonaHeader(mode = mode, preset = preset, persona = persona)
        SpringIn(fresh = true, fromEnd = false) {
            Surface(
                shape = bubbleShape(fromUser = false, run = BubbleRun.ALONE),
                color = persona.answer,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant
            ) {
                Row(
                    modifier = Modifier.padding(start = 10.dp, end = 18.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    LoadingIndicator(modifier = Modifier.size(40.dp), color = persona.accent)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        // The stage's leading emoji goes: the indicator already says it.
                        text = stage?.trimStart { !it.isLetterOrDigit() }?.ifBlank { null } ?: "Thinking…",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    }
}

/** The time under the last bubble of a run, in the device's short time format. */
@Composable
private fun MessageTime(message: ChatMessage) {
    val timestamp = message.timestamp ?: return
    val locale = Locale.current.platformLocale
    val text = remember(timestamp, locale) {
        DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
            .withLocale(locale)
            .format(Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()))
    }
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp, start = 6.dp, end = 6.dp)
    )
}

private fun ChatMessage.isFresh(now: Long = System.currentTimeMillis()): Boolean =
    timestamp != null && now - timestamp in 0..FRESH_MS

// --- COMPONENTS ---

/**
 * Cortex, the active preset and Spark. Tapping the preset segment while it is selected opens the
 * preset menu (re-read from the vault), with a way to edit the presets file.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrainModeSwitch(
    currentMode: ChatMode,
    presets: List<PromptPreset>,
    activePreset: PromptPreset,
    onModeChanged: (ChatMode) -> Unit,
    onPresetSelected: (String) -> Unit,
    onPresetMenuOpened: () -> Unit,
    onEditPresets: () -> Unit,
    activeColor: Color,
    activeContentColor: Color
) {
    var presetMenu by remember { mutableStateOf(false) }
    val modes = listOf(
        Triple(ChatMode.ORACLE, Icons.Rounded.Psychology, "Cortex"),
        Triple(ChatMode.PRESET, if (activePreset.id == "boardroom") Icons.Rounded.Groups else Icons.Rounded.Tune, "Council"),
        Triple(ChatMode.SCRIBE, Icons.Rounded.AutoAwesome, "Spark")
    )
    Box {
    ConnectedButtonRow(
        modifier = Modifier
            // Three segments read as one control up to this width, centred beyond it.
            .centeredMaxWidth(640.dp)
            .padding(horizontal = 24.dp, vertical = 8.dp)
    ) {
        modes.forEachIndexed { index, (mode, icon, label) ->
            ToggleButton(
                checked = currentMode == mode,
                onCheckedChange = {
                    when {
                        currentMode != mode -> onModeChanged(mode)
                        mode == ChatMode.PRESET -> {
                            onPresetMenuOpened()
                            presetMenu = true
                        }
                    }
                },
                shapes = connectedShapes(index, modes.size),
                colors = ToggleButtonDefaults.toggleButtonColors(
                    checkedContainerColor = activeColor,
                    checkedContentColor = activeContentColor
                ),
                modifier = Modifier.weight(1f)
            ) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                                Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
    DropdownMenu(expanded = presetMenu, onDismissRequest = { presetMenu = false }) {
        presets.forEach { preset ->
            DropdownMenuItem(
                text = {
                    Column {
                        Text(PromptPresetFormat.plainTitle(preset.title), style = MaterialTheme.typography.bodyLarge)
                        if (preset.description.isNotBlank()) {
                            Text(preset.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                },
                onClick = {
                    presetMenu = false
                    onPresetSelected(preset.id)
                }
            )
        }
        HorizontalDivider()
        DropdownMenuItem(
            text = { Text("Edit presets") },
            leadingIcon = { Icon(Icons.Rounded.Tune, contentDescription = null) },
            onClick = {
                presetMenu = false
                onEditPresets()
            }
        )
    }
    }
}

@Composable
fun StealthInputBar(
    textState: TextFieldValue,
    onTextChange: (TextFieldValue) -> Unit,
    onSend: () -> Unit,
    isLoading: Boolean,
    hint: String,
    accentColor: Color,
    accentContentColor: Color
) {
    val context = LocalContext.current
    val isEnabled = textState.text.isNotBlank() && !isLoading

    // Dictation into the input, transcribed on the device (fr-FR).
    val currentText by rememberUpdatedState(textState.text)
    val dictation = cloud.wafflecommons.pixelbrainreader.ui.utils.rememberOnDeviceDictation(
        allowNetworkFallback = true,
        onResult = { spokenText ->
            val newText = if (currentText.isBlank()) spokenText else "$currentText $spokenText"
            onTextChange(TextFieldValue(newText, TextRange(newText.length)))
        },
        onMessage = { message -> android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_LONG).show() }
    )
    cloud.wafflecommons.pixelbrainreader.ui.utils.DictationListeningDialog(dictation)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            border = BorderStroke(1.dp, if (textState.text.isNotEmpty()) accentColor else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        ) {
            Row(
                modifier = Modifier.padding(start = 20.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(modifier = Modifier.weight(1f)) {
                    if (textState.text.isEmpty()) {
                        Text(
                            text = hint,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    BasicTextField(
                        value = textState,
                        onValueChange = onTextChange,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                        cursorBrush = SolidColor(accentColor),
                        maxLines = 4,
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { if (isEnabled) onSend() })
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                FilledIconButton(
                    onClick = {
                        if (textState.text.isBlank()) {
                            dictation.start()
                        } else {
                            onSend()
                        }
                    }, shapes = IconButtonDefaults.shapes(),
                    enabled = !isLoading,
                    modifier = Modifier.size(48.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = if (isEnabled) accentColor else MaterialTheme.colorScheme.surfaceContainerHighest,
                        contentColor = if (isEnabled) accentContentColor else MaterialTheme.colorScheme.onSurfaceVariant,
                        disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        disabledContentColor = accentColor
                    )
                ) {
                    if (isLoading) {
                        LoadingIndicator(modifier = Modifier.size(20.dp), color = accentColor)
                    } else {
                        Icon(
                            if (textState.text.isBlank()) Icons.Rounded.Mic else Icons.AutoMirrored.Rounded.Send,
                            contentDescription = if (textState.text.isBlank()) "Dictate" else "Send"
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun NanoStatusIndicator(state: NanoState, modifier: Modifier = Modifier) {
    val (icon, tint, label) = when (state) {
        is NanoState.Ready -> Triple(
            Icons.Rounded.Bolt,
            MaterialTheme.colorScheme.primary,
            "Gemini Nano · on-device"
        )
        is NanoState.Downloading -> Triple(
            Icons.Rounded.Bolt,
            MaterialTheme.colorScheme.tertiary,
            "Gemini Nano · downloading…"
        )
        NanoState.NotDownloaded -> Triple(
            Icons.Rounded.CloudOff,
            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            "Gemini Nano · download in Settings"
        )
        is NanoState.Checking, NanoState.Unknown -> Triple(
            Icons.Rounded.Bolt,
            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            "Checking on-device AI…"
        )
        is NanoState.Unavailable -> Triple(
            Icons.Rounded.CloudOff,
            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            "Nano unavailable · ${state.reason}"
        )
        is NanoState.Error -> Triple(
            Icons.Rounded.CloudOff,
            MaterialTheme.colorScheme.error.copy(alpha = 0.7f),
            "Nano error · ${state.cause.localizedMessage ?: state.cause.message ?: state.cause::class.java.simpleName}"
        )
    }

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = tint,
            modifier = Modifier.size(14.dp)
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = tint,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun ClearChatDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val haptic = LocalHapticFeedback.current
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Rounded.DeleteSweep,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error
            )
        },
        title = { Text("Clear this conversation?") },
        text = {
            Text(
                "This permanently deletes this panel's messages. The other panel is unaffected.",
                style = MaterialTheme.typography.bodyMedium
            )
        },
        confirmButton = {
            TextButton(onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onConfirm()
            }, shapes = ButtonDefaults.shapes()) {
                Text("Clear", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, shapes = ButtonDefaults.shapes()) { Text("Cancel") }
        }
    )
}

/** An empty conversation: the persona's large avatar in its Expressive shape, its name and what it does (C3). */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun EmptyStatePlaceholder(mode: ChatMode, preset: PromptPreset, persona: PersonaColors) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(horizontal = 32.dp)) {
            val subtitle = when (mode) {
                ChatMode.ORACLE -> "Ask anything: the answer comes from your Second Brain's notes, with its sources."
                ChatMode.PRESET -> PromptPresetFormat.plainTitle(preset.title) + ": " +
                    preset.description.ifBlank { "a preset from your vault" } +
                    if (preset.useNotes) " · grounded in your notes" else ""
                ChatMode.SCRIBE -> "Brainstorm freely with the on-device AI, away from your notes."
            }
            PersonaAvatar(
                mode = mode,
                preset = preset,
                persona = persona,
                size = 104.dp,
                shape = MaterialShapes.Cookie9Sided.toShape()
            )
            Spacer(modifier = Modifier.height(20.dp))
            Text(
                text = personaName(mode, preset),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}
