package cloud.wafflecommons.pixelbrainreader.data.modes

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import cloud.wafflecommons.pixelbrainreader.data.repository.FileRepository
import cloud.wafflecommons.pixelbrainreader.data.sync.VaultPushScheduler
import cloud.wafflecommons.pixelbrainreader.data.system.AppClock
import cloud.wafflecommons.pixelbrainreader.di.ApplicationScope
import cloud.wafflecommons.pixelbrainreader.widget.ui.WidgetUpdateManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.IOException
import java.time.LocalDateTime
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

private val Context.modesStore: DataStore<Preferences> by preferencesDataStore(name = "context_modes")

/**
 * Work / Personal / Off. The configuration (work folders, schedule, leaves, key dates) lives in the
 * vault so it syncs; the manual choice and screen-share-safe flag are this device's own state.
 * [visibility] is the one policy every surface filters with.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class ModesRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val fileRepository: FileRepository,
    private val pushScheduler: VaultPushScheduler,
    appClock: AppClock,
    @param:ApplicationScope private val appScope: CoroutineScope,
    private val widgetUpdateManager: dagger.Lazy<WidgetUpdateManager>
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }
    private val writeMutex = Mutex()

    private val _config = MutableStateFlow(ModesConfig())
    val config: StateFlow<ModesConfig> = _config.asStateFlow()

    private val devicePrefs: Flow<Preferences> = context.modesStore.data.catch { e ->
        if (e is IOException) emit(emptyPreferences()) else throw e
    }

    private val override: Flow<ModeOverride?> = devicePrefs.map(::overrideOf)
    private val screenShareSafe: Flow<Boolean> = devicePrefs.map { it[KEY_SCREEN_SHARE_SAFE] ?: false }

    /** Re-evaluates the schedule every minute while the app is visible. */
    private val ticks: Flow<Long> = appClock.isInForeground.flatMapLatest { foreground ->
        if (!foreground) {
            flowOf(System.currentTimeMillis())
        } else {
            flow {
                while (true) {
                    val now = System.currentTimeMillis()
                    emit(now)
                    delay(MINUTE_MS - now % MINUTE_MS)
                }
            }
        }
    }

    val visibility: StateFlow<Visibility> = combine(_config, override, screenShareSafe, ticks) { cfg, ov, safe, _ ->
        compute(cfg, ov, safe)
    }.stateIn(appScope, SharingStarted.Eagerly, Visibility.ALL)

    init {
        appScope.launch { reload() }
    }

    /** Re-reads the configuration from the vault (at start and after every sync). */
    suspend fun reload() = withContext(Dispatchers.IO) {
        readFromDisk()?.let { _config.value = it }
    }

    /** For workers and widgets: the policy right now, without waiting for the flows. */
    suspend fun currentVisibility(): Visibility = withContext(Dispatchers.IO) {
        val cfg = readFromDisk() ?: _config.value
        val prefs = devicePrefs.first()
        compute(cfg, overrideOf(prefs), prefs[KEY_SCREEN_SHARE_SAFE] ?: false)
    }

    /** Applies [transform] to the configuration ON DISK (settled decision #8) and syncs it. */
    suspend fun updateConfig(transform: (ModesConfig) -> ModesConfig) = withContext(Dispatchers.IO) {
        writeMutex.withLock {
            val base = readFromDisk() ?: if (fileRepository.fileExists(PATH)) {
                // Unreadable: move it aside before rewriting, never overwrite it.
                fileRepository.moveAsideCorrupt(PATH)
                _config.value
            } else {
                _config.value
            }
            val updated = transform(base)
            fileRepository.saveFileLocally(PATH, json.encodeToString(ModesConfig.serializer(), updated) + "\n")
            _config.value = updated
            pushScheduler.requestPush()
            // The quick-actions widget shows the toolkit's name.
            if (updated.toolkitName != base.toolkitName) widgetUpdateManager.get().triggerUpdate()
        }
    }

    /** A manual mode, until the next schedule change or midnight. */
    suspend fun setManualMode(mode: ContextMode) {
        val now = LocalDateTime.now()
        val until = ModeResolver.overrideUntil(_config.value, now).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        context.modesStore.edit {
            it[KEY_MANUAL_MODE] = mode.name
            it[KEY_MANUAL_UNTIL] = until
        }
    }

    suspend fun clearManualMode() {
        context.modesStore.edit {
            it.remove(KEY_MANUAL_MODE)
            it.remove(KEY_MANUAL_UNTIL)
        }
    }

    suspend fun setScreenShareSafe(enabled: Boolean) {
        context.modesStore.edit { it[KEY_SCREEN_SHARE_SAFE] = enabled }
        // The health, companion and mood widgets hide their figures too: rebuild and re-render them.
        widgetUpdateManager.get().scheduleSnapshotUpdate()
        widgetUpdateManager.get().triggerUpdate()
    }

    private fun compute(cfg: ModesConfig, ov: ModeOverride?, safe: Boolean): Visibility {
        val resolved = ModeResolver.resolve(cfg, ov, LocalDateTime.now(), ZoneId.systemDefault())
        return Visibility(resolved.mode, resolved.source, safe, cfg.workFolders, resolved.leave, cfg.appearance())
    }

    private fun overrideOf(prefs: Preferences): ModeOverride? {
        val mode = prefs[KEY_MANUAL_MODE]?.let { name -> ContextMode.entries.firstOrNull { it.name == name } } ?: return null
        return ModeOverride(mode, prefs[KEY_MANUAL_UNTIL] ?: 0L)
    }

    /** Null when the file is missing or unreadable. */
    private suspend fun readFromDisk(): ModesConfig? {
        val raw = fileRepository.readFile(PATH) ?: return null
        return try {
            json.decodeFromString(ModesConfig.serializer(), raw)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Modes configuration unreadable; keeping the current one", e)
            null
        }
    }

    companion object {
        const val PATH = "10_Journal/system/context_modes.json"
        private const val TAG = "ModesRepository"
        private const val MINUTE_MS = 60_000L
        private val KEY_MANUAL_MODE = stringPreferencesKey("manual_mode")
        private val KEY_MANUAL_UNTIL = longPreferencesKey("manual_until")
        private val KEY_SCREEN_SHARE_SAFE = booleanPreferencesKey("screen_share_safe")
    }
}
