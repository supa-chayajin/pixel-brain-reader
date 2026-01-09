package cloud.wafflecommons.pixelbrainreader.ui.lifeos

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import cloud.wafflecommons.pixelbrainreader.data.model.Task
import java.time.format.DateTimeFormatter

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
