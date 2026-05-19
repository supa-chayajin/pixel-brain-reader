package cloud.wafflecommons.pixelbrainreader.ui.ai

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cloud.wafflecommons.pixelbrainreader.data.ai.NanoState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.hilt.navigation.compose.hiltViewModel
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatPanel(
    modifier: Modifier = Modifier,
    viewModel: ChatViewModel = hiltViewModel(),
    onInsertContent: (String) -> Unit = {}
) {
    var textState by remember { mutableStateOf(TextFieldValue("")) }
    val listState = rememberLazyListState()

    val currentMode by viewModel.currentMode.collectAsStateWithLifecycle()
    val persistedHistory by viewModel.chatHistory.collectAsStateWithLifecycle()
    val streaming by viewModel.streamingMessage.collectAsStateWithLifecycle()

    // Single source of truth for what the list renders: persisted history +
    // optional transient streaming bubble (cloud-only — Nano is one-shot).
    val displayedMessages = remember(persistedHistory, streaming) {
        if (streaming != null) persistedHistory + streaming!! else persistedHistory
    }

    // Auto-scroll on any new item — covers persisted appends AND streaming
    // token updates (size grows by 1 when streaming starts, then content grows
    // in place which won't trigger this; that's fine, the user already sees the
    // last bubble).
    LaunchedEffect(displayedMessages.size) {
        if (displayedMessages.isNotEmpty()) {
            listState.animateScrollToItem(displayedMessages.lastIndex)
        }
    }

    // Mode brand routed through MaterialTheme. ORACLE (RAG) = primary, SCRIBE
    // (Creative) = tertiary — both adapt to Material You. We also animate the
    // matched on-color so contrast against the accent is always guaranteed.
    val modeColor by animateColorAsState(
        targetValue = if (currentMode == ChatMode.ORACLE) MaterialTheme.colorScheme.primary
                      else MaterialTheme.colorScheme.tertiary,
        label = "modeColor"
    )
    val modeContentColor by animateColorAsState(
        targetValue = if (currentMode == ChatMode.ORACLE) MaterialTheme.colorScheme.onPrimary
                      else MaterialTheme.colorScheme.onTertiary,
        label = "modeContentColor"
    )

    val nanoState by viewModel.nanoState.collectAsStateWithLifecycle()

    if (viewModel.showCloudFallbackDialog) {
        CloudFallbackDialog(
            reason = viewModel.cloudFallbackReason,
            onConfirm = viewModel::onConfirmCloudFallback,
            onDismiss = viewModel::onDismissCloudFallback
        )
    }

    Scaffold(
        topBar = {
            Column(modifier = Modifier.background(MaterialTheme.colorScheme.surface)) {
                cloud.wafflecommons.pixelbrainreader.ui.components.CortexTopAppBar(title = "Neural Interface")
                
                // --- MODE SWITCHER ---
                BrainModeSwitch(
                    currentMode = currentMode,
                    onModeChanged = { viewModel.toggleMode() },
                    activeColor = modeColor,
                    activeContentColor = modeContentColor
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
                .imePadding()
        ) {
            // Chat Content
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                if (displayedMessages.isEmpty()) {
                    EmptyStatePlaceholder(mode = currentMode)
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                        contentPadding = PaddingValues(top = 16.dp, bottom = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        items(displayedMessages, key = { it.id }) { msg ->
                            ChatBubble(
                                message = msg,
                                onInsert = if (!msg.isUser) onInsertContent else null,
                                accentColor = modeColor
                            )
                        }
                    }
                }
            }

            // Input Area
            Column {
                // Nano availability indicator — proactive, always visible
                NanoStatusIndicator(
                    state = nanoState,
                    modifier = Modifier.padding(start = 24.dp, top = 4.dp, bottom = 2.dp)
                )

                // Loading Indicator Text
                AnimatedVisibility(visible = viewModel.loadingStage != null) {
                    Text(
                        text = viewModel.loadingStage ?: "",
                        style = MaterialTheme.typography.labelSmall,
                        color = modeColor,
                        modifier = Modifier.padding(start = 24.dp, bottom = 4.dp)
                    )
                }

                StealthInputBar(
                    textState = textState,
                    onTextChange = { textState = it },
                    onSend = {
                        if (textState.text.isNotBlank()) {
                            viewModel.sendMessage(textState.text)
                            textState = TextFieldValue("")
                        }
                    },
                    isLoading = viewModel.loadingStage != null,
                    hint = if (currentMode == ChatMode.ORACLE) "Ask your Second Brain..." else "Spark a creative idea...",
                    accentColor = modeColor,
                    accentContentColor = modeContentColor
                )
            }
        }
    }
}

// --- COMPONENTS ---

@Composable
fun BrainModeSwitch(
    currentMode: ChatMode,
    onModeChanged: () -> Unit,
    activeColor: Color,
    activeContentColor: Color
) {
    // Color of an inactive pill's icon + label — theme-aware, readable on the
    // surfaceContainerHighest track behind both pills.
    val inactiveContentColor = MaterialTheme.colorScheme.onSurfaceVariant

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 8.dp)
            .height(48.dp)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest, CircleShape)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        // CORTEX OPTION
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .clip(CircleShape)
                .background(if (currentMode == ChatMode.ORACLE) activeColor else Color.Transparent)
                .clickable { if (currentMode != ChatMode.ORACLE) onModeChanged() },
            contentAlignment = Alignment.Center
        ) {
            val contentTint = if (currentMode == ChatMode.ORACLE) activeContentColor else inactiveContentColor
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Rounded.Psychology,
                    contentDescription = null,
                    tint = contentTint,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "Cortex (RAG)",
                    style = MaterialTheme.typography.labelLarge,
                    color = contentTint
                )
            }
        }

        // SPARK OPTION
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .clip(CircleShape)
                .background(if (currentMode == ChatMode.SCRIBE) activeColor else Color.Transparent)
                .clickable { if (currentMode != ChatMode.SCRIBE) onModeChanged() },
            contentAlignment = Alignment.Center
        ) {
            val contentTint = if (currentMode == ChatMode.SCRIBE) activeContentColor else inactiveContentColor
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Rounded.AutoAwesome,
                    contentDescription = null,
                    tint = contentTint,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "Spark (Creative)",
                    style = MaterialTheme.typography.labelLarge,
                    color = contentTint
                )
            }
        }
    }
}

