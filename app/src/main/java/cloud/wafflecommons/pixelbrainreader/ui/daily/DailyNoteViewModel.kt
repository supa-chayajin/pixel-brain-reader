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
    val noteIntro: String = "",
    val noteOutro: String = "",
    val metadata: Map<String, String> = emptyMap(),
     // Deprecating single noteContent in favor of split
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
     */
    fun loadDailyNote(date: LocalDate) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)

            // STEP 1: SYNC FIRST (Blocking)
            val (owner, repo) = secretManager.getRepoInfo()
            if (owner != null && repo != null) {
                try {
                    fileRepository.syncRepository(owner, repo)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            // STEP 2: CHECK EXISTENCE
            val exists = dailyNoteRepository.hasNote(date)

            // STEP 3: CREATE ONLY IF TRULY MISSING
            if (!exists) {
                try {
                    val templatePath = "99_System/Templates/T_Daily_Journal.md"
                    var rawTemplate = templateRepository.getTemplateContent(templatePath)
                    
                    if (rawTemplate.isNullOrBlank()) {
                         rawTemplate = """
                             # Journal {{date}}
                             
                             ## 📝 Journal
                             
                             ## 🧠 Idées / Second Cerveau
                         """.trimIndent()
                    }

                    val title = date.format(DateTimeFormatter.ISO_DATE)
                    val processedContent = cloud.wafflecommons.pixelbrainreader.data.utils.TemplateEngine.apply(rawTemplate, title)

                    dailyNoteRepository.createDailyNote(date, processedContent, owner, repo)

                } catch (e: Exception) {
                    android.util.Log.e("DailyNoteVM", "Failed to create note", e)
                }
            }

            // STEP 4: LOAD FINAL CONTENT
            reloadDataOnly(date)
        }
    }
    
    private fun reloadDataOnly(date: LocalDate = LocalDate.now()) {
        viewModelScope.launch {
            val formattedDate = date.format(DateTimeFormatter.ISO_DATE)
            val notePath = "10_Journal/$formattedDate.md"

            // Parallel Fetch
            val moodFlow = moodRepository.getDailyMood(date)
            val contentFlow = fileRepository.getFileContentFlow(notePath)

            combine(moodFlow, contentFlow) { mood, content ->
                var intro = ""
                var outro = ""
                var weather: WeatherData? = null
                
                if (content != null) {
                    val frontmatter = FrontmatterManager.extractFrontmatter(content)
                    // We parse the raw content for split (strip frontmatter logic handled implicitly inside parser? 
                    // No, FrontmatterManager.stripFrontmatter returns body. We split THAT body.)
                    val body = FrontmatterManager.stripFrontmatter(content)
                    
                    val parsed = parseSplitContent(body)
                    // Visual Fix: Remove redundant date header from intro (e.g. # 📅 2026-01-09)
                    intro = parsed.first.replaceFirst(Regex("^# 📅 \\d{4}-\\d{2}-\\d{2}\\s*"), "").trim()
                    outro = parsed.second
                    
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
                    noteIntro = intro,
                    noteOutro = outro,
                    metadata = if (content != null) FrontmatterManager.extractFrontmatter(content) else emptyMap(),
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

    private fun parseSplitContent(content: String): Pair<String, String> {
        val startHeader = "## \uD83D\uDCDD Journal"
        val endHeader = "## \uD83E\uDDE0 Idées / Second Cerveau"
        
        val lines = content.lines()
        val startIndex = lines.indexOfFirst { it.trim().startsWith(startHeader) }
        val endIndex = lines.indexOfFirst { it.trim().startsWith(endHeader) }
        
        // If headers not found, return full content as Intro ? Or adhere to "Tasks ONLY in Timeline"?
        // If Start Missing: Display Full Content as Intro.
        if (startIndex == -1) {
             return content to ""
        }
        
        // Intro = Lines BEFORE StartHeader
        val introText = lines.subList(0, startIndex).joinToString("\n")
        
        // Tasks are between Start and End. We DON'T return them as text here, 
        // because TaskTimeline reads the file directly via Repository.
        // Wait, TaskTimeline reads from Repo. Visual VM just needs to NOT show duplication.
        // So we skip the lines between start and end.
        
        val effectiveEndIndex = if (endIndex == -1) lines.size else endIndex
        
        // Outro = Lines AFTER EndHeader (inclusive of EndHeader? usually headers stay with content)
        // User said: "Extract text after ## 🧠 Idées"
        val outroText = if (endIndex != -1) {
             lines.subList(endIndex, lines.size).joinToString("\n")
        } else {
             ""
        }
        
        return introText to outroText
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
