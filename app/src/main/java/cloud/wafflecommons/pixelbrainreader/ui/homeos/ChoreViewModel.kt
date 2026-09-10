package cloud.wafflecommons.pixelbrainreader.ui.homeos

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cloud.wafflecommons.pixelbrainreader.data.gamification.Attribute
import cloud.wafflecommons.pixelbrainreader.data.local.entity.HomeRoomEntity
import cloud.wafflecommons.pixelbrainreader.data.repository.ChoreRepository
import cloud.wafflecommons.pixelbrainreader.data.sync.SyncOrchestrator
import cloud.wafflecommons.pixelbrainreader.data.sync.SyncState
import cloud.wafflecommons.pixelbrainreader.domain.gamification.GrantXpUseCase
import cloud.wafflecommons.pixelbrainreader.domain.homeos.CalculateChoreEntropyUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import javax.inject.Inject

@HiltViewModel
class ChoreViewModel @Inject constructor(
    private val choreRepository: ChoreRepository,
    private val calculateChoreEntropyUseCase: CalculateChoreEntropyUseCase,
    private val grantXpUseCase: GrantXpUseCase,
    private val syncOrchestrator: SyncOrchestrator
) : ViewModel() {

    // Sorted and grouped by Room natively for the UI, carrying each room's identity colour.
    val groupedChores: StateFlow<List<RoomChoreGroup>> = choreRepository.getRoomsWithChoresStream()
        .map { roomsWithChores ->
            roomsWithChores.map { roomData ->
                // Archived chores are hidden from the dashboard (still preserved on export).
                val visibleChores = roomData.chores.filter { !it.archived }
                val uiModels = calculateChoreEntropyUseCase(visibleChores)
                RoomChoreGroup(
                    roomId = roomData.room.id,
                    roomName = roomData.room.name,
                    roomColorHex = roomData.room.color,
                    // We show all known rooms (even empty ones).
                    chores = uiModels.sortedByDescending { it.dirtinessPercentage }
                )
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    val allRooms: StateFlow<List<HomeRoomEntity>> = choreRepository.getAllRoomsStream()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    val isSyncing: StateFlow<SyncState> = syncOrchestrator.syncState

    /** Chore taps being processed — a fast double tap must not complete (and reward) twice. Main thread only. */
    private val choresInFlight = mutableSetOf<String>()

    /** Pull-to-refresh: a full, user-initiated sync (bypasses the automatic cooldown). */
    fun triggerSync() {
        syncOrchestrator.requestSync(force = true)
    }

    fun doChore(choreId: String) {
        if (!choresInFlight.add(choreId)) return
        viewModelScope.launch {
            try {
                completeChore(choreId)
            } finally {
                choresInFlight.remove(choreId)
            }
        }
    }

    private suspend fun completeChore(choreId: String) = withContext(Dispatchers.IO) {
        // Read the chore fresh: the dashboard list can lag a tap behind, and a stale "dirty" model
        // used to grant the XP twice.
        val chore = choreRepository.getAllChoresStream().first().find { it.id == choreId } ?: return@withContext
        val dirtiness = calculateChoreEntropyUseCase(listOf(chore)).firstOrNull()?.dirtinessPercentage
            ?: return@withContext

        // Persistence (also when no XP is due, so the date stays accurate).
        choreRepository.updateLastDoneDate(choreId, LocalDate.now().toString())

        // ANTI-CHEAT: cleaned today or yesterday (dirtiness very low) → no XP.
        if (dirtiness < 5f) return@withContext

        // Restoring Order Bonus: +15 XP when it was deeply dirty (>= 100%).
        val finalXp = chore.baseEffort.toDouble() + if (dirtiness >= 100f) 15.0 else 0.0

        // Gamification integration — physical cleaning maps to Endurance.
        grantXpUseCase.executeCustom(
            attribute = Attribute.END,
            xpBase = finalXp,
            sourceId = "Cleaned: ${chore.name}"
        )
    }

    fun addChoreWithRoomId(name: String, roomId: String, frequencyDays: Int, baseEffort: Int, pastDays: Int = 0) {
        viewModelScope.launch(Dispatchers.IO) {
            val randomId = java.util.UUID.randomUUID().toString()
            val safeFreq = frequencyDays.takeIf { it > 0 } ?: 1

            choreRepository.insertChore(
                cloud.wafflecommons.pixelbrainreader.data.local.entity.ChoreEntity(
                    id = randomId,
                    name = name.trim(),
                    roomId = roomId,
                    baseEffort = baseEffort,
                    frequencyDays = safeFreq,
                    lastDoneDate = LocalDate.now().minusDays(pastDays.toLong()).toString()
                )
            )
        }
    }

    fun addChore(name: String, room: String, frequencyDays: Int, baseEffort: Int, pastDays: Int = 0) {
        viewModelScope.launch(Dispatchers.IO) {
            val safeRoomName = room.trim().takeIf { it.isNotEmpty() } ?: "Uncategorized"
            val safeFreq = frequencyDays.takeIf { it > 0 } ?: 1

            // Bridge: Find existing Room UUID by name, or create it dynamically
            val allRoomsList = choreRepository.getAllRoomsStream().first()
            var matchingRoom = allRoomsList.find { it.name.equals(safeRoomName, ignoreCase = true) }

            if (matchingRoom == null) {
                matchingRoom = HomeRoomEntity(
                    id = java.util.UUID.randomUUID().toString(),
                    name = safeRoomName,
                    // New rooms get a random identity colour (like habits carry a colour).
                    color = cloud.wafflecommons.pixelbrainreader.ui.theme.RoomPalette.randomHex()
                )
                choreRepository.upsertRoom(matchingRoom)
            }

            addChoreWithRoomId(name, matchingRoom.id, safeFreq, baseEffort, pastDays)
        }
    }

    fun addDebugChore() {
        addChore(
            name = "Chore ${java.util.UUID.randomUUID().toString().take(4)}",
            room = listOf("Kitchen", "Bathroom", "Living Room").random(),
            baseEffort = listOf(10, 25, 50).random(),
            frequencyDays = listOf(3, 7, 14, 30).random(),
            pastDays = (0..20).random()
        )
    }
}
