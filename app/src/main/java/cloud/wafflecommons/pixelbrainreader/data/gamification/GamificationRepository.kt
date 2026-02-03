package cloud.wafflecommons.pixelbrainreader.data.gamification

import android.util.Log
import cloud.wafflecommons.pixelbrainreader.data.repository.FileRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GamificationRepository @Inject constructor(
    private val fileRepository: FileRepository
) {
    private val _gamificationState = MutableStateFlow(GamificationState())
    val gamificationState: Flow<GamificationState> = _gamificationState.asStateFlow()

    private val systemDir = "10_Journal/system"
    private val profileFile = "$systemDir/gamification_profile.json"
    private val mutex = Mutex()

    private val jsonParser = Json {
        ignoreUnknownKeys = true
        isLenient = true
        prettyPrint = true
        encodeDefaults = true
    }

    init {
        // Load on init? Or explicit load?
        // Let's do lazy load via a suspend fun or Fire-and-forget in scope?
        // Better: Expose a load function or have User call it. 
        // We'll load immediately in a coroutine but we need a scope.
        // Usually Repositories shouldn't launch, but we can have a suspend init.
    }

    suspend fun loadState() = withContext(Dispatchers.IO) {
        val file = fileRepository.getLocalFile(profileFile)
        if (file.exists()) {
            try {
                val content = file.readText()
                if (content.isNotBlank()) {
                    val state = jsonParser.decodeFromString<GamificationState>(content)
                    _gamificationState.value = state
                }
            } catch (e: Exception) {
                if (cloud.wafflecommons.pixelbrainreader.BuildConfig.DEBUG) {
                    Log.e("Gamification", "Error loading profile", e)
                }
                // Keep default
            }
        } else {
            // Create default
            saveState(_gamificationState.value)
        }
    }

    suspend fun updateState(transform: (GamificationState) -> GamificationState) = mutex.withLock {
        withContext(Dispatchers.IO) {
            val current = _gamificationState.value
            val newState = transform(current)
            _gamificationState.value = newState
            saveState(newState)
        }
    }

    private suspend fun saveState(state: GamificationState) {
        try {
            val json = jsonParser.encodeToString(state)
            fileRepository.createLocalFolder(systemDir)
            fileRepository.saveFileLocally(profileFile, json)
        } catch (e: Exception) {
            if (cloud.wafflecommons.pixelbrainreader.BuildConfig.DEBUG) {
                Log.e("Gamification", "Error saving profile", e)
            }
        }
    }
}
