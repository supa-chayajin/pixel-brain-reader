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

import cloud.wafflecommons.pixelbrainreader.data.gamification.Attribute
import cloud.wafflecommons.pixelbrainreader.data.local.preferences.GamificationPreferences
import cloud.wafflecommons.pixelbrainreader.domain.gamification.GrantXpUseCase
import dagger.Lazy
import kotlinx.coroutines.flow.first

@Singleton
class GamificationRepository @Inject constructor(
    private val fileRepository: FileRepository,
    private val gamificationPreferences: GamificationPreferences,
    private val lazyGrantXpUseCase: Lazy<GrantXpUseCase>
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
        val disk = readStateFromDisk()
        if (disk != null) {
            _gamificationState.value = disk
        } else {
            // No valid file yet — persist the current (default) so the file exists.
            saveState(_gamificationState.value)
        }
    }

    suspend fun getStateSnapshot(): GamificationState = withContext(Dispatchers.IO) {
        readStateFromDisk() ?: GamificationState() // Fallback
    }

    /** Reads the persisted profile fresh from disk, or null if it is absent / blank / corrupt. */
    private fun readStateFromDisk(): GamificationState? {
        return try {
            val file = fileRepository.getLocalFile(profileFile)
            if (!file.exists()) return null
            val content = file.readText()
            if (content.isBlank()) null else jsonParser.decodeFromString<GamificationState>(content)
        } catch (e: Exception) {
            if (cloud.wafflecommons.pixelbrainreader.BuildConfig.DEBUG) {
                Log.e("Gamification", "Error reading profile from disk", e)
            }
            null
        }
    }

    suspend fun updateState(transform: (GamificationState) -> GamificationState) = mutex.withLock {
        withContext(Dispatchers.IO) {
            // CRITICAL: base the mutation on the freshest ON-DISK state, not a possibly-unhydrated
            // in-memory default. A widget ActionCallback (or any caller) can run before loadState()
            // has hydrated _gamificationState, so it would still hold the default; mutating THAT and
            // saving it silently WIPES the real saved profile (this actually happened — a single
            // widget chore reset a level-9 profile to level 1). Reading disk first makes every
            // mutation additive to persisted progress. `mutex` serialises writes within the process,
            // and Glance callbacks share this same process, so disk is always current here.
            val current = readStateFromDisk() ?: _gamificationState.value
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

    suspend fun processTaskCompletion(taskContent: String) {
        val tags = extractTags(taskContent)
        val tagMappings = gamificationPreferences.tagToStatMappingFlow.first()

        tags.forEach { tag ->
            val attribute = tagMappings[tag] ?: Attribute.VIG // Default to VIGOR if not found

            updateState { state ->
                val newAttributes = state.attributes.toMutableMap()
                val currentAttrVal = newAttributes[attribute] ?: 0
                newAttributes[attribute] = currentAttrVal + 1

                val currentProfile = state.profile
                var newXp = currentProfile.currentXp + 20.0 // 20 XP for task
                var newLevel = currentProfile.level
                var newXpTarget = currentProfile.xpToNextLevel

                if (newXp >= newXpTarget) {
                    newXp -= newXpTarget
                    newLevel++
                    newXpTarget = cloud.wafflecommons.pixelbrainreader.data.gamification.XpCalculator.getXpForNextLevel(newLevel)
                }

                val newProfile = currentProfile.copy(
                    level = newLevel,
                    currentXp = newXp,
                    xpToNextLevel = newXpTarget
                )

                val historyEntry = cloud.wafflecommons.pixelbrainreader.data.gamification.XpGainEntry(
                    timestamp = System.currentTimeMillis(),
                    amount = 20.0,
                    source = "Task Completed: $tag",
                    attribute = attribute
                )

                state.copy(
                    profile = newProfile,
                    attributes = newAttributes,
                    history = (state.history + historyEntry).takeLast(50)
                )
            }
        }
    }

    private fun extractTags(content: String): List<String> {
        val regex = Regex("#\\w+")
        return regex.findAll(content).map { it.value }.toList()
    }
}
