package cloud.wafflecommons.pixelbrainreader.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

data class JohnnyDecimalCategory(
    val code: String,
    val label: String,
    val isPrivate: Boolean = false
)

fun parseJohnnyDecimal(name: String): JohnnyDecimalCategory? {
    val prefix = name.takeWhile { it.isDigit() }
    val num = prefix.toIntOrNull() ?: return null
    return when (num) {
        in 0..9 -> JohnnyDecimalCategory(prefix, "Meta")
        in 10..19 -> JohnnyDecimalCategory(prefix, "Journal")
        in 20..29 -> JohnnyDecimalCategory(prefix, "Projects")
        in 30..39 -> JohnnyDecimalCategory(prefix, "Areas")
        in 40..49 -> JohnnyDecimalCategory(prefix, "Resources")
        in 50..89 -> JohnnyDecimalCategory(prefix, "Archives")
        in 90..99 -> JohnnyDecimalCategory(prefix, "Private", isPrivate = true)
        else -> null
    }
}

@Composable
fun BreadcrumbBar(
    currentPath: String,
    onPathClick: (String) -> Unit,
    onHomeClick: () -> Unit,
    isLargeScreen: Boolean
) {
    val pathParts = currentPath.split("/").filter { it.isNotEmpty() }
    val showCompact = !isLargeScreen && pathParts.size > 2 || isLargeScreen && pathParts.size > 3


    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        FilledTonalIconButton(
            onClick = { onHomeClick() },
            colors = IconButtonDefaults.filledTonalIconButtonColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant
            )
        ) {
            Icon(
                imageVector = Icons.Default.Home,
                contentDescription = "Home",
                modifier = Modifier
                    .padding(4.dp)
                    .size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        
        if (pathParts.isEmpty()) return@Row

        Icon(Icons.AutoMirrored.Filled.ArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)

        if (showCompact) {
            val parentPath = pathParts.dropLast(1).joinToString("/")
            FilledTonalButton(
                onClick = { onPathClick(parentPath) },
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
            ) {
                Text(
                    text = "...",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
            }
             Icon(Icons.AutoMirrored.Filled.ArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            // Show only last part
             val lastPart = pathParts.last()
            val jdCategory = parseJohnnyDecimal(lastPart)

            FilledTonalButton(
                onClick = { onPathClick(currentPath) },
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = if (jdCategory?.isPrivate == true) MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)
                                     else MaterialTheme.colorScheme.surfaceContainerHighest,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                ),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
            ) {
                if (jdCategory != null) {
                    Text(
                        text = "[${jdCategory.label}]",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (jdCategory.isPrivate) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                        modifier = Modifier.padding(end = 4.dp)
                    )
                }
                Text(
                    text = lastPart,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        } else {
            pathParts.forEachIndexed { index, part ->
                if (index > 0) {
                    Icon(Icons.AutoMirrored.Filled.ArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                
                val clickPath = pathParts.take(index + 1).joinToString("/")
                val jdCategory = parseJohnnyDecimal(part)

                FilledTonalButton(
                    onClick = { onPathClick(clickPath) },
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = if (jdCategory?.isPrivate == true) MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)
                                         else MaterialTheme.colorScheme.surfaceContainerHighest,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                    ),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    if (jdCategory != null) {
                        Text(
                            text = "[${jdCategory.label}]",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (jdCategory.isPrivate) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                            modifier = Modifier.padding(end = 4.dp)
                        )
                    }
                    Text(
                        text = part,
                        style = if (index == pathParts.lastIndex) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyMedium,
                        color = if (index == pathParts.lastIndex) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
