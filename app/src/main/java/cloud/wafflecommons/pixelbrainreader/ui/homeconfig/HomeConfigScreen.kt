package cloud.wafflecommons.pixelbrainreader.ui.homeconfig

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import cloud.wafflecommons.pixelbrainreader.ui.theme.NavBarClearance
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import cloud.wafflecommons.pixelbrainreader.data.local.entity.ChoreEntity
import cloud.wafflecommons.pixelbrainreader.data.local.entity.HomeRoomEntity
import cloud.wafflecommons.pixelbrainreader.ui.components.CortexIconButton
import cloud.wafflecommons.pixelbrainreader.ui.theme.RoomPalette
import java.time.LocalDate
import java.util.UUID
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeConfigScreen(
    onNavigateBack: () -> Unit,
    viewModel: HomeConfigViewModel = hiltViewModel()
) {
    val haptic = LocalHapticFeedback.current
    val rooms by viewModel.allRooms.collectAsStateWithLifecycle()
    val choresByRoom by viewModel.choresByRoom.collectAsStateWithLifecycle()

    var showRoomDialog by remember { mutableStateOf<HomeRoomEntity?>(null) }
    var showCreateRoomDialog by remember { mutableStateOf(false) }
    
    var showChoreSheet by remember { mutableStateOf<ChoreEntity?>(null) }
    var showCreateChoreSheet by remember { mutableStateOf(false) }

    var roomToDelete by remember { mutableStateOf<HomeRoomEntity?>(null) }
    var choreToDelete by remember { mutableStateOf<ChoreEntity?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Configuration de la maison") },
                navigationIcon = {
                    CortexIconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Retour")
                    }
                }
            )
        },
        floatingActionButton = {
            // Lift the stacked FABs above the floating ExpressiveNavBar (which overlays content).
            Column(
                horizontalAlignment = Alignment.End,
                modifier = Modifier.padding(bottom = 66.dp)
            ) {
                SmallFloatingActionButton(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        showCreateRoomDialog = true
                    },
                    modifier = Modifier.padding(bottom = 8.dp)
                ) {
                    Icon(Icons.Rounded.Home, contentDescription = "Ajouter une pièce")
                }
                ExtendedFloatingActionButton(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        showCreateChoreSheet = true
                    },
                    icon = { Icon(Icons.Rounded.Add, contentDescription = "Ajouter une corvée") },
                    text = { Text("Ajouter une corvée") }
                )
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(bottom = NavBarClearance)
        ) {
            item {
                Text(
                    text = "Mes pièces",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(16.dp),
                    color = MaterialTheme.colorScheme.primary
                )
                
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // "Add Room" Chip
                    item {
                        InputChip(
                            selected = false,
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                showCreateRoomDialog = true
                            },
                            label = { Text("Ajouter") },
                            leadingIcon = { Icon(Icons.Rounded.Add, "Ajouter", Modifier.size(18.dp)) }
                        )
                    }

                    items(rooms, key = { it.id }) { room ->
                        val choreCount = choresByRoom[room.id]?.size ?: 0
                        FilterChip(
                            selected = true,
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                showRoomDialog = room
                            },
                            modifier = Modifier.animateItem(),
                            label = { Text("${room.name} ($choreCount)") },
                            trailingIcon = {
                                CortexIconButton(
                                    onClick = { roomToDelete = room },
                                    modifier = Modifier.size(18.dp)
                                ) {
                                    Icon(
                                        Icons.Rounded.Delete, 
                                        contentDescription = "Supprimer",
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(14.dp)
                                    )
                                }
                            }
                        )
                    }
                }
                
                if (rooms.isEmpty()) {
                    Text(
                        text = "Aucune pièce configurée. Ajoutez une pièce pour commencer.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                }
                
                HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
            }

            rooms.forEach { room ->
                item(key = "header_${room.id}") {
                    Text(
                        text = room.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }

                val chores = choresByRoom[room.id] ?: emptyList()
                if (chores.isEmpty()) {
                    item {
                        Text(
                            text = "Aucune corvée dans cette pièce.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp)
                        )
                    }
                } else {
                    items(chores, key = { it.id }) { chore ->
                        ListItem(
                            headlineContent = { Text(chore.name, fontWeight = FontWeight.Medium) },
                            supportingContent = { Text("Tous les ${chore.frequencyDays} jours • ${chore.baseEffort} XP") },
                            trailingContent = {
                                Row {
                                    CortexIconButton(onClick = { showChoreSheet = chore }) {
                                        Icon(Icons.Rounded.Edit, contentDescription = "Modifier la corvée")
                                    }
                                    CortexIconButton(onClick = { choreToDelete = chore }) {
                                        Icon(Icons.Rounded.Delete, contentDescription = "Supprimer la corvée", tint = MaterialTheme.colorScheme.error)
                                    }
                                }
                            },
                            modifier = Modifier
                                .clickable {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    showChoreSheet = chore
                                }
                                .animateItem()
                        )
                    }
                }
            }
        }
    }

    // --- Dialogs & Sheets ---

    if (showRoomDialog != null || showCreateRoomDialog) {
        val isEdit = showRoomDialog != null
        val targetRoom = showRoomDialog
        var nameInput by remember { mutableStateOf(targetRoom?.name ?: "") }

        AlertDialog(
            onDismissRequest = { 
                showRoomDialog = null
                showCreateRoomDialog = false 
            },
            title = { Text(if (isEdit) "Modifier la pièce" else "Nouvelle pièce") },
            text = {
                OutlinedTextField(
                    value = nameInput,
                    onValueChange = { nameInput = it },
                    label = { Text("Nom de la pièce") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (nameInput.isNotBlank()) {
                            viewModel.upsertRoom(
                                id = targetRoom?.id,
                                name = nameInput,
                                icon = targetRoom?.icon ?: "home",
                                // Keep an existing room's colour on edit; give a new room a random one.
                                color = targetRoom?.color ?: RoomPalette.randomHex(),
                                sortOrder = targetRoom?.sortOrder ?: 0
                            )
                        }
                        showRoomDialog = null
                        showCreateRoomDialog = false
                    },
                    enabled = nameInput.isNotBlank()
                ) {
                    Text("Enregistrer")
                }
            },
            dismissButton = {
                TextButton(onClick = { 
                    showRoomDialog = null
                    showCreateRoomDialog = false 
                }) {
                    Text("Annuler")
                }
            }
        )
    }

    if (roomToDelete != null) {
        AlertDialog(
            onDismissRequest = { roomToDelete = null },
            title = { Text("Supprimer la pièce ?") },
            text = { Text("Supprimer '${roomToDelete?.name}' l'effacera définitivement, ainsi que toutes les corvées associées. Cette action est irréversible.") },
            confirmButton = {
                Button(
                    onClick = {
                        roomToDelete?.let { viewModel.deleteRoom(it) }
                        roomToDelete = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Supprimer")
                }
            },
            dismissButton = {
                TextButton(onClick = { roomToDelete = null }) {
                    Text("Annuler")
                }
            }
        )
    }

    if (choreToDelete != null) {
        AlertDialog(
            onDismissRequest = { choreToDelete = null },
            title = { Text("Supprimer la corvée ?") },
            text = { Text("Voulez-vous vraiment supprimer '${choreToDelete?.name}' ?") },
            confirmButton = {
                Button(
                    onClick = {
                        choreToDelete?.let { viewModel.deleteChore(it) }
                        choreToDelete = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Supprimer")
                }
            },
            dismissButton = {
                TextButton(onClick = { choreToDelete = null }) {
                    Text("Annuler")
                }
            }
        )
    }

    if (showChoreSheet != null || showCreateChoreSheet) {
        val isEdit = showChoreSheet != null
        val targetChore = showChoreSheet

        var name by remember { mutableStateOf(targetChore?.name ?: "") }
        var selectedRoomId by remember { mutableStateOf(targetChore?.roomId ?: rooms.firstOrNull()?.id ?: "") }
        var frequencyDays by remember { mutableStateOf(targetChore?.frequencyDays?.toString() ?: "7") }
        var baseEffort by remember { mutableFloatStateOf(targetChore?.baseEffort?.toFloat() ?: 30f) }

        val nameError = name.isBlank()
        val frequencyError = frequencyDays.toIntOrNull()?.let { it <= 0 } ?: true

        ModalBottomSheet(
            onDismissRequest = { 
                showChoreSheet = null
                showCreateChoreSheet = false 
            }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 16.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = if (isEdit) "Modifier la corvée" else "Nouvelle corvée",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 16.dp)
                )

                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Nom de la corvée") },
                    modifier = Modifier.fillMaxWidth(),
                    isError = nameError,
                    singleLine = true
                )

                Spacer(modifier = Modifier.height(16.dp))

                var roomExpanded by remember { mutableStateOf(false) }
                val selectedRoomName = rooms.find { it.id == selectedRoomId }?.name ?: "Sélectionner une pièce"

                ExposedDropdownMenuBox(
                    expanded = roomExpanded,
                    onExpandedChange = { roomExpanded = it },
                ) {
                    OutlinedTextField(
                        value = selectedRoomName,
                        onValueChange = { },
                        readOnly = true,
                        label = { Text("Pièce") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(androidx.compose.material3.ExposedDropdownMenuAnchorType.PrimaryNotEditable),
                        colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors()
                    )

                    ExposedDropdownMenu(
                        expanded = roomExpanded,
                        onDismissRequest = { roomExpanded = false }
                    ) {
                        rooms.forEach { room ->
                            DropdownMenuItem(
                                text = { Text(room.name) },
                                onClick = {
                                    selectedRoomId = room.id
                                    roomExpanded = false
                                }
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                OutlinedTextField(
                    value = frequencyDays,
                    onValueChange = { frequencyDays = it.filter { char -> char.isDigit() } },
                    label = { Text("Fréquence (jours)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                    isError = frequencyError,
                    singleLine = true
                )

                Spacer(modifier = Modifier.height(24.dp))

                Text(
                    text = "Effort / récompense XP de base : ${baseEffort.roundToInt()}",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold
                )
                
                Slider(
                    value = baseEffort,
                    onValueChange = { baseEffort = it },
                    valueRange = 10f..100f,
                    steps = 8,
                    modifier = Modifier.fillMaxWidth()
                )
                
                Spacer(modifier = Modifier.height(32.dp))

                Button(
                    onClick = {
                        if (selectedRoomId.isBlank()) return@Button // Should never happen unless no rooms exist
                        
                        val finalFreq = frequencyDays.toIntOrNull() ?: 1
                        val newChore = ChoreEntity(
                            id = targetChore?.id ?: UUID.randomUUID().toString(),
                            name = name.trim(),
                            roomId = selectedRoomId,
                            baseEffort = baseEffort.roundToInt(),
                            frequencyDays = finalFreq,
                            lastDoneDate = targetChore?.lastDoneDate ?: LocalDate.now().toString(),
                            icon = targetChore?.icon ?: "cleaning_services",
                            createdAt = targetChore?.createdAt ?: System.currentTimeMillis()
                        )
                        viewModel.upsertChore(newChore)
                        
                        showChoreSheet = null
                        showCreateChoreSheet = false
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !nameError && !frequencyError && selectedRoomId.isNotBlank()
                ) {
                    Text("Enregistrer la corvée")
                }
                
                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }
}
