package cloud.wafflecommons.pixelbrainreader.data.work

import android.content.Context
import cloud.wafflecommons.pixelbrainreader.data.local.security.CryptoManager
import cloud.wafflecommons.pixelbrainreader.data.local.security.SecretManager
import cloud.wafflecommons.pixelbrainreader.data.modes.ModesRepository
import cloud.wafflecommons.pixelbrainreader.data.repository.FileRepository
import cloud.wafflecommons.pixelbrainreader.data.sync.VaultPushScheduler
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.LocalDate
import java.time.YearMonth
import java.util.Arrays
import javax.inject.Inject
import javax.inject.Singleton

/** The encrypted log needs the private vault's password, stored when the vault was last unlocked. */
class VaultPasswordMissingException : Exception("Unlock the private vault once on this phone to use the encrypted log.")

/**
 * The mission log (#1): one markdown file per month, plain in a vault folder or encrypted in the
 * private vault. Every write reads the file from disk first (settled decision #8), inserts or
 * replaces one line, writes atomically and queues a push.
 */
@Singleton
class MissionLogRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val fileRepository: FileRepository,
    private val cryptoManager: CryptoManager,
    private val secretManager: SecretManager,
    private val pushScheduler: VaultPushScheduler,
    private val modesRepository: dagger.Lazy<ModesRepository>
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }
    private val writeMutex = Mutex()

    private val _settings = MutableStateFlow(MissionLogSettings())
    val settings: StateFlow<MissionLogSettings> = _settings.asStateFlow()

    @Volatile
    private var settingsLoaded = false

    private val _changes = MutableStateFlow(0L)

    /** Bumped after every write, so screens re-read. */
    val changes: StateFlow<Long> = _changes.asStateFlow()

    /** Reads the settings from the vault; without a settings file the log goes into the first work folder. */
    suspend fun reloadSettings(): MissionLogSettings = withContext(Dispatchers.IO) {
        val loaded = readSettings() ?: defaultSettings()
        _settings.value = loaded
        settingsLoaded = true
        loaded
    }

    suspend fun updateSettings(transform: (MissionLogSettings) -> MissionLogSettings) = withContext(Dispatchers.IO) {
        writeMutex.withLock {
            val base = readSettings() ?: run {
                if (fileRepository.fileExists(SETTINGS_PATH)) fileRepository.moveAsideCorrupt(SETTINGS_PATH)
                _settings.value
            }
            val updated = transform(base)
            fileRepository.saveFileLocally(SETTINGS_PATH, json.encodeToString(MissionLogSettings.serializer(), updated) + "\n")
            _settings.value = updated
            settingsLoaded = true
            pushScheduler.requestPush()
        }
        _changes.update { it + 1 }
    }

    suspend fun entries(month: YearMonth): List<LogEntry> = withContext(Dispatchers.IO) {
        read(MissionLogFormat.pathFor(currentSettings(), month))?.let(MissionLogFormat::parse).orEmpty()
    }

    /** Every entry from [from] to [to] included, oldest first: the months they span, filtered. */
    suspend fun entriesBetween(from: LocalDate, to: LocalDate): List<LogEntry> = withContext(Dispatchers.IO) {
        generateSequence(YearMonth.from(from)) { it.plusMonths(1) }
            .takeWhile { !it.isAfter(YearMonth.from(to)) }
            .toList()
            .flatMap { entries(it) }
            .filter { it.at.toLocalDate() in from..to }
            .sortedBy { it.at }
    }

    suspend fun add(entry: LogEntry) = mutate(YearMonth.from(entry.at)) { content -> MissionLogFormat.insert(content, entry) }

    /** Replaces [old] by [new]; an entry moved to another month is removed from the old file first. */
    suspend fun update(old: LogEntry, new: LogEntry) {
        if (YearMonth.from(old.at) == YearMonth.from(new.at)) {
            mutate(YearMonth.from(old.at)) { content ->
                MissionLogFormat.replace(content, old, new) ?: throw IllegalStateException("The entry changed on disk; reload the log.")
            }
        } else {
            delete(old)
            add(new.copy(sourceLine = null))
        }
    }

    suspend fun delete(entry: LogEntry) = mutate(YearMonth.from(entry.at)) { content ->
        MissionLogFormat.replace(content, entry, null) ?: throw IllegalStateException("The entry changed on disk; reload the log.")
    }

    private suspend fun mutate(month: YearMonth, change: (String) -> String) = withContext(Dispatchers.IO) {
        writeMutex.withLock {
            val settings = currentSettings()
            val path = MissionLogFormat.pathFor(settings, month)
            val current = read(path) ?: MissionLogFormat.newFile(path, month, LocalDate.now())
            write(path, change(current), settings.encrypted)
        }
        _changes.update { it + 1 }
        pushScheduler.requestPush()
    }

    private suspend fun currentSettings(): MissionLogSettings = if (settingsLoaded) _settings.value else reloadSettings()

    private suspend fun readSettings(): MissionLogSettings? {
        val raw = fileRepository.readFile(SETTINGS_PATH) ?: return null
        return runCatching { json.decodeFromString(MissionLogSettings.serializer(), raw) }.getOrNull()
    }

    private fun defaultSettings(): MissionLogSettings {
        val workFolder = modesRepository.get().config.value.workFolders.firstOrNull()
        return if (workFolder != null) MissionLogSettings(folder = "$workFolder/Mission log") else MissionLogSettings()
    }

    private suspend fun read(path: String): String? {
        if (!path.endsWith(".enc")) return fileRepository.readFile(path)
        val file = File(vaultDir, path)
        if (!file.exists()) return null
        return withPassword { password -> cryptoManager.decrypt(file.readBytes(), password) }
    }

    private suspend fun write(path: String, content: String, encrypted: Boolean) {
        if (!encrypted) {
            fileRepository.saveFileLocally(path, content)
            return
        }
        val bytes = withPassword { password -> cryptoManager.encrypt(content, password) }
        val target = File(vaultDir, path).apply { parentFile?.mkdirs() }
        val staging = File(context.filesDir, "private_staging").apply { mkdirs() }
        val temp = File(staging, "${target.name}.${System.nanoTime()}.tmp")
        try {
            FileOutputStream(temp).use { out ->
                out.write(bytes)
                out.fd.sync()
            }
            try {
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (e: AtomicMoveNotSupportedException) {
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            if (temp.exists()) temp.delete()
        }
    }

    private suspend fun <T> withPassword(block: suspend (CharArray) -> T): T {
        val password = secretManager.getVaultPassword()?.toCharArray() ?: throw VaultPasswordMissingException()
        return try {
            block(password)
        } finally {
            Arrays.fill(password, '\u0000')
        }
    }

    private val vaultDir: File get() = File(context.filesDir, "vault")

    companion object {
        const val SETTINGS_PATH = "10_Journal/system/mission_log.json"
    }
}
