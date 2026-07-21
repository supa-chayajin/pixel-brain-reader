package cloud.wafflecommons.pixelbrainreader.ui.homeos

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import cloud.wafflecommons.pixelbrainreader.data.local.entity.HomeRoomEntity
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddChoreBottomSheet(
    onDismiss: () -> Unit,
    viewModel: ChoreViewModel
) {
    val allRooms by viewModel.allRooms.collectAsStateWithLifecycle()
    val haptic = LocalHapticFeedback.current

    var name by remember { mutableStateOf("") }
    var selectedRoomId by remember { mutableStateOf("") }
    var frequencyDays by remember { mutableStateOf("") }
    var baseEffort by remember { mutableFloatStateOf(30f) }
    var isExpanded by remember { mutableStateOf(false) }

    val nameError = name.isBlank()
    val frequencyError = frequencyDays.toIntOrNull()?.let { it <= 0 } ?: true

    ModalBottomSheet(
        onDismissRequest = onDismiss
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                text = "Nouvelle corvée",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Nom de la corvée") },
                placeholder = { Text("Ex : Vider le lave-vaisselle") },
                modifier = Modifier.fillMaxWidth(),
                isError = name.isNotBlank() && nameError,
                singleLine = true
            )

            Spacer(modifier = Modifier.height(16.dp))

            if (allRooms.isEmpty()) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
                ) {
                    Text(
                        text = "Créez d'abord une pièce pour ajouter des corvées",
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            } else {
                ExposedDropdownMenuBox(
                    expanded = isExpanded,
                    onExpandedChange = { isExpanded = it },
                ) {
                    val currentRoomName = allRooms.find { it.id == selectedRoomId }?.name ?: "Sélectionner une pièce"
                    OutlinedTextField(
                        value = currentRoomName,
                        onValueChange = { },
                        readOnly = true,
                        label = { Text("Pièce") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(androidx.compose.material3.ExposedDropdownMenuAnchorType.PrimaryNotEditable),
                        colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors()
                    )

                    ExposedDropdownMenu(
                        expanded = isExpanded,
                        onDismissRequest = { isExpanded = false }
                    ) {
                        allRooms.forEach { existingRoom ->
                            DropdownMenuItem(
                                text = { Text(existingRoom.name) },
                                onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    selectedRoomId = existingRoom.id
                                    isExpanded = false
                                }
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            OutlinedTextField(
                value = frequencyDays,
                onValueChange = { frequencyDays = it.filter { char -> char.isDigit() } },
                label = { Text("Fréquence (jours)") },
                placeholder = { Text("7 pour une fois par semaine") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
                isError = frequencyDays.isNotBlank() && frequencyError,
                singleLine = true
            )

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "Effort / récompense XP : ${baseEffort.roundToInt()}",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold
            )
            
            Slider(
                value = baseEffort,
                onValueChange = { baseEffort = it },
                valueRange = 10f..100f,
                steps = 8, // (100 - 10) / 10 = 9 intervals => 8 internal steps
                modifier = Modifier.fillMaxWidth()
            )
            
            Spacer(modifier = Modifier.height(32.dp))

            Button(
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    val finalFreq = frequencyDays.toIntOrNull() ?: 1
                    viewModel.addChoreWithRoomId(
                        name = name,
                        roomId = selectedRoomId,
                        frequencyDays = finalFreq,
                        baseEffort = baseEffort.roundToInt()
                    )
                    onDismiss()
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !nameError && !frequencyError && selectedRoomId.isNotBlank()
            ) {
                Text("Enregistrer la corvée")
            }
            
            Spacer(modifier = Modifier.height(32.dp)) // Extra padding for the bottom bar/nav
        }
    }
}
