package cloud.wafflecommons.pixelbrainreader.ui.ai

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.EventNote
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import cloud.wafflecommons.pixelbrainreader.data.ai.ScribePersona

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
    LaunchedEffect(viewModel.messages.size, viewModel.messages.lastOrNull()?.content) {
        if (viewModel.messages.isNotEmpty()) {
            listState.animateScrollToItem(viewModel.messages.lastIndex)
        }
    }

    Scaffold(
        modifier = modifier,
        containerColor = Color.Transparent, // Transparent pour le blend avec MainScreen
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        "Neural Vault",
                        style = MaterialTheme.typography.titleLarge
                    )
                },
                actions = {
                    IconButton(onClick = { viewModel.resetChat() }) {
                        Icon(Icons.Rounded.Delete, "Clear Chat", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = Color.Transparent,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {

            // --- 1. Expressive Toggle (Vault / Scribe) ---
            Spacer(modifier = Modifier.height(8.dp))
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                ExpressiveToggle(
                    currentMode = viewModel.currentMode,
                    onModeSelected = { viewModel.switchMode(it) }
                )
            }

            // --- 2. Expressive Chips (Scribe Personas) ---
            // CORRECTION: Rendu plus moderne avec icônes et taille adaptée
            AnimatedVisibility(visible = viewModel.currentMode == ChatMode.SCRIBE) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp, start = 16.dp, end = 16.dp), // Plus d'espace
                    horizontalArrangement = Arrangement.Center
                ) {
                    ScribePersona.entries.forEach { persona ->
                        // Mapping simple d'icônes selon le persona
                        val icon = when(persona) {
                            ScribePersona.TECH_WRITER -> Icons.Rounded.Description
                            ScribePersona.CODER -> Icons.Rounded.Code
                            ScribePersona.PLANNER -> Icons.Rounded.EventNote
                        }

                        ExpressiveChip(
                            label = persona.name.replace("_", " ").lowercase().replaceFirstChar { it.uppercase() }, // Format "Tech writer"
                            icon = icon,
                            isSelected = viewModel.currentPersona == persona,
                            onClick = { viewModel.switchPersona(persona) }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                }
            }

            // --- 3. Content Area ---
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                if (viewModel.messages.isEmpty()) {
                    EmptyStatePlaceholder()
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 16.dp),
                        contentPadding = PaddingValues(top = 24.dp, bottom = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        items(viewModel.messages) { msg ->
                            ChatBubble(
                                message = msg,
                                onInsert = if (viewModel.currentMode == ChatMode.SCRIBE && !msg.isUser) onInsertContent else null
                            )
                        }
                    }
                }
            }

            // --- 4. Stealth Input Bar ---
            StealthInputBar(
                textState = textState,
                onTextChange = { textState = it },
                onSend = {
                    viewModel.sendMessage(textState.text)
                    textState = TextFieldValue("")
                },
                isLoading = viewModel.isLoading,
                hint = if (viewModel.currentMode == ChatMode.RAG) "Ask your documents..." else "Describe what to write..."
            )
        }
    }
}

// --- COMPONENTS CUSTOM ---

@Composable
fun ExpressiveToggle(
    currentMode: ChatMode,
    onModeSelected: (ChatMode) -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = CircleShape,
        modifier = Modifier
            .height(56.dp) // Légèrement plus grand pour l'impact
            .width(280.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ToggleOption(
                text = "Vault",
                icon = Icons.Rounded.Search,
                isSelected = currentMode == ChatMode.RAG,
                modifier = Modifier.weight(1f),
                onClick = { onModeSelected(ChatMode.RAG) }
            )
            ToggleOption(
                text = "Scribe",
                icon = Icons.Rounded.Edit,
                isSelected = currentMode == ChatMode.SCRIBE,
                modifier = Modifier.weight(1f),
                onClick = { onModeSelected(ChatMode.SCRIBE) }
            )
        }
    }
}

@Composable
fun ToggleOption(
    text: String,
    icon: ImageVector,
    isSelected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val backgroundColor by animateColorAsState(
        targetValue = if (isSelected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
        animationSpec = tween(300),
        label = "bg"
    )
    val contentColor by animateColorAsState(
        targetValue = if (isSelected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        label = "content"
    )

    Box(
        modifier = modifier
            .fillMaxHeight()
            .padding(4.dp)
            .clip(CircleShape)
            .background(backgroundColor)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.titleSmall, // Plus gras
                color = contentColor
            )
        }
    }
}

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
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                val isEnabled = textState.text.isNotBlank() && !isLoading
                val btnColor by animateColorAsState(
                    if (isEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
                    label = "btnColor"
                )

                IconButton(
                    onClick = onSend,
                    enabled = isEnabled,
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
                        Icon(
                            Icons.AutoMirrored.Rounded.Send,
                            contentDescription = "Send",
                            tint = if (isEnabled) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                        )
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
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.AutoAwesome, null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.tertiary)
                        Spacer(Modifier.width(6.dp))
                        Text("Brain", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
                    }
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
