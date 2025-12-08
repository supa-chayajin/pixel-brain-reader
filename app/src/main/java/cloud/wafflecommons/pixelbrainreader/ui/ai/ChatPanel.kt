package cloud.wafflecommons.pixelbrainreader.ui.ai

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import cloud.wafflecommons.pixelbrainreader.data.ai.ScribePersona

@Composable
fun ChatPanel(
    modifier: Modifier = Modifier,
    viewModel: ChatViewModel = hiltViewModel(),
    onInsertContent: (String) -> Unit = {}
) {
    var textState by remember { mutableStateOf(TextFieldValue("")) }
    val listState = rememberLazyListState()

    // Auto-scroll to bottom
    LaunchedEffect(viewModel.messages.size, viewModel.messages.lastOrNull()?.content) {
        if (viewModel.messages.isNotEmpty()) {
            listState.animateScrollToItem(viewModel.messages.lastIndex)
        }
    }

    Column(modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerHigh)) {
        
        // Header & Mode Switch
        Row(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("Neural Vault", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 8.dp))
            
            IconButton(onClick = { viewModel.resetChat() }) {
                Icon(Icons.Default.Delete, "Clear Chat")
            }
        }

        // Toggles
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
            FilterChip(
                selected = viewModel.currentMode == ChatMode.RAG,
                onClick = { viewModel.switchMode(ChatMode.RAG) },
                label = { Text("Vault (RAG)") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                modifier = Modifier.padding(end = 4.dp)
            )
            FilterChip(
                selected = viewModel.currentMode == ChatMode.SCRIBE,
                onClick = { viewModel.switchMode(ChatMode.SCRIBE) },
                label = { Text("Scribe") },
                leadingIcon = { Icon(Icons.Default.Edit, null) },
            )
        }
        
        // Persona Chips (Only if Scribe)
        if (viewModel.currentMode == ChatMode.SCRIBE) {
           Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
               ScribePersona.entries.forEach { persona ->
                   SuggestionChip(
                       onClick = { viewModel.switchPersona(persona) },
                       label = { Text(persona.name.take(4)) }, // Short label
                       modifier = Modifier.padding(end = 4.dp),
                       colors = if (viewModel.currentPersona == persona) SuggestionChipDefaults.suggestionChipColors(containerColor = MaterialTheme.colorScheme.secondaryContainer) else SuggestionChipDefaults.suggestionChipColors()
                   )
               }
           }
        }

        Divider(color = MaterialTheme.colorScheme.outlineVariant)

        // Messages List
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
            contentPadding = PaddingValues(vertical = 8.dp)
        ) {
            items(viewModel.messages) { msg ->
                ChatBubble(message = msg, onInsert = if (viewModel.currentMode == ChatMode.SCRIBE && !msg.isUser) onInsertContent else null)
            }
        }

        // Input Area
        Row(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = textState,
                onValueChange = { textState = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text(if (viewModel.currentMode == ChatMode.RAG) "Ask your documents..." else "Describe what to write...") },
                singleLine = false,
                maxLines = 4
            )
            IconButton(
                onClick = {
                    viewModel.sendMessage(textState.text)
                    textState = TextFieldValue("")
                },
                enabled = textState.text.isNotBlank() && !viewModel.isLoading
            ) {
                if (viewModel.isLoading) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                } else {
                    Icon(Icons.AutoMirrored.Filled.Send, "Send")
                }
            }
        }
    }
}

@Composable
fun ChatBubble(message: ChatMessage, onInsert: ((String) -> Unit)?) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalAlignment = if (message.isUser) Alignment.End else Alignment.Start
    ) {
        Surface(
            shape = if (message.isUser) RoundedCornerShape(12.dp, 12.dp, 0.dp, 12.dp) else RoundedCornerShape(12.dp, 12.dp, 12.dp, 0.dp),
            color = if (message.isUser) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
            modifier = Modifier.widthIn(max = 280.dp)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                if (!message.isUser) {
                    Icon(Icons.Default.Star, null, modifier = Modifier.size(16.dp).padding(bottom = 4.dp), tint = MaterialTheme.colorScheme.tertiary)
                }
                Text(
                    text = message.content, 
                    style = MaterialTheme.typography.bodyMedium
                )
                
                // Insert Button for Scribe Mode AI responses
                if (onInsert != null && !message.isStreaming && message.content.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = { onInsert(message.content) },
                        modifier = Modifier.height(32.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
                    ) {
                        Text("Insert", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}
