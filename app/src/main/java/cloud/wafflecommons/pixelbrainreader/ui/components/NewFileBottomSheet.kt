package cloud.wafflecommons.pixelbrainreader.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.NoteAdd
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewFileBottomSheet(
    availableTemplates: List<String>,
    onDismiss: () -> Unit,
    onCreate: (filename: String, templateName: String?) -> Unit
) {
    // Expressive: Allow partial expansion (half-height)
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    var filename by remember { mutableStateOf("") }
    var selectedTemplate by remember { mutableStateOf<String?>(null) } // null = Blank

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        // Expressive Shape: Rounded top corners (28dp)
        shape = androidx.compose.foundation.shape.RoundedCornerShape(
            topStart = 28.dp, 
            topEnd = 28.dp
        ),
        // Ensure DragHandle is shown (it is default, but explicit for clarity if needed, or just leave default)
        dragHandle = { BottomSheetDefaults.DragHandle() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp) // M3 standard margin
                .padding(bottom = 24.dp)
        ) {
            // Header: Handle is auto-included by ModalBottomSheet
            Text(
                text = "New Note",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(bottom = 24.dp)
            )

            // 1. Filename Input
            OutlinedTextField(
                value = filename,
                onValueChange = { filename = it },
                label = { Text("Filename") },
                placeholder = { Text("New file name...") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
                leadingIcon = { Icon(Icons.Outlined.Description, contentDescription = null) }
            )
            Spacer(modifier = Modifier.height(24.dp))

            // 2. Templates Section
            Text(
                text = "Choose Template",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 12.dp)
            )

            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f, fill = false)
                // Weight allows it to take space but not push header off if list is short? 
                // fill=false is key for BottomSheet behavior.
            ) {
                // Option A: No Template (Blank)
                item {
                    TemplateListItem(
                        displayName = "No Template (Blank)",
                        isSelected = selectedTemplate == null,
                        icon = Icons.Outlined.NoteAdd,
                        onClick = { selectedTemplate = null }
                    )
                }

                // Option B: Templates
                items(availableTemplates) { templateName ->
                    val displayName = formatTemplateName(templateName)
                    TemplateListItem(
                        displayName = displayName,
                        isSelected = selectedTemplate == templateName,
                        icon = Icons.Outlined.Description,
                        onClick = { selectedTemplate = templateName }
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(24.dp))

            // 3. Create Action
            Button(
                onClick = { onCreate(filename, selectedTemplate) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Create Note")
            }
            
            Spacer(modifier = Modifier.height(16.dp)) // Extra padding for navigation bar
        }
    }
}

@Composable
private fun TemplateListItem(
    displayName: String,
    isSelected: Boolean,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit
) {
    ListItem(
        headlineContent = { Text(displayName) },
        leadingContent = {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        trailingContent = {
            RadioButton(
                selected = isSelected,
                onClick = null // Handled by ListItem click
            )
        },
        modifier = Modifier
            .padding(horizontal = 4.dp, vertical = 2.dp)
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(50)) // Enforce Pill Highlight
            .clickable(onClick = onClick)
    )
}

/**
 * Formats "T_Area_Goal.md" -> "Area Goal"
 */
private fun formatTemplateName(filename: String): String {
    var name = filename
    if (name.endsWith(".md", ignoreCase = true)) {
        name = name.removeSuffix(".md")
    }
    if (name.startsWith("T_", ignoreCase = true)) {
        name = name.removePrefix("T_")
    }
    return name.replace("_", " ")
}
