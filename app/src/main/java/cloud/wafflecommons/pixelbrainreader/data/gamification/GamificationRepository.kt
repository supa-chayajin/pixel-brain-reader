package cloud.wafflecommons.pixelbrainreader.data.gamification

import android.util.Log
import cloud.wafflecommons.pixelbrainreader.data.local.preferences.GamificationPreferences
import cloud.wafflecommons.pixelbrainreader.data.repository.FileRepository
import cloud.wafflecommons.pixelbrainreader.widget.ui.WidgetUpdateManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GamificationRepository @Inject constructor(
    private val fileRepository: FileRepository,
    private val gamificationPreferences: GamificationPreferences,
    private val widgetUpdateManager: WidgetUpdateManager,
    private val pushScheduler: cloud.wafflecommons.pixelbrainreader.data.sync.VaultPushScheduler
) {
    private val _gamificationState = MutableStateFlow(GamificationState())
    val gamificationState: Flow<GamificationState> = _gamificationState.asStateFlow()

    // Level-ups as a domain event, so XP granted anywhere (habits, widgets, health) can be celebrated
    // by whichever UI is showing — without the domain layer depending on UI classes.
    private val _levelUps = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val levelUps: SharedFlow<Unit> = _levelUps.asSharedFlow()

    fun notifyLevelUp() {
        _levelUps.tryEmit(Unit)
    }

    private val systemDir = "10_Journal/system"
    private val profileFile = "$systemDir/gamification_profile.json"
    private val mutex = Mutex()

    private val jsonParser = Json {
        ignoreUnknownKeys = true
        isLenient = true
        prettyPrint = true
        encodeDefaults = true
    }

    /** What is on disk. "Absent" and "unreadable" must never be conflated: only absent may be seeded. */
    private sealed interface DiskProfile {
        data object Absent : DiskProfile

        /** Present but not parseable (merge-conflict markers, a hand edit, …). */
        data object Corrupt : DiskProfile

        /** Present but could not be read right now (I/O error). */
        data object Unavailable : DiskProfile

        data class Loaded(val state: GamificationState) : DiskProfile
    }

    /**
     * Hydrates the in-memory profile from disk. The file is only created when it does not exist: a
     * profile that exists but cannot be read is left untouched — this used to overwrite it with a
     * level-1 default.
     */
    suspend fun loadState() = mutex.withLock {
        withContext(Dispatchers.IO) {
            when (val disk = readProfile()) {
                is DiskProfile.Loaded -> {
                    _gamificationState.value = disk.state
                }
                DiskProfile.Absent -> {
                    saveState(_gamificationState.value)
                }
                DiskProfile.Corrupt, DiskProfile.Unavailable -> {
                    Log.e(TAG, "Gamification profile unreadable ($disk); left untouched")
                }
            }
        }
    }

    suspend fun getStateSnapshot(): GamificationState = withContext(Dispatchers.IO) {
        (readProfile() as? DiskProfile.Loaded)?.state ?: _gamificationState.value
    }

    private fun readProfile(): DiskProfile {
        val file = fileRepository.getLocalFile(profileFile)
        if (!file.exists()) return DiskProfile.Absent
        val content = try {
            file.readText()
        } catch (e: IOException) {
            Log.e(TAG, "Could not read the gamification profile", e)
            return DiskProfile.Unavailable
        }
        if (content.isBlank()) return DiskProfile.Absent
        return try {
            DiskProfile.Loaded(jsonParser.decodeFromString<GamificationState>(content))
        } catch (e: Exception) {
            Log.e(TAG, "The gamification profile is not valid JSON", e)
            DiskProfile.Corrupt
        }
    }

    suspend fun updateState(transform: (GamificationState) -> GamificationState) = mutex.withLock {
        withContext<Unit>(Dispatchers.IO) {
            // CRITICAL: base the mutation on the freshest ON-DISK state, not a possibly-unhydrated
            // in-memory default. A widget ActionCallback (or any caller) can run before loadState()
            // has hydrated _gamificationState, so it would still hold the default; mutating THAT and
            // saving it silently WIPES the real saved profile (this actually happened — a single
            // widget chore reset a level-9 profile to level 1). Reading disk first makes every
            // mutation additive to persisted progress. `mutex` serialises writes within the process,
            // and Glance callbacks share this same process, so disk is always current here.
            val current = when (val disk = readProfile()) {
                is DiskProfile.Loaded -> disk.state
                DiskProfile.Absent -> _gamificationState.value
                DiskProfile.Corrupt -> {
                    // Never write over a profile we cannot parse: move it aside first so the saved
                    // progress stays recoverable, then build on the best state we have.
                    val backup = fileRepository.moveAsideCorrupt(profileFile)
                    if (backup == null) {
                        Log.e(TAG, "Gamification update skipped: the unparseable profile could not be preserved")
                        return@withContext
                    }
                    Log.e(TAG, "Unparseable gamification profile moved aside to ${backup.name}")
                    _gamificationState.value
                }
                DiskProfile.Unavailable -> {
                    Log.e(TAG, "Gamification update skipped: the profile could not be read")
                    return@withContext
                }
            }
            val newState = transform(current)
            _gamificationState.value = newState
            saveState(newState)
            // Every XP/level/attribute change flows through here — refresh the widgets so the
            // Companion rings, level bar, etc. reflect the new state (coalesced snapshot rebuild).
            runCatching { widgetUpdateManager.scheduleSnapshotUpdate() }
        }
    }

    private suspend fun saveState(state: GamificationState) {
        try {
            fileRepository.createLocalFolder(systemDir)
            fileRepository.saveFileLocally(profileFile, jsonParser.encodeToString(state))
            pushScheduler.requestPush()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Error saving profile", e)
        }
    }

    suspend fun processTaskCompletion(taskContent: String) {
        val tags = TAG_REGEX.findAll(taskContent).map { it.value }.toList()
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

                while (newXp >= newXpTarget) {
                    newXp -= newXpTarget
                    newLevel++
                    newXpTarget = XpCalculator.getXpForNextLevel(newLevel)
                }

                val newProfile = currentProfile.copy(
                    level = newLevel,
                    currentXp = newXp,
                    xpToNextLevel = newXpTarget
                )

                val historyEntry = XpGainEntry(
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

    private companion object {
        const val TAG = "Gamification"
        val TAG_REGEX = Regex("#\\w+")
    }
}
