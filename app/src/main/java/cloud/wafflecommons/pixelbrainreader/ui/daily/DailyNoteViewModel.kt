package cloud.wafflecommons.pixelbrainreader.ui.daily

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cloud.wafflecommons.pixelbrainreader.data.repository.DailyMoodData
import cloud.wafflecommons.pixelbrainreader.data.repository.FileRepository
import cloud.wafflecommons.pixelbrainreader.data.repository.MoodRepository
import cloud.wafflecommons.pixelbrainreader.data.utils.FrontmatterManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import javax.inject.Inject

    // ... imports
import cloud.wafflecommons.pixelbrainreader.data.repository.WeatherData
import cloud.wafflecommons.pixelbrainreader.data.repository.WeatherRepository
import cloud.wafflecommons.pixelbrainreader.data.local.security.SecretManager


data class DailyNoteState(
    val date: LocalDate = LocalDate.now(),
    val moodData: DailyMoodData? = null,
    val noteContent: String = "",
    val weatherData: WeatherData? = null,
    val isLoading: Boolean = true
)



@HiltViewModel
class DailyNoteViewModel @Inject constructor(
    private val moodRepository: MoodRepository,
    private val fileRepository: FileRepository,
    private val weatherRepository: WeatherRepository,
    private val secretManager: SecretManager,
    private val dailyNoteRepository: cloud.wafflecommons.pixelbrainreader.data.repository.DailyNoteRepository,
    private val templateRepository: cloud.wafflecommons.pixelbrainreader.data.repository.TemplateRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(DailyNoteState())
    val uiState: StateFlow<DailyNoteState> = _uiState.asStateFlow()

    init {
        // On init, we load today's note implicitly
        loadDailyNote(LocalDate.now())
        observeUpdates()
    }

    private fun observeUpdates() {
        viewModelScope.launch {
            fileRepository.fileUpdates.collect { path: String ->
                val today = LocalDate.now().format(DateTimeFormatter.ISO_DATE)
                val notePath = "10_Journal/$today.md"
                // Relaxed check: Is it a json file (Mood) or likely the daily note?
                if (path == notePath || path.endsWith(".json")) {
                   // Only reload data part, don't re-trigger creation logic
                   reloadDataOnly()
                }
            }
        }
    }

    /**
     * Critical Function: Orchestrates the Daily Note loading.
     * 1. Sync (Pull) - BLOCKING
     * 2. Check Existence (DB)
     * 3. Create (if missing) using Template
     * 4. Load Data
     */
    fun loadDailyNote(date: LocalDate) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)

            // STEP 1: SYNC FIRST (Blocking)
            // We MUST wait for the pull to finish to ensure the file system/DB is up to date.
            // If we check 'hasNote' before this finishes, we risk overwriting existing data.
            val (owner, repo) = secretManager.getRepoInfo()
            if (owner != null && repo != null) {
                try {
                    fileRepository.syncRepository(owner, repo)
                } catch (e: Exception) {
                    // If offline or conflict, we still proceed to check local files,
                    // but we logged the error.
                    e.printStackTrace()
                }
            }

            // STEP 2: CHECK EXISTENCE
            // Now that pull is done, is the file actually there?
            val exists = dailyNoteRepository.hasNote(date)

            // STEP 3: CREATE ONLY IF TRULY MISSING
            if (!exists) {
                try {
                    val templatePath = "99_System/Templates/T_Daily_Journal.md"
                    // Try to get template, fallback to safe string if missing
                    var rawTemplate = templateRepository.getTemplateContent(templatePath)
                    
                    if (rawTemplate.isNullOrBlank()) {
                         rawTemplate = """
                             # Journal {{date}}
                             
                             *Created automatically (Template missing)*
                         """.trimIndent()
                    }

                    val title = date.format(DateTimeFormatter.ISO_DATE)
                    val processedContent = cloud.wafflecommons.pixelbrainreader.data.utils.TemplateEngine.apply(rawTemplate, title)

                    // Create & Push
                    dailyNoteRepository.createDailyNote(date, processedContent, owner, repo)

                } catch (e: Exception) {
                    android.util.Log.e("DailyNoteVM", "Failed to create note", e)
                }
            }

            // STEP 4: LOAD FINAL CONTENT
            // Re-read the file (whether it existed or we just created it)
            reloadDataOnly(date)
        }
    }
    
    // Separated logic to just load data (for refresh/updates) without the syncing/creation overhead
    private fun reloadDataOnly(date: LocalDate = LocalDate.now()) {
        viewModelScope.launch {
            val formattedDate = date.format(DateTimeFormatter.ISO_DATE)
            val notePath = "10_Journal/$formattedDate.md"

            // Parallel Fetch
            val moodFlow = moodRepository.getDailyMood(date)
            val contentFlow = fileRepository.getFileContentFlow(notePath)

            combine(moodFlow, contentFlow) { mood, content ->
                var displayContent = ""
                var weather: WeatherData? = null
                
                if (content != null) {
                    val frontmatter = FrontmatterManager.extractFrontmatter(content)
                    displayContent = FrontmatterManager.stripFrontmatter(content)
                    
                    val weatherEmoji = frontmatter["weather"]
                    val temp = frontmatter["temperature"]
                    val loc = frontmatter["location"]
                    
                    if (!weatherEmoji.isNullOrEmpty() && !temp.isNullOrEmpty()) {
                        weather = WeatherData(weatherEmoji, temp, loc, "Saved")
                    } else {
                        // Auto-fetch weather if missing and it's today
                        launch { fetchAndSaveWeather(date, notePath, content, frontmatter) }
                    }
                } 
                
                DailyNoteState(
                    date = date,
                    moodData = mood,
                    noteContent = displayContent,
                    weatherData = weather,
                    isLoading = false
                )
            }.catch { 
                _uiState.value = _uiState.value.copy(isLoading = false)
            }.collect { newState ->
                _uiState.value = newState
            }
        }
    }

    private suspend fun fetchAndSaveWeather(date: LocalDate, path: String, currentContent: String, currentFrontmatter: Map<String, String>) {
         val isToday = date == LocalDate.now()
         val result = if (isToday) {
             weatherRepository.getCurrentWeatherAndLocation()
         } else {
             weatherRepository.getHistoricalWeather(date)
         }
         
         if (result != null) {
             val updates = mapOf(
                 "weather" to result.emoji,
                 "temperature" to result.temperature,
                 "location" to (result.location ?: "")
             )
             
             val updatedContent = FrontmatterManager.injectWeather(currentContent, updates)
             
             if (updatedContent != currentContent) {
                 fileRepository.saveFileLocally(path, updatedContent)
                 val (owner, repo) = secretManager.getRepoInfo()
                 if (owner != null && repo != null) {
                     try {
                         fileRepository.pushDirtyFiles(owner, repo, "docs(journal): add weather data for ${date}")
                     } catch (e: Exception) {}
                 }
             }
         }
    }

    fun refresh() {
        // Full reload including sync/check
        loadDailyNote(LocalDate.now())
    }
}
