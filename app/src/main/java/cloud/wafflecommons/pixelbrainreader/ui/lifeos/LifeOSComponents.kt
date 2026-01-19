package cloud.wafflecommons.pixelbrainreader.ui.lifeos

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cloud.wafflecommons.pixelbrainreader.data.model.Task
import java.time.format.DateTimeFormatter
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Remove
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.draw.alpha
import cloud.wafflecommons.pixelbrainreader.data.model.HabitType
import cloud.wafflecommons.pixelbrainreader.data.model.TimelineEvent


@Composable
fun TaskTimeline(
    tasks: List<Task>,
    onToggle: (Task) -> Unit,
    modifier: Modifier = Modifier
) {
    if (tasks.isEmpty()) {
        Text(
            text = "No focus tasks planned.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier.padding(8.dp)
        )
    } else {
        Column(modifier = modifier) {
            tasks.forEach { task ->
                TaskTimelineItem(task, onToggle)
                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }
}

@Composable
fun TaskTimelineItem(task: Task, onToggle: (Task) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggle(task) },
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Time
        Text(
            text = task.time?.format(DateTimeFormatter.ofPattern("HH:mm")) ?: "Any",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.width(48.dp)
        )

        // Card
        Card(
            colors = CardDefaults.cardColors(
                containerColor = if (task.isCompleted) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surfaceContainer
            ),
            modifier = Modifier.weight(1f)
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(
                    checked = task.isCompleted, 
                    onCheckedChange = { onToggle(task) },
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = task.cleanText,
                    style = MaterialTheme.typography.bodyMedium,
                    textDecoration = if (task.isCompleted) TextDecoration.LineThrough else null
                )
            }
        }
    }
}

