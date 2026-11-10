package cloud.wafflecommons.pixelbrainreader.ui.daily

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cloud.wafflecommons.pixelbrainreader.data.modes.ModesRepository
import cloud.wafflecommons.pixelbrainreader.data.work.DailyProjects
import cloud.wafflecommons.pixelbrainreader.data.work.ProjectArrangementRepository
import cloud.wafflecommons.pixelbrainreader.data.work.ProjectRepository
import cloud.wafflecommons.pixelbrainreader.data.work.ProjectSummary
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The Daily screen's Projects block ([DailyProjects]): read when the screen shows and again when
 * the mode changes (work folders hide outside Work mode). It reads only: pins and hides are the
 * Projects tab's.
 */
@HiltViewModel
class DailyProjectsViewModel @Inject constructor(
    private val projectRepository: ProjectRepository,
    private val arrangementRepository: ProjectArrangementRepository,
    modesRepository: ModesRepository
) : ViewModel() {

    private val _projects = MutableStateFlow<List<ProjectSummary>>(emptyList())
    val projects: StateFlow<List<ProjectSummary>> = _projects.asStateFlow()

    private val _isPinned = MutableStateFlow(false)
    /** The cards are the pinned projects (else the most active ones, and the block says how to choose). */
    val isPinned: StateFlow<Boolean> = _isPinned.asStateFlow()

    init {
        modesRepository.visibility
            .map { it.hideWork }
            .distinctUntilChanged()
            .onEach { reload() }
            .launchIn(viewModelScope)
    }

    fun reload() {
        viewModelScope.launch {
            try {
                val arranged = arrangementRepository.read().arrange(projectRepository.projects())
                _isPinned.value = arranged.pinned.isNotEmpty()
                _projects.value = DailyProjects.pick(arranged)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The block just stays as it was: the Projects tab says what went wrong.
            }
        }
    }
}