@Composable
fun ChatBubble(
    message: ChatMessage, 
    onInsert: ((String) -> Unit)?,
    accentColor: Color
) {
    val isUser = message.isUser

    val bubbleShape = if (isUser) {
        RoundedCornerShape(20.dp, 20.dp, 4.dp, 20.dp)
    } else {
        RoundedCornerShape(20.dp, 20.dp, 20.dp, 4.dp)
    }

    // M3 contrast pairs:
    //   User bubble: primary / onPrimary           (high-contrast brand chip)
    //   AI bubble:   surfaceVariant / onSurfaceVariant  (low-contrast surface)
    val containerColor = if (isUser) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val contentColor = if (isUser) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
    ) {
        Surface(
            shape = bubbleShape,
            color = containerColor,
            contentColor = contentColor,
            modifier = Modifier.widthIn(max = 340.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {

                // Message Content
                Text(
                    text = message.content,
                    style = MaterialTheme.typography.bodyLarge,
                    color = contentColor
                )

                // SOURCES SECTION (RAG CITATIONS) — AI bubble only
                if (!isUser && message.sources.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(12.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        "Sources:",
                        style = MaterialTheme.typography.labelSmall,
                        color = accentColor,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))

                    @OptIn(ExperimentalLayoutApi::class)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        message.sources.forEach { source ->
                            AssistChip(
                                onClick = { /* TODO: Navigate to File */ },
                                label = {
                                    Text(
                                        source.substringAfterLast("/"),
                                        style = MaterialTheme.typography.labelSmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                },
                                leadingIcon = {
                                    Icon(
                                        Icons.Outlined.Description,
                                        contentDescription = null,
                                        modifier = Modifier.size(12.dp)
                                    )
                                },
                                // Chips sit inside an AI bubble (surfaceVariant);
                                // surfaceContainerHigh makes them pop against that.
                                colors = AssistChipDefaults.assistChipColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                    labelColor = MaterialTheme.colorScheme.onSurface
                                ),
                                border = AssistChipDefaults.assistChipBorder(true)
                            )
                        }
                    }
                }
            }
        }

        if (onInsert != null && !message.isStreaming && message.content.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = { onInsert(message.content) }) {
                Icon(Icons.Outlined.SaveAlt, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Save to Inbox")
            }
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
    var lastClickTime by remember { mutableLongStateOf(0L) }
    val isEnabled = textState.text.isNotBlank() && !isLoading

    val speechLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val spokenText: String? =
                result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
            if (!spokenText.isNullOrBlank()) {
                val newText = if (textState.text.isBlank()) spokenText else "${textState.text} $spokenText"
                onTextChange(TextFieldValue(newText, TextRange(newText.length)))
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
            .navigationBarsPadding(),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            border = BorderStroke(1.dp, if (textState.text.isNotEmpty()) accentColor else Color.Transparent)
        ) {
            Row(
                modifier = Modifier.padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(modifier = Modifier.weight(1f)) {
                    if (textState.text.isEmpty()) {
                        Text(
                            text = hint,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
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

                IconButton(
                    onClick = {
                        if (textState.text.isBlank()) {
                             try {
                                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                                }
                                speechLauncher.launch(intent)
                            } catch (e: ActivityNotFoundException) { }
                        } else {
                            onSend()
                        }
                    },
                    modifier = Modifier
                        .size(44.dp)
                        .background(if (isEnabled) accentColor else MaterialTheme.colorScheme.surfaceContainerHighest, CircleShape)
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            color = accentContentColor,
                            strokeWidth = 2.dp
                        )
                    } else {
                         Icon(
                            if (textState.text.isBlank()) Icons.Rounded.Mic else Icons.AutoMirrored.Rounded.Send,
                            contentDescription = null,
                            tint = if (isEnabled) accentContentColor else MaterialTheme.colorScheme.onSurfaceVariant
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
fun CloudFallbackDialog(
    reason: String?,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Rounded.CloudOff,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
        },
        title = { Text("Local AI unavailable") },
        text = {
            Column {
                Text(
                    reason ?: "Gemini Nano cannot process this request.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    "Sending this prompt to the secure Cloud Gemini service means it will leave your device.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("Use Cloud") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
fun EmptyStatePlaceholder(mode: ChatMode) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = if (mode == ChatMode.ORACLE) Icons.Rounded.Psychology else Icons.Rounded.AutoAwesome,
                contentDescription = null,
                modifier = Modifier.size(80.dp),
                tint = MaterialTheme.colorScheme.surfaceContainerHighest
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                if (mode == ChatMode.ORACLE) "Accessing Cortex..." else "Igniting Spark...",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
