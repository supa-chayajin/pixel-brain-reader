package cloud.wafflecommons.pixelbrainreader.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.automirrored.outlined.NoteAdd
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewFileBottomSheet(
    availableTemplates: List<String>,
    onDismiss: () -> Unit,
    loadVariables: suspend (templateName: String) -> List<String> = { emptyList() },
    onCreate: (filename: String, templateName: String?, values: Map<String, String>) -> Unit
) {
    // Expressive: Allow partial expansion (half-height)
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    val haptic = LocalHapticFeedback.current
    var filename by remember { mutableStateOf("") }
    var selectedTemplate by remember { mutableStateOf<String?>(null) } // null = Blank
    // The selected template's form: one field per {{variable}} (E1).
    var variables by remember { mutableStateOf(emptyList<String>()) }
    val values = remember { androidx.compose.runtime.mutableStateMapOf<String, String>() }
    androidx.compose.runtime.LaunchedEffect(selectedTemplate) {
        variables = selectedTemplate?.let { loadVariables(it) }.orEmpty()
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        // Expressive Shape: Rounded top corners (extraLarge token, top-only)
        shape = MaterialTheme.shapes.extraLarge.copy(
            bottomStart = androidx.compose.foundation.shape.CornerSize(0.dp),
            bottomEnd = androidx.compose.foundation.shape.CornerSize(0.dp)
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
                placeholder = { Text("New file name (e.g. Project/Note)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
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
                        icon = Icons.AutoMirrored.Outlined.NoteAdd,
                        onClick = { selectedTemplate = null }
                    )
                }

                // Option B: Templates
                items(availableTemplates, key = { it }) { templateName ->
                    val displayName = formatTemplateName(templateName)
                    Box(Modifier.animateItem()) {
                        TemplateListItem(
                            displayName = displayName,
                            isSelected = selectedTemplate == templateName,
                            icon = Icons.Outlined.Description,
                            onClick = { selectedTemplate = templateName }
                        )
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(24.dp))

            if (variables.isNotEmpty()) {
                TemplateForm(variables, values)
                Spacer(modifier = Modifier.height(16.dp))
            }

            // 3. Create Action
            Button(
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onCreate(filename, selectedTemplate, values.filterKeys { it in variables })
                },
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
    val haptic = LocalHapticFeedback.current
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
            .clickable {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onClick()
            }
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

/** One field per template variable; a date picker for the date-like ones. Blank fields stay as placeholders. */
@Composable
private fun TemplateForm(variables: List<String>, values: MutableMap<String, String>) {
    var picking by remember { mutableStateOf<String?>(null) }
    Text(
        text = "Fill in the template",
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(bottom = 8.dp)
    )
    // Bounded and scrollable, so a long form never pushes "Create Note" off the sheet.
    Column(
        modifier = Modifier
            .heightIn(max = 280.dp)
            .verticalScroll(androidx.compose.foundation.rememberScrollState()),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)
    ) {
        variables.forEach { name ->
            val label = name.replace('_', ' ').replaceFirstChar { it.uppercase() }
            if (cloud.wafflecommons.pixelbrainreader.data.utils.TemplateEngine.isDateVariable(name)) {
                androidx.compose.material3.AssistChip(
                    onClick = { picking = name },
                    label = { Text(values[name]?.let { "$label: $it" } ?: label) }
                )
            } else {
                OutlinedTextField(
                    value = values[name].orEmpty(),
                    onValueChange = { values[name] = it },
                    label = { Text(label) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
    picking?.let { name ->
        PickDateDialog(
            initial = values[name]?.let { runCatching { java.time.LocalDate.parse(it) }.getOrNull() },
            onConfirm = { date -> values[name] = date.toString(); picking = null },
            onDismiss = { picking = null }
        )
    }
}
