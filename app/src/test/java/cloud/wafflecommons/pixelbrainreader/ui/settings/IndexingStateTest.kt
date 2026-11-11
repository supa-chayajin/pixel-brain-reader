package cloud.wafflecommons.pixelbrainreader.ui.settings

import androidx.work.Data
import androidx.work.WorkInfo
import androidx.work.workDataOf
import cloud.wafflecommons.pixelbrainreader.data.workers.IndexingWorker
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.UUID

/**
 * A long index is a chain of runs, each handing over before the system's job limit: Settings shows
 * it as one indexing that is still going, with the current run's progress.
 */
class IndexingStateTest {

    private fun info(state: WorkInfo.State, output: Data = Data.EMPTY, progress: Data = Data.EMPTY) =
        WorkInfo(UUID.randomUUID(), state, emptySet(), outputData = output, progress = progress)

    private fun stateOf(vararg infos: WorkInfo) = SettingsViewModel.indexingStateOf(infos.toList())

    @Test
    fun `a running run shows its progress`() {
        val progress = workDataOf(IndexingWorker.KEY_NOTES_DONE to 12, IndexingWorker.KEY_NOTES_TOTAL to 340)
        assertEquals(
            SettingsViewModel.IndexingState.Running(notesDone = 12, notesTotal = 340),
            stateOf(info(WorkInfo.State.SUCCEEDED), info(WorkInfo.State.RUNNING, progress = progress))
        )
    }

    @Test
    fun `a finished run that handed over is still indexing`() {
        assertEquals(SettingsViewModel.IndexingState.Enqueued, stateOf(info(WorkInfo.State.SUCCEEDED), info(WorkInfo.State.ENQUEUED)))
        assertEquals(SettingsViewModel.IndexingState.Enqueued, stateOf(info(WorkInfo.State.BLOCKED), info(WorkInfo.State.SUCCEEDED)))
    }

    @Test
    fun `the end of the chain is the result`() {
        assertEquals(SettingsViewModel.IndexingState.Succeeded, stateOf(info(WorkInfo.State.SUCCEEDED), info(WorkInfo.State.SUCCEEDED)))
        assertEquals(
            SettingsViewModel.IndexingState.Failed("The embedding model could not start"),
            stateOf(info(WorkInfo.State.FAILED, output = workDataOf(IndexingWorker.KEY_ERROR to "The embedding model could not start")))
        )
        assertEquals(SettingsViewModel.IndexingState.Failed("Indexing cancelled"), stateOf(info(WorkInfo.State.CANCELLED)))
        assertEquals(SettingsViewModel.IndexingState.Idle, stateOf())
    }
}
