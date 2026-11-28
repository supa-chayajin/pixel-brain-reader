package cloud.wafflecommons.pixelbrainreader.ui.homeconfig

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cloud.wafflecommons.pixelbrainreader.data.device.VaultWriteGate
import cloud.wafflecommons.pixelbrainreader.data.local.entity.ChoreEntity
import cloud.wafflecommons.pixelbrainreader.data.local.entity.HomeRoomEntity
import cloud.wafflecommons.pixelbrainreader.ui.components.CortexIconButton
import cloud.wafflecommons.pixelbrainreader.ui.components.ReadOnlyNotice
import cloud.wafflecommons.pixelbrainreader.ui.components.segmentShape
import cloud.wafflecommons.pixelbrainreader.ui.settings.SettingsLazyPageScaffold
import cloud.wafflecommons.pixelbrainreader.ui.settings.SettingsNote
import cloud.wafflecommons.pixelbrainreader.ui.settings.SettingsPage
import cloud.wafflecommons.pixelbrainreader.ui.settings.SettingsRow
import cloud.wafflecommons.pixelbrainreader.ui.settings.SettingsSubhead
import cloud.wafflecommons.pixelbrainreader.ui.theme.LocalNavRail
import cloud.wafflecommons.pixelbrainreader.ui.theme.RoomPalette
import java.time.LocalDate
import java.util.UUID
import kotlin.math.roundToInt