@Composable
fun HabitCard(
    habit: HabitWithStats,
    onToggle: () -> Unit,
    onUpdateValue: (Double) -> Unit
) {
    val config = habit.config
    // Parse Color
    val themeColor = remember(config.color) {
        try {
            Color(android.graphics.Color.parseColor(config.color))
        } catch (e: Exception) {
            Color(0xFF6750A4) // Fallback Primary
        }
    }

    // State for Dialog
    var showDialog by remember { mutableStateOf(false) }

    if (showDialog) {
        HabitEditDialog(
            habit = habit,
            color = themeColor,
            onDismiss = { showDialog = false },
            onConfirm = { newVal ->
                onUpdateValue(newVal)
                showDialog = false
            }
        )
    }

    val isDone = habit.isCompletedToday
    // Animate Background
    val containerColor by animateColorAsState(
        if (isDone) themeColor.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surfaceContainer
    )

    // Dim if not scheduled today
    val alpha = if (habit.isScheduledToday) 1f else 0.5f

    Card(
        onClick = {
            if (config.type == HabitType.MEASURABLE) {
                showDialog = true
            } else {
                onToggle()
            }
        },
        colors = CardDefaults.cardColors(containerColor = containerColor),
        modifier = Modifier.fillMaxWidth().wrapContentHeight().alpha(alpha) // [MODIFIED] Alpha
    ) {
        Column(
            modifier = Modifier.padding(12.dp)
        ) {
            // Top Section: Info (Icon + Text)
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Icon (Visual)
                Icon(
                    imageVector = if (isDone) Icons.Default.Check else Icons.Default.RadioButtonUnchecked, 
                    contentDescription = null,
                    tint = themeColor,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(Modifier.width(8.dp))
                
                Text(
                    text = config.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            
            Spacer(Modifier.height(4.dp))
            
            // Description (Middle)
            if (config.description.isNotBlank()) {
                val styledDescription = androidx.compose.ui.text.buildAnnotatedString {
                    val regex = Regex("\\(\\+[A-Z]{3}\\)")
                    val text = config.description
                    
                    var lastIndex = 0
                    regex.findAll(text).forEach { result ->
                        append(text.substring(lastIndex, result.range.first))
                        
                        // Highlight the tag
                        withStyle(
                            androidx.compose.ui.text.SpanStyle(
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold
                            )
                        ) {
                            append(result.value)
                        }
                        lastIndex = result.range.last + 1
                    }
                    append(text.substring(lastIndex))
                }
                
                Text(
                    text = styledDescription,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
            
            // Visual Separator
            Spacer(Modifier.height(8.dp))

            // Bottom Section: Input Control
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.CenterEnd // Align controls to end
            ) {
                if (config.type == HabitType.MEASURABLE) {
                    Column(horizontalAlignment = Alignment.End) {
                        val target = if (config.targetValue > 0) config.targetValue else 1.0
                        val progress = (habit.currentValue / target).toFloat().coerceIn(0f, 1f)
                        
                        Text(
                            text = "${habit.currentValue.toInt()} / ${config.targetValue.toInt()} ${config.unit}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(4.dp))
                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier.width(80.dp).height(8.dp).clip(RoundedCornerShape(4.dp)),
                            color = themeColor,
                            trackColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        )
                    }
                } else {
                    // Boolean - Just show Streak here? Or keep "Tap to Complete"?
                    // User Request: 'display the current "Streak"... at the bottom of the card with a small flame 🔥 icon.'
                    // Let's put Streak on the LEFT and Status on the RIGHT.
                }
            }
            
            // [NEW] Footer Row: Streak + Status/Action
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Streak Display
                Text(
                    text = "🔥 ${habit.currentStreak}",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (habit.currentStreak > 2) Color(0xFFFF9800) else MaterialTheme.colorScheme.onSurfaceVariant
                )
                
                // Status / Action Hint
                if (config.type != HabitType.MEASURABLE) {
                     if (isDone) {
                        Text(
                            "Done!",
                            style = MaterialTheme.typography.labelLarge,
                            color = themeColor,
                            fontWeight = FontWeight.Bold
                        )
                    } else if (habit.isScheduledToday) {
                         Text(
                            "Tap to Complete",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                         Text(
                            "Not Today",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha=0.7f)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun HabitEditDialog(
    habit: HabitWithStats,
    color: Color,
    onDismiss: () -> Unit,
    onConfirm: (Double) -> Unit
) {
    val config = habit.config
    var tempValue by remember { mutableDoubleStateOf(habit.currentValue) }
    
    // Quick Add Steps (e.g. 250ml for water)
    val step = if (config.unit.lowercase() in listOf("ml", "mg", "g")) 250.0 else 1.0

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(text = "Update ${config.title}")
        },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "${tempValue.toInt()} / ${config.targetValue.toInt()} ${config.unit}",
                    style = MaterialTheme.typography.displayMedium,
                    color = color
                )
                Spacer(Modifier.height(16.dp))
                
                // Controls
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    FilledIconButton(
                        onClick = { tempValue = (tempValue - step).coerceAtLeast(0.0) },
                        colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
                    ) {
                        Icon(Icons.Default.Remove, "-")
                    }
                    
                    FilledIconButton(
                        onClick = { tempValue = (tempValue + step) },
                        colors = IconButtonDefaults.filledIconButtonColors(containerColor = color)
                    ) {
                        Icon(Icons.Default.Add, "+")
                    }
                }
                
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = tempValue.toString(),
                    onValueChange = { 
                        it.toDoubleOrNull()?.let { v -> tempValue = v }
                    },
                    label = { Text("Value") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.width(120.dp)
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(tempValue) },
                colors = ButtonDefaults.buttonColors(containerColor = color)
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

// --- DAY TIMELINE COMPONENT ---

@Composable
fun DayTimeline(events: List<TimelineEvent>) {
    if (events.isEmpty()) {
        Text(
            text = "No items planned.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(8.dp)
        )
    } else {
        Column(modifier = Modifier.padding(vertical = 8.dp)) {
            events.sortedBy { it.time }.forEachIndexed { index, event ->
                val isLast = index == events.lastIndex
                DayTimelineItem(event, isLast)
            }
        }
    }
}

@Composable
fun DayTimelineItem(event: TimelineEvent, isLast: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
        verticalAlignment = Alignment.Top
    ) {
        // Time Column
        Text(
            text = event.time.format(DateTimeFormatter.ofPattern("HH:mm")),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .width(50.dp)
                .padding(top = 2.dp), 
            textAlign = androidx.compose.ui.text.style.TextAlign.End
        )
        
        Spacer(Modifier.width(12.dp))

        // Axis Column
        Column(
            horizontalAlignment = Alignment.CenterHorizontally, 
            modifier = Modifier.width(16.dp)
        ) {
            // Dot & Line
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(top = 2.dp) // Optical alignment with text
            ) {
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary)
                )
                if (!isLast) {
                    Box(
                        modifier = Modifier
                            .width(2.dp)
                            .height(24.dp)
                            .background(MaterialTheme.colorScheme.outlineVariant)
                    )
                }
            }
        }
        
        Spacer(Modifier.width(12.dp))

        // Content Column
        Text(
            text = event.content,
            style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 20.sp),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .weight(1f)
                .padding(bottom = 24.dp) // Spacing between events
        )
    }
}
