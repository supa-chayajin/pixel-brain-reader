package cloud.wafflecommons.pixelbrainreader.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.ExistingWorkPolicy
import androidx.work.WorkManager
import cloud.wafflecommons.pixelbrainreader.data.ai.GoldenSet
import cloud.wafflecommons.pixelbrainreader.data.ai.GoldenSetRunner
import cloud.wafflecommons.pixelbrainreader.data.device.VaultWriteGate
import cloud.wafflecommons.pixelbrainreader.data.repository.UserPreferencesRepository
import cloud.wafflecommons.pixelbrainreader.data.workers.IndexingWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Settings › AI & search › Knowledge vault (13.4, S-b): the vault's indexing, hybrid search, the
 * golden set's measure and related notes while writing.
 */
@HiltViewModel
class KnowledgeVaultViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val preferences: UserPreferencesRepository,
    private val goldenSetRunner: GoldenSetRunner
) : ViewModel() {

    private val _indexingState = MutableStateFlow<IndexingState>(IndexingState.Idle)
    val indexingState: StateFlow<IndexingState> = _indexingState.asStateFlow()

    /** R2 (12.20): Chat's notes re-ranked by shared words; on by default. */
    val hybridSearch: StateFlow<Boolean> = preferences.hybridSearch
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    /** The golden set's last result (R1): one line, or why it could not run. */
    private val _goldenSummary = MutableStateFlow<String?>(null)
    val goldenSummary: StateFlow<String?> = _goldenSummary.asStateFlow()

    private val _isGoldenRunning = MutableStateFlow(false)
    val isGoldenRunning: StateFlow<Boolean> = _isGoldenRunning.asStateFlow()

    init {
        // Re-attach to any run in flight (a run started before the process died, a chain of runs).
        viewModelScope.launch {
            WorkManager.getInstance(context)
                .getWorkInfosForUniqueWorkFlow(IndexingWorker.UNIQUE_WORK_NAME)
                .collect { infos -> _indexingState.value = indexingStateOf(infos) }
        }
    }

    /**
     * Index the vault now: unique work, so tapping again while a run goes on adds nothing (KEEP).
     * Battery only: requiring device-idle would wait for Doze, and the tap would seem to do nothing.
     */
    fun index() {
        // No embedding on a read-only device: its AI is off.
        if (VaultWriteGate.readOnly) return
        WorkManager.getInstance(context).enqueueUniqueWork(
            IndexingWorker.UNIQUE_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            IndexingWorker.request(tag = "manual_indexing")
        )
    }

    /** Acknowledge a finished run (success or failure): the row goes back to idle. */
    fun dismissIndexingState() {
        val current = _indexingState.value
        if (current is IndexingState.Succeeded || current is IndexingState.Failed) {
            _indexingState.value = IndexingState.Idle
        }
    }

    /** Chat's next question follows it. */
    fun setHybridSearch(enabled: Boolean) {
        viewModelScope.launch { preferences.setHybridSearch(enabled) }
    }

    fun runGoldenSet() {
        if (VaultWriteGate.readOnly || _isGoldenRunning.value) return
        viewModelScope.launch {
            _isGoldenRunning.value = true
            try {
                _goldenSummary.value = goldenSetRunner.run()?.summary()
                    ?: "No question found in ${GoldenSet.PATH}"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _goldenSummary.value = "The golden set could not run: ${e.message}"
            } finally {
                _isGoldenRunning.value = false
            }
        }
    }

    // --- Related notes while writing (13.4, S-j): the editor's bulb, also here ---

    /** K5 (12.20): the editor looks for notes close to the paragraph being written; on by default. */
    val relatedWhileWriting: StateFlow<Boolean> = preferences.relatedWhileWriting
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    fun setRelatedWhileWriting(enabled: Boolean) {
        viewModelScope.launch { preferences.setRelatedWhileWriting(enabled) }
    }
}