/**
 * Settings › Life OS › Home & chores: the rooms, and each room's chores as a segmented list (13.4,
 * S-k: the Settings frame). The rooms and chores live in the vault: a read-only device lists them
 * and edits none (12.15, S2).
 */
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
    // A read-only device lists the rooms and chores and edits none (12.15, S2).
    val readOnly = VaultWriteGate.readOnly

    SettingsLazyPageScaffold(
        page = SettingsPage.HOME_CHORES,
        onBack = onNavigateBack,
        floatingActionButton = {
            // Lift the stacked FABs above the floating ExpressiveNavBar (which overlays content); none beside the rail.
            if (!readOnly) Column(
                horizontalAlignment = Alignment.End,
                modifier = Modifier.padding(bottom = if (LocalNavRail.current) 0.dp else 66.dp)
            ) {
                SmallFloatingActionButton(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        showCreateRoomDialog = true
                    },
                    modifier = Modifier.padding(bottom = 8.dp)
                ) {
                    Icon(Icons.Rounded.Home, contentDescription = "Add a room")
                }
                ExtendedFloatingActionButton(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        showCreateChoreSheet = true
                    },
                    icon = { Icon(Icons.Rounded.Add, contentDescription = null) },
                    text = { Text("Add chore") }
                )
            }
        }
    ) {
        if (readOnly) item(key = "read-only") { ReadOnlyNotice(Modifier.padding(bottom = 14.dp)) }
        item(key = "rooms-header") { SettingsSubhead("Rooms", Modifier.padding(bottom = 6.dp)) }
        item(key = "rooms") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // "Add" chip
                    if (!readOnly) item {
                        InputChip(
                            selected = false,
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                showCreateRoomDialog = true
                            },
                            label = { Text("Add") },
                            leadingIcon = { Icon(Icons.Rounded.Add, "Add a room", Modifier.size(18.dp)) }
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
                            enabled = !readOnly,
                            modifier = Modifier.animateItem(),
                            label = { Text("${room.name} ($choreCount)") },
                            trailingIcon = if (readOnly) null else {
                                {
                                    CortexIconButton(
                                        onClick = { roomToDelete = room },
                                        modifier = Modifier.size(18.dp)
                                    ) {
                                        Icon(
                                            Icons.Rounded.Delete,
                                            contentDescription = "Delete ${room.name}",
                                            tint = MaterialTheme.colorScheme.error,
                                            modifier = Modifier.size(14.dp)
                                        )
                                    }
                                }
                            }
                        )
                    }
                }
                if (rooms.isEmpty()) {
                    SettingsNote(if (readOnly) "No rooms yet." else "No rooms yet. Add a room to get started.")
                }
            }
        }

        rooms.forEach { room ->
            item(key = "header_${room.id}") {
                SettingsSubhead(room.name, Modifier.padding(top = 14.dp, bottom = 6.dp))
            }

            val chores = choresByRoom[room.id] ?: emptyList()
            if (chores.isEmpty()) {
                item(key = "empty_${room.id}") { SettingsNote("No chores in this room.") }
            } else {
                itemsIndexed(chores, key = { _, chore -> chore.id }) { index, chore ->
                    SettingsRow(
                        title = chore.name,
                        description = "Every ${chore.frequencyDays} days • ${chore.baseEffort} XP",
                        icon = Icons.Rounded.CleaningServices,
                        iconKey = room.name,
                        shape = segmentShape(index, chores.size),
                        onClick = if (readOnly) null else { { showChoreSheet = chore } },
                        trailing = if (readOnly) null else {
                            {
                                Row {
                                    CortexIconButton(onClick = { showChoreSheet = chore }) {
                                        Icon(Icons.Rounded.Edit, contentDescription = "Edit ${chore.name}")
                                    }
                                    CortexIconButton(onClick = { choreToDelete = chore }) {
                                        Icon(Icons.Rounded.Delete, contentDescription = "Delete ${chore.name}", tint = MaterialTheme.colorScheme.error)
                                    }
                                }
                            }
                        },
                        modifier = Modifier.animateItem()
                    )
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
            title = { Text(if (isEdit) "Edit room" else "New room") },
            text = {
                OutlinedTextField(
                    value = nameInput,
                    onValueChange = { nameInput = it },
                    label = { Text("Room name") },
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
                    }, shapes = ButtonDefaults.shapes(),
                    enabled = nameInput.isNotBlank()
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showRoomDialog = null
                    showCreateRoomDialog = false
                }, shapes = ButtonDefaults.shapes()) {
                    Text("Cancel")
                }
            }
        )
    }

    if (roomToDelete != null) {
        AlertDialog(
            onDismissRequest = { roomToDelete = null },
            title = { Text("Delete room?") },
            text = { Text("Deleting '${roomToDelete?.name}' will permanently remove it and cascade delete all associated chores. This cannot be undone.") },
            confirmButton = {
                Button(
                    onClick = {
                        roomToDelete?.let { viewModel.deleteRoom(it) }
                        roomToDelete = null
                    }, shapes = ButtonDefaults.shapes(),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { roomToDelete = null }, shapes = ButtonDefaults.shapes()) {
                    Text("Cancel")
                }
            }
        )
    }

    if (choreToDelete != null) {
        AlertDialog(
            onDismissRequest = { choreToDelete = null },
            title = { Text("Delete chore?") },
            text = { Text("Are you sure you want to delete '${choreToDelete?.name}'?") },
            confirmButton = {
                Button(
                    onClick = {
                        choreToDelete?.let { viewModel.deleteChore(it) }
                        choreToDelete = null
                    }, shapes = ButtonDefaults.shapes(),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { choreToDelete = null }, shapes = ButtonDefaults.shapes()) {
                    Text("Cancel")
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
                    text = if (isEdit) "Edit chore" else "New chore",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 16.dp)
                )

                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Chore name") },
                    modifier = Modifier.fillMaxWidth(),
                    isError = nameError,
                    singleLine = true
                )

                Spacer(modifier = Modifier.height(16.dp))

                var roomExpanded by remember { mutableStateOf(false) }
                val selectedRoomName = rooms.find { it.id == selectedRoomId }?.name ?: "Select a room"

                ExposedDropdownMenuBox(
                    expanded = roomExpanded,
                    onExpandedChange = { roomExpanded = it },
                ) {
                    OutlinedTextField(
                        value = selectedRoomName,
                        onValueChange = { },
                        readOnly = true,
                        label = { Text("Room") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
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
                    label = { Text("Every (days)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                    isError = frequencyError,
                    singleLine = true
                )

                Spacer(modifier = Modifier.height(24.dp))

                Text(
                    text = "Effort: ${baseEffort.roundToInt()} XP",
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
                        // Editing keeps every field the form doesn't show (archived flag, manual sort
                        // order): rebuilding the entity used to un-archive the chore and reset its order.
                        val newChore = targetChore?.copy(
                            name = name.trim(),
                            roomId = selectedRoomId,
                            baseEffort = baseEffort.roundToInt(),
                            frequencyDays = finalFreq
                        ) ?: ChoreEntity(
                            id = UUID.randomUUID().toString(),
                            name = name.trim(),
                            roomId = selectedRoomId,
                            baseEffort = baseEffort.roundToInt(),
                            frequencyDays = finalFreq,
                            lastDoneDate = LocalDate.now().toString(),
                            icon = "cleaning_services",
                            createdAt = System.currentTimeMillis()
                        )
                        viewModel.upsertChore(newChore)

                        showChoreSheet = null
                        showCreateChoreSheet = false
                    }, shapes = ButtonDefaults.shapes(),
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !nameError && !frequencyError && selectedRoomId.isNotBlank()
                ) {
                    Text("Save chore")
                }

                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }
}
