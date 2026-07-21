package cloud.wafflecommons.pixelbrainreader.ui.daily

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import cloud.wafflecommons.pixelbrainreader.data.local.entity.GratitudeEntity
import cloud.wafflecommons.pixelbrainreader.ui.components.CortexIconButton
import cloud.wafflecommons.pixelbrainreader.ui.utils.HapticHelper

import androidx.compose.ui.platform.LocalView

@Composable
fun GratitudeSection(
    gratitudes: List<GratitudeEntity>,
    onAddGratitude: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var text by remember { mutableStateOf("") }
    val focusManager = LocalFocusManager.current
    val view = LocalView.current // For HapticHelper

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium, // Medium for consistency
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(
            modifier = Modifier
                .padding(16.dp)
                .animateContentSize()
        ) {
            Text(
                text = "Gratitude express ✨",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary
            )
            
            Spacer(modifier = Modifier.height(8.dp))

            // Input
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Un petit moment positif ?") },
                singleLine = true,
                trailingIcon = {
                    CortexIconButton(
                        onClick = {
                            if (text.isNotBlank()) {
                                onAddGratitude(text)
                                with(HapticHelper) { view.performHapticSuccess() }
                                text = ""
                                focusManager.clearFocus()
                            }
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Default.Star,
                            contentDescription = "Ajouter une gratitude",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                },
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    imeAction = ImeAction.Done
                ),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                    onDone = {
                        if (text.isNotBlank()) {
                            onAddGratitude(text)
                            with(HapticHelper) { view.performHapticSuccess() }
                            text = ""
                            focusManager.clearFocus()
                        }
                    }
                ),
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                )
            )

            // List
            if (gratitudes.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    gratitudes.forEach { entry ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("✨", style = MaterialTheme.typography.bodySmall)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = entry.content,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}
