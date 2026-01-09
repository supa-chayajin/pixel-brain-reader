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
    private val secretManager: SecretManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(DailyNoteState())
    val uiState: StateFlow<DailyNoteState> = _uiState.asStateFlow()

    init {
        loadData()
        observeUpdates()
        checkAndCreateDailyNote()
    }

    private fun observeUpdates() {
        viewModelScope.launch {
            fileRepository.fileUpdates.collect { path: String ->
                // Check if the updated file is relevant (Current Daily Note OR Mood JSON)
                val today = LocalDate.now().format(DateTimeFormatter.ISO_DATE)
                val notePath = "10_Journal/$today.md"
                
                // Relaxed check: Is it a json file (Mood) or likely the daily note?
                if (path == notePath || path.endsWith(".json")) {
                   loadData()
                }
            }
        }
    }

    fun loadData() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            
            val today = LocalDate.now()
            val formattedDate = today.format(DateTimeFormatter.ISO_DATE)
            val notePath = "10_Journal/$formattedDate.md"

            // Step A: Fetch Mood Data
            val moodFlow = moodRepository.getDailyMood(today)

            // Step B: Fetch Markdown Content
            val contentFlow = fileRepository.getFileContentFlow(notePath)

            combine(moodFlow, contentFlow) { mood, content ->
                var displayContent = ""
                var weather: WeatherData? = null
                
                if (content != null) {
                    val frontmatter = FrontmatterManager.extractFrontmatter(content)
                    // Usage of Strict Split Logic
                    displayContent = FrontmatterManager.stripFrontmatter(content)
                    
                    // Weather Logic: Read from Frontmatter OR Fetch
                    val weatherEmoji = frontmatter["weather"]
                    val temp = frontmatter["temperature"]
                    val loc = frontmatter["location"]
                    
                    if (!weatherEmoji.isNullOrEmpty() && !temp.isNullOrEmpty()) {
                        // 1. Data Exists in File -> Use it (Source of Truth)
                        weather = WeatherData(weatherEmoji, temp, loc, "Saved")
                    } else {
                        // 2. Data Missing -> Auto-Fetch & Save
                        launch { fetchAndSaveWeather(today, notePath, content, frontmatter) }
                    }
                } 
                
                DailyNoteState(
                    date = today,
                    moodData = mood,
                    noteContent = displayContent,
                    weatherData = weather,
                    isLoading = false
                )
            }.catch { e ->
                _uiState.value = _uiState.value.copy(isLoading = false)
            }.collect { newState ->
                _uiState.value = newState
            }
        }
    }

    private suspend fun fetchAndSaveWeather(date: LocalDate, path: String, currentContent: String, currentFrontmatter: Map<String, String>) {
         // Avoid Fetch Loop: Check if we just tried? (Or rely on State not updating immediately?)
         
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
             
             // Update Content string with new Frontmatter (Using strict Injection)
             val updatedContent = FrontmatterManager.injectWeather(currentContent, updates)
             
             // Optimization: Check if content changed before triggering Save/Sync
             if (updatedContent != currentContent) {
                 // 1. Save locally (Persist + UI Update via Flow)
                 fileRepository.saveFileLocally(path, updatedContent)
                 
                 // 2. Git Sync (Push)
                 val (owner, repo) = secretManager.getRepoInfo()
                 if (!owner.isNullOrEmpty() && !repo.isNullOrEmpty()) {
                     try {
                         val customMessage = "docs(journal): add weather data for ${date}"
                         val pushResult = fileRepository.pushDirtyFiles(owner, repo, customMessage)
                         if (pushResult.isFailure) {
                             android.util.Log.e("DailyNoteVM", "Weather Push Failed: ${pushResult.exceptionOrNull()?.message}")
                         }
                     } catch (e: Exception) {
                         android.util.Log.e("DailyNoteVM", "Weather Push Exception", e)
                     }
                 }
             } else {
                 android.util.Log.d("DailyNoteVM", "Weather data unchanged, skipping save/sync.")
             }
         }
    }

    
    // Refresh retained for manual calls if needed, but Reactive loop should handle most.
    fun refresh() {
        loadData()
        checkAndCreateDailyNote()
    }

    private fun checkAndCreateDailyNote() {
        viewModelScope.launch {
            val today = LocalDate.now().format(DateTimeFormatter.ISO_DATE)
            val notePath = "10_Journal/$today.md"
            
            // Check existence logic: 
            // We can try to read it. If flow returns null or entity missing?
            // Repository.getFileContentFlow returns Flow<String?>. First emission.
            
            val content = fileRepository.getFileContentFlow(notePath).firstOrNull()
            
            if (content == null) {
                // File likely doesn't exist. Create it!
                // Ensure directory exists first?
                fileRepository.createLocalFolder("10_Journal")
                
                val defaultContent = """
                    ---
                    mood: 
                    weather: 
                    temperature: 
                    location: 
                    tags: [daily]
                    ---
                    
                    # Daily Note: $today
                    
                    Start your day here...
                """.trimIndent()
                
                fileRepository.saveFileLocally(notePath, defaultContent)
                
                 // Trigger Sync (Push) for the new file
                 val (owner, repo) = secretManager.getRepoInfo()
                 if (owner != null && repo != null) {
                     fileRepository.pushDirtyFiles(owner, repo, "docs(daily): create $today.md")
                 }
                 
                 // Refresh Data
                 loadData()
            }
        }
    }
}
