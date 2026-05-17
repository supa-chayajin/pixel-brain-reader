package cloud.wafflecommons.pixelbrainreader.ui.homeconfig

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import cloud.wafflecommons.pixelbrainreader.data.local.entity.ChoreEntity
import cloud.wafflecommons.pixelbrainreader.data.local.entity.HomeRoomEntity
import java.time.LocalDate
import java.util.UUID
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeConfigScreen(
    onNavigateBack: () -> Unit,
    viewModel: HomeConfigViewModel = hiltViewModel()
) {
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
                title = { Text("Home Configuration") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        floatingActionButton = {
            Column(horizontalAlignment = Alignment.End) {
                SmallFloatingActionButton(
                    onClick = { showCreateRoomDialog = true },
                    modifier = Modifier.padding(bottom = 8.dp)
                ) {
                    Icon(Icons.Rounded.Home, contentDescription = "Add Room")
                }
                ExtendedFloatingActionButton(
                    onClick = { showCreateChoreSheet = true },
                    icon = { Icon(Icons.Rounded.Add, contentDescription = "Add Chore") },
                    text = { Text("Add Chore") }
                )
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(bottom = 100.dp)
        ) {
            item {
                Text(
                    text = "My Rooms",
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
                            onClick = { showCreateRoomDialog = true },
                            label = { Text("Add") },
                            leadingIcon = { Icon(Icons.Rounded.Add, "Add", Modifier.size(18.dp)) }
                        )
                    }

                    items(rooms, key = { it.id }) { room ->
                        val choreCount = choresByRoom[room.id]?.size ?: 0
                        FilterChip(
                            selected = true,
                            onClick = { showRoomDialog = room },
                            label = { Text("${room.name} ($choreCount)") },
                            trailingIcon = {
                                IconButton(
                                    onClick = { roomToDelete = room },
                                    modifier = Modifier.size(18.dp)
                                ) {
                                    Icon(
                                        Icons.Rounded.Delete, 
                                        contentDescription = "Delete",
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
                        text = "No rooms configured. Add a room to get started.",
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
                            text = "No chores in this room.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp)
                        )
                    }
                } else {
                    items(chores, key = { it.id }) { chore ->
                        ListItem(
                            headlineContent = { Text(chore.name, fontWeight = FontWeight.Medium) },
                            supportingContent = { Text("Every ${chore.frequencyDays} days • ${chore.baseEffort} XP") },
                            trailingContent = {
                                Row {
                                    IconButton(onClick = { showChoreSheet = chore }) {
                                        Icon(Icons.Rounded.Edit, contentDescription = "Edit Chore")
                                    }
                                    IconButton(onClick = { choreToDelete = chore }) {
                                        Icon(Icons.Rounded.Delete, contentDescription = "Delete Chore", tint = MaterialTheme.colorScheme.error)
                                    }
                                }
                            },
                            modifier = Modifier.clickable { showChoreSheet = chore }
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
            title = { Text(if (isEdit) "Edit Room" else "New Room") },
            text = {
                OutlinedTextField(
                    value = nameInput,
                    onValueChange = { nameInput = it },
                    label = { Text("Room Name") },
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
                                color = targetRoom?.color ?: "#808080",
                                sortOrder = targetRoom?.sortOrder ?: 0
                            )
                        }
                        showRoomDialog = null
                        showCreateRoomDialog = false
                    },
                    enabled = nameInput.isNotBlank()
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { 
                    showRoomDialog = null
                    showCreateRoomDialog = false 
                }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (roomToDelete != null) {
        AlertDialog(
            onDismissRequest = { roomToDelete = null },
            title = { Text("Delete Room?") },
            text = { Text("Deleting '${roomToDelete?.name}' will permanently remove it and cascade delete all associated chores. This cannot be undone.") },
            confirmButton = {
                Button(
                    onClick = {
                        roomToDelete?.let { viewModel.deleteRoom(it) }
                        roomToDelete = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { roomToDelete = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (choreToDelete != null) {
        AlertDialog(
            onDismissRequest = { choreToDelete = null },
            title = { Text("Delete Chore?") },
            text = { Text("Are you sure you want to delete '${choreToDelete?.name}'?") },
            confirmButton = {
                Button(
                    onClick = {
                        choreToDelete?.let { viewModel.deleteChore(it) }
                        choreToDelete = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { choreToDelete = null }) {
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
                    text = if (isEdit) "Edit Chore" else "New Chore",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 16.dp)
                )

                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Chore Name") },
                    modifier = Modifier.fillMaxWidth(),
                    isError = nameError,
                    singleLine = true
                )

                Spacer(modifier = Modifier.height(16.dp))

                var roomExpanded by remember { mutableStateOf(false) }
                val selectedRoomName = rooms.find { it.id == selectedRoomId }?.name ?: "Select Room"

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
                            .menuAnchor(androidx.compose.material3.MenuAnchorType.PrimaryNotEditable),
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
                    label = { Text("Frequency (Days)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                    isError = frequencyError,
                    singleLine = true
                )

                Spacer(modifier = Modifier.height(24.dp))

                Text(
                    text = "Effort / Base XP Reward: ${baseEffort.roundToInt()}",
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
                    Text("Save Chore")
                }
                
                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }
}
