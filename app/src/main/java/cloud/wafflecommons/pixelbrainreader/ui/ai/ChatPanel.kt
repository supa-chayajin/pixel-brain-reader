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
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material3.*
import androidx.compose.runtime.*
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

    // Auto-scroll logic
    LaunchedEffect(viewModel.messages.size) {
        if (viewModel.messages.isNotEmpty()) {
            listState.animateScrollToItem(viewModel.messages.lastIndex)
        }
    }

    val modeColor by animateColorAsState(
        targetValue = if (viewModel.currentMode == ChatMode.ORACLE) Color(0xFF9C27B0) else Color(0xFFFF9800), // Purple vs Orange
        label = "modeColor"
    )

    Scaffold(
        topBar = {
            Column(modifier = Modifier.background(MaterialTheme.colorScheme.surface)) {
                cloud.wafflecommons.pixelbrainreader.ui.components.CortexTopAppBar(title = "Neural Interface")
                
                // --- MODE SWITCHER ---
                BrainModeSwitch(
                    currentMode = viewModel.currentMode,
                    onModeChanged = { viewModel.toggleMode() },
                    activeColor = modeColor
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
                if (viewModel.messages.isEmpty()) {
                    EmptyStatePlaceholder(mode = viewModel.currentMode)
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                        contentPadding = PaddingValues(top = 16.dp, bottom = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        items(viewModel.messages) { msg ->
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
                    hint = if (viewModel.currentMode == ChatMode.ORACLE) "Ask your Second Brain..." else "Spark a creative idea...",
                    accentColor = modeColor
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
    activeColor: Color
) {
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Rounded.Psychology, 
                    contentDescription = null, 
                    tint = if (currentMode == ChatMode.ORACLE) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "Cortex (RAG)",
                    style = MaterialTheme.typography.labelLarge,
                    color = if (currentMode == ChatMode.ORACLE) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Rounded.AutoAwesome, 
                    contentDescription = null, 
                    tint = if (currentMode == ChatMode.SCRIBE) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "Spark (Creative)",
                    style = MaterialTheme.typography.labelLarge,
                    color = if (currentMode == ChatMode.SCRIBE) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
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

    val containerColor = if (isUser) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainerHigh
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
    ) {
        Surface(
            shape = bubbleShape,
            color = containerColor,
            modifier = Modifier.widthIn(max = 340.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                
                // Message Content
                Text(
                    text = message.content,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                
                // SOURCES SECTION (RAG CITATIONS)
                if (!isUser && message.sources.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(12.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f))
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
                                colors = AssistChipDefaults.assistChipColors(
                                    containerColor = MaterialTheme.colorScheme.surface,
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
    accentColor: Color
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
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
                    } else {
                         Icon(
                            if (textState.text.isBlank()) Icons.Rounded.Mic else Icons.AutoMirrored.Rounded.Send,
                            contentDescription = null,
                            tint = if (isEnabled) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
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
