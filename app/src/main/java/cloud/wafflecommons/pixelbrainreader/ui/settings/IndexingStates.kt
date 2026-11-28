package cloud.wafflecommons.pixelbrainreader.ui.settings

import androidx.work.WorkInfo
import cloud.wafflecommons.pixelbrainreader.data.workers.IndexingWorker

/**
 * Live state of the vault's indexing job (Settings › Knowledge vault, 13.4: moved out of the 12.x
 * SettingsViewModel unchanged).
 *
 * Reflects WorkManager's view of [IndexingWorker.UNIQUE_WORK_NAME] across process death: if the user
 * backgrounds the app while indexing is running and comes back, the page re-attaches to the same
 * WorkInfo flow and keeps showing the running state until the worker actually finishes.
 */
sealed class IndexingState {
    data object Idle : IndexingState()
    data object Enqueued : IndexingState()
    /** [notesDone] of [notesTotal] notes embedded by the current run (0 of 0 before its first report). */
    data class Running(val notesDone: Int = 0, val notesTotal: Int = 0) : IndexingState()
    data object Succeeded : IndexingState()
    data class Failed(val reason: String) : IndexingState()
}

/**
 * The indexing state across every run under the unique name. A long index is a chain of runs (each
 * hands over to the next before the system's job limit): a finished run next to a queued or running
 * one means indexing is still going.
 */
internal fun indexingStateOf(infos: List<WorkInfo>): IndexingState {
    infos.firstOrNull { it.state == WorkInfo.State.RUNNING }?.let { running ->
        return IndexingState.Running(
            notesDone = running.progress.getInt(IndexingWorker.KEY_NOTES_DONE, 0),
            notesTotal = running.progress.getInt(IndexingWorker.KEY_NOTES_TOTAL, 0)
        )
    }
    if (infos.any { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.BLOCKED }) {
        return IndexingState.Enqueued
    }
    infos.firstOrNull { it.state == WorkInfo.State.FAILED }?.let { failed ->
        return IndexingState.Failed(failed.outputData.getString(IndexingWorker.KEY_ERROR) ?: "Indexing failed")
    }
    if (infos.any { it.state == WorkInfo.State.CANCELLED }) return IndexingState.Failed("Indexing cancelled")
    if (infos.any { it.state == WorkInfo.State.SUCCEEDED }) return IndexingState.Succeeded
    return IndexingState.Idle
}
