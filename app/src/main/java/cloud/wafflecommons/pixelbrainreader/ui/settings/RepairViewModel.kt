package cloud.wafflecommons.pixelbrainreader.ui.settings

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cloud.wafflecommons.pixelbrainreader.data.device.VaultWriteGate
import cloud.wafflecommons.pixelbrainreader.data.repository.ChoreRepository
import cloud.wafflecommons.pixelbrainreader.data.repository.HabitRepository
import cloud.wafflecommons.pixelbrainreader.data.repository.VaultListFile
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Settings › Vault & device › Repair (13.4, S7): the 12.x Import / Export All, as repair tools.
 * Habits, rooms and chores save to the vault on their own; these only put the app and the vault
 * back in step when something looks out of it.
 */
@HiltViewModel
class RepairViewModel @Inject constructor(
    private val habitRepository: HabitRepository,
    private val choreRepository: ChoreRepository
) : ViewModel() {

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    /**
     * Habits (configs and logs), rooms and chores read again from the vault into the app, through the
     * same bridges the sync runs: it only reads the vault, so a read-only device can run it too.
     */
    fun reload(onDone: (Boolean) -> Unit) {
        if (_running.value) return
        viewModelScope.launch {
            _running.value = true
            try {
                habitRepository.syncWithFileSystem()
                choreRepository.syncWithFileSystem()
                onDone(true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Reload from the vault failed: ${e.javaClass.simpleName}")
                onDone(false)
            } finally {
                _running.value = false
            }
        }
    }

    /**
     * The app's habits, rooms and chores written back to the vault's files, then synced: a vault
     * write, refused on a read-only device. A file the app holds none of the items of is left as it
     * is ([WriteBackOutcome.Done.left]); a failed write, sync or move aside is [WriteBackOutcome.Failed].
     */
    fun writeBack(onDone: (WriteBackOutcome) -> Unit) {
        if (_running.value || VaultWriteGate.refuses()) return
        viewModelScope.launch {
            _running.value = true
            try {
                onDone(WriteBackOutcome.Done(habitRepository.performBulkConfigSync()))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Write back to the vault failed: ${e.javaClass.simpleName}")
                onDone(WriteBackOutcome.Failed)
            } finally {
                _running.value = false
            }
        }
    }

    private companion object {
        const val TAG = "Repair"
    }
}

/** How Repair's write-back ended (13.4, S7). */
sealed interface WriteBackOutcome {
    /** Written and synced, except the vault's files [left] as they are (the app held none of their items). */
    data class Done(val left: List<VaultListFile>) : WriteBackOutcome

    /** A write, a file that couldn't be kept aside, or the sync failed. */
    data object Failed : WriteBackOutcome
}

/** What the snackbar says after a write-back (13.4): a file left as it is asks for a reload first. Pure. */
internal fun writeBackMessage(outcome: WriteBackOutcome): String = when (outcome) {
    WriteBackOutcome.Failed -> "Writing back failed."
    is WriteBackOutcome.Done -> {
        val left = VaultListFile.entries.filter { it in outcome.left }
        when {
            left.isEmpty() -> "Written back to the vault and synced."
            left.size == VaultListFile.entries.size ->
                "Nothing written back: the app holds no habits, rooms or chores. Reload from the vault first."
            else -> {
                val names = left.map {
                    when (it) {
                        VaultListFile.HABITS -> "habits"
                        VaultListFile.ROOMS -> "rooms"
                        VaultListFile.CHORES -> "chores"
                    }
                }
                "Written back and synced. The vault's ${names.joinToString(" and ")} were left as they are: " +
                    "the app holds none. Reload from the vault first."
            }
        }
    }
}
