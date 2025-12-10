package cloud.wafflecommons.pixelbrainreader.ui.ai

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.EventNote
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.hilt.navigation.compose.hiltViewModel
import cloud.wafflecommons.pixelbrainreader.data.ai.ScribePersona
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatPanel(
    modifier: Modifier = Modifier,
    viewModel: ChatViewModel = hiltViewModel(),
    onInsertContent: (String) -> Unit = {}
) {
    var textState by remember { mutableStateOf(TextFieldValue("")) }
    val listState = rememberLazyListState()

    // --- Collapsible Header Logic REMOVED for Stability ---
    // We use a simple Column layout to keep the header sticky at the top without complex offset calculations.

    // Auto-scroll logic (Optimized)
    LaunchedEffect(viewModel.messages.size) {
        if (viewModel.messages.isNotEmpty()) {
            listState.animateScrollToItem(viewModel.messages.lastIndex)
        }
    }

    Scaffold(
        containerColor = Color.Transparent, 
        contentWindowInsets = ScaffoldDefaults.contentWindowInsets.exclude(WindowInsets.ime),
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .imePadding()
        ) {
            // 1. Header (Chips) - Fixed at Top
            Surface(
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
                modifier = Modifier.fillMaxWidth().zIndex(1f)
            ) {
                 Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp, horizontal = 16.dp),
                    horizontalArrangement = Arrangement.Center
                ) {
                    ScribePersona.entries.forEach { persona ->
                        val icon = when(persona) {
                            ScribePersona.TECH_WRITER -> Icons.Rounded.Description
                            ScribePersona.CODER -> Icons.Rounded.Code
                            ScribePersona.PLANNER -> Icons.Rounded.EventNote
                        }
                        ExpressiveChip(
                            label = persona.name.replace("_", " ").lowercase().replaceFirstChar { it.uppercase() },
                            icon = icon,
                            isSelected = viewModel.currentPersona == persona,
                            onClick = { viewModel.switchPersona(persona) }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                }
            }

            // 2. Chat Content & Input
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                Column(modifier = Modifier.fillMaxSize()) {
                    Box(modifier = Modifier.weight(1f)) {
                         if (viewModel.messages.isEmpty()) {
                            EmptyStatePlaceholder()
                        } else {
                            LazyColumn(
                                state = listState,
                                modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                                contentPadding = PaddingValues(top = 16.dp, bottom = 24.dp), 
                                verticalArrangement = Arrangement.spacedBy(24.dp)
                            ) {
                                items(viewModel.messages) { msg ->
                                    ChatBubble(
                                        message = msg,
                                        onInsert = if (!msg.isUser) onInsertContent else null
                                    )
                                }
                            }
                        }
                    }
                    
                    // Input Bar
                     StealthInputBar(
                        textState = textState,
                        onTextChange = { textState = it },
                        onSend = {
                            if (textState.text.isNotBlank()) {
                                viewModel.sendMessage(textState.text)
                                textState = TextFieldValue("")
                            }
                        },
                        isLoading = viewModel.isLoading,
                        hint = "Describe what to write..."
                    )
                }
            }
        }
    }
}

// --- COMPONENTS CUSTOM ---



@Composable
fun ExpressiveChip(
    label: String,
    icon: ImageVector,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    // Couleurs : Tertiary pour se différencier du Secondary (Vault/Scribe)
    val containerColor by animateColorAsState(
        if (isSelected) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest, // Gris doux si inactif
        label = "chipBg"
    )
    val contentColor by animateColorAsState(
        if (isSelected) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        label = "chipContent"
    )

    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = containerColor,
        modifier = Modifier.height(40.dp) // Hauteur standard Material 3 (plus confortable)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 16.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = contentColor
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge, // Texte un peu plus grand
                color = contentColor
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
    hint: String
) {
    var lastClickTime by remember { mutableLongStateOf(0L) }
    val isEnabled = textState.text.isNotBlank() && !isLoading
    
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
            .imePadding()
            .navigationBarsPadding(),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            modifier = Modifier.widthIn(max = 640.dp).fillMaxWidth(),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Row(
                modifier = Modifier.padding(start = 20.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(modifier = Modifier.weight(1f)) {
                    if (textState.text.isEmpty()) {
                        Text(
                            text = hint,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    }
                    BasicTextField(
                        value = textState,
                        onValueChange = onTextChange,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        maxLines = 4,
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(
                            onSend = {
                                val currentTime = System.currentTimeMillis()
                                if (isEnabled && (currentTime - lastClickTime > 1000L)) {
                                    lastClickTime = currentTime
                                    onSend()
                                }
                            }
                        )
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                val btnColor by animateColorAsState(
                    if (isEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest,
                    label = "btnColor"
                )
                
                // UX Requirement: Mic when empty, Send when typing
                val showMic = textState.text.isBlank()

                IconButton(
                    onClick = {
                        if (showMic) {
                             // Placeholder for Voice Feature
                             println("Voice feature not implemented yet")
                        } else {
                            val currentTime = System.currentTimeMillis()
                            if (currentTime - lastClickTime > 1000L) {
                                lastClickTime = currentTime
                                onSend()
                            }
                        }
                    },
                    enabled = true, // Always clickable for the Mic feedback
                    modifier = Modifier
                        .size(44.dp)
                        .background(btnColor, CircleShape)
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            color = MaterialTheme.colorScheme.onPrimary,
                            strokeWidth = 2.dp
                        )
                    } else {
                        AnimatedContent(targetState = showMic, label = "iconSwitch") { isMic ->
                            if (isMic) {
                                Icon(
                                    Icons.Rounded.Mic,
                                    contentDescription = "Voice Input",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                ) 
                            } else {
                                Icon(
                                    Icons.AutoMirrored.Rounded.Send,
                                    contentDescription = "Send",
                                    tint = MaterialTheme.colorScheme.onPrimary
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun EmptyStatePlaceholder() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = Icons.Outlined.Psychology,
                contentDescription = null,
                modifier = Modifier.size(100.dp),
                tint = MaterialTheme.colorScheme.surfaceContainerHighest
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                "Pixel Brain is ready",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun ChatBubble(message: ChatMessage, onInsert: ((String) -> Unit)?) {
    val isUser = message.isUser

    val bubbleShape = if (isUser) {
        RoundedCornerShape(24.dp, 24.dp, 4.dp, 24.dp)
    } else {
        RoundedCornerShape(24.dp, 24.dp, 24.dp, 4.dp)
    }

    val containerColor = if (isUser) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f)
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
                if (!isUser) {
                     // Spacer only for spacing
                     Spacer(Modifier.height(8.dp))
                }

                Text(
                    text = message.content,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )

                if (onInsert != null && !message.isStreaming && message.content.isNotBlank()) {
                    Spacer(Modifier.height(12.dp))
                    FilledTonalButton(
                        onClick = { onInsert(message.content) },
                        modifier = Modifier.height(32.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp)
                    ) {
                        Text("Add to Doc", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
}
