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
import cloud.wafflecommons.pixelbrainreader.data.model.HabitType
// Removed incorrect import
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

    Card(
        onClick = {
            if (config.type == HabitType.MEASURABLE) {
                showDialog = true
            } else {
                onToggle()
            }
        },
        colors = CardDefaults.cardColors(containerColor = containerColor),
        modifier = Modifier.size(width = 160.dp, height = 180.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Top Section: Icon & Title
            Column {
                // Icon (Visual)
                Icon(
                    imageVector = if (isDone) Icons.Default.Check else Icons.Default.RadioButtonUnchecked, 
                    contentDescription = null,
                    tint = themeColor,
                    modifier = Modifier.size(28.dp)
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text = config.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // Bottom Section: Progress or Status
            Column {
                if (config.type == HabitType.MEASURABLE) {
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
                        modifier = Modifier.fillMaxWidth().height(6.dp),
                        color = themeColor,
                        trackColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    )
                } else {
                    // Boolean
                     if (isDone) {
                        Text(
                            "Done!",
                            style = MaterialTheme.typography.labelLarge,
                            color = themeColor,
                            fontWeight = FontWeight.Bold
                        )
                    } else {
                         Text(
                            "Do it",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
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
        modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)
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
                Box(
                    modifier = Modifier
                        .width(2.dp)
                        .height(24.dp)
                        .background(MaterialTheme.colorScheme.outlineVariant)
                )
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
