package cloud.wafflecommons.pixelbrainreader.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowRight
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.window.core.layout.WindowWidthSizeClass

@Composable
fun BreadcrumbBar(
    currentPath: String,
    onPathClick: (String) -> Unit,
    onHomeClick: () -> Unit,
    isLargeScreen: Boolean
) {
    val pathParts = currentPath.split("/").filter { it.isNotEmpty() }
    val showCompact = !isLargeScreen && pathParts.size > 2

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.Home,
            contentDescription = "Home",
            modifier = Modifier
                .clickable(onClick = onHomeClick)
                .padding(4.dp)
                .size(20.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        
        if (pathParts.isEmpty()) return@Row

        Icon(Icons.AutoMirrored.Filled.ArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)

        if (showCompact) {
             Text(
                text = "...",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp)
            )
             Icon(Icons.AutoMirrored.Filled.ArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            // Show only last part
             val lastPart = pathParts.last()
             Text(
                text = lastPart,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                 modifier = Modifier.padding(horizontal = 4.dp)
            )
        } else {
            pathParts.forEachIndexed { index, part ->
                if (index > 0) {
                    Icon(Icons.AutoMirrored.Filled.ArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                
                // Reconstruct path for click
                // This is a naive reconstruction, assuming unique names in path or sequential.
                // A better way would be accumulating path.
                val clickPath = pathParts.take(index + 1).joinToString("/")
                
                Text(
                    text = part,
                    style = if (index == pathParts.lastIndex) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyMedium,
                    color = if (index == pathParts.lastIndex) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .clickable { onPathClick(clickPath) }
                        .padding(horizontal = 4.dp)
                )
            }
        }
    }
}
