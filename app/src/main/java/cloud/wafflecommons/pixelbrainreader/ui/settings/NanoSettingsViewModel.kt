package cloud.wafflecommons.pixelbrainreader.ui.settings

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cloud.wafflecommons.pixelbrainreader.data.ai.LocalAiManager
import cloud.wafflecommons.pixelbrainreader.data.ai.NanoSelfTest
import cloud.wafflecommons.pixelbrainreader.data.ai.NanoState
import cloud.wafflecommons.pixelbrainreader.data.device.VaultWriteGate
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Settings › Gemini Nano (13.4): the on-device model's state, its download and storage, the self-test. */
@HiltViewModel
class NanoSettingsViewModel @Inject constructor(
    private val localAiManager: LocalAiManager
) : ViewModel() {

    /** Mirrors [LocalAiManager.nanoState]. */
    val nanoState: StateFlow<NanoState> = localAiManager.nanoState

    /** The latest self-test, this one's or the one that ran after a download. */
    val selfTest: StateFlow<NanoSelfTest?> = localAiManager.selfTest

    private val _isSelfTestRunning = MutableStateFlow(false)
    val isSelfTestRunning: StateFlow<Boolean> = _isSelfTestRunning.asStateFlow()

    /** The user's own download (settled decision #1: it also starts by itself when ML Kit offers it). */
    fun download() {
        localAiManager.downloadModel()
    }

    /** AICore's system page, where the model's storage is managed (ML Kit has no delete). */
    fun openStorage() {
        localAiManager.openAicoreSettings()
    }

    fun runSelfTest() {
        // No AI on a read-only device: the page offers no test there.
        if (VaultWriteGate.readOnly || _isSelfTestRunning.value) return
        viewModelScope.launch {
            _isSelfTestRunning.value = true
            try {
                localAiManager.runSelfTest()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "The self-test couldn't run: ${e.javaClass.simpleName}")
            } finally {
                _isSelfTestRunning.value = false
            }
        }
    }

    private companion object {
        const val TAG = "NanoSettingsViewModel"
    }
}
