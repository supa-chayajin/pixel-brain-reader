package cloud.wafflecommons.pixelbrainreader.data.repository

import android.util.Log
import androidx.room.withTransaction
import cloud.wafflecommons.pixelbrainreader.data.local.AppDatabase
import cloud.wafflecommons.pixelbrainreader.data.local.dao.MoodDao
import cloud.wafflecommons.pixelbrainreader.data.local.entity.MoodEntity
import cloud.wafflecommons.pixelbrainreader.data.sync.VaultPushScheduler
import cloud.wafflecommons.pixelbrainreader.widget.ui.WidgetUpdateManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.IOException
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Standalone Mood Entry model (Domain).
 */
data class MoodEntry(
    val time: String,
    val score: Int,
    val label: String,
    val activities: List<String>,
    val note: String? = null
)

/**
 * Summary statistics for a daily mood log.
 */
data class MoodSummary(
    val averageScore: Double,
    val mainEmoji: String
)

/**
 * Container for daily mood data stored as JSON.
 */
data class DailyMoodData(
    val date: String,
    val entries: List<MoodEntry>,
    val summary: MoodSummary
)

// --- DTOs (Internal Serialization) ---
@Serializable
data class MoodEntryDto(
    val time: String,
    val score: Int,
    val label: String,
    val activities: List<String> = emptyList(),
    val note: String? = null
)

@Serializable
data class MoodSummaryDto(
    val averageScore: Double,
    val mainEmoji: String
)

@Serializable
data class DailyMoodDataDto(
    val date: String = "",
    val entries: List<MoodEntryDto> = emptyList(),
    val summary: MoodSummaryDto? = null
)

@Singleton
class MoodRepository @Inject constructor(
    private val fileRepository: FileRepository,
    private val moodDao: MoodDao,
    private val database: AppDatabase,
    private val pushScheduler: VaultPushScheduler,
    private val widgetUpdateManager: WidgetUpdateManager
) {
    private val moodDir = "10_Journal/data/health/mood"

    private val jsonParser = Json {
        ignoreUnknownKeys = true
        isLenient = true
        prettyPrint = true
    }

    /** Serializes read-modify-write of a day's JSON file (app + widget taps can race). */
    private val writeMutex = Mutex()

    // SSOT: Room Database
    fun getMoodFlow(): Flow<List<MoodEntity>> {
        return moodDao.getAllMoods()
    }

    /** Moods for an inclusive date range — cheaper than streaming the whole table. */
    fun getMoodsBetween(start: LocalDate, end: LocalDate): Flow<List<MoodEntity>> =
        moodDao.getMoodsBetween(start.toString(), end.toString())

    /**
     * Sync Bridge: scans the mood JSON files and reconciles them into Room.
     *
     * Rows are replaced per DAY, and only for days whose content differs from Room: no write (so no
     * invalidation and no screen recomputation) when nothing changed, and rows whose timestamp was
     * derived in another timezone — or entries removed from the JSON — no longer linger as duplicates.
     */
    suspend fun syncWithFileSystem() = withContext(Dispatchers.IO) {
        val root = fileRepository.getLocalFile(moodDir)
        if (!root.exists()) {
            Log.d("DataSync", "Mood directory not found")
            return@withContext
        }

        // Phase 1: read + parse every JSON file OUTSIDE any DB transaction.
        val parsedByDate = mutableMapOf<String, MutableMap<Long, MoodEntity>>()
        root.walk().filter { it.isFile && it.name.endsWith(".json") }.forEach { file ->
            try {
                val content = file.readText()
                if (content.isBlank()) return@forEach
                val data = jsonParser.decodeFromString<DailyMoodDataDto>(content)
                val dateKey = data.date.ifBlank { file.nameWithoutExtension.take(10) }
                val day = parsedByDate.getOrPut(dateKey) { mutableMapOf() }
                // Keyed by timestamp: two entries in the same minute collapse exactly like the
                // REPLACE insert would, so the comparison below stays stable.
                data.entries.forEach { entry -> toEntity(dateKey, entry).let { day[it.timestamp] = it } }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("PBR_SYNC", "Failed to parse ${file.name}: ${e.message}")
            }
        }
        if (parsedByDate.isEmpty()) return@withContext

        // Phase 2: one short transaction for the days that actually changed.
        val existingByDate = moodDao.getAllMoodsOnce().groupBy { it.date }
        val changed = parsedByDate
            .mapValues { (_, byTimestamp) -> byTimestamp.values.toList() }
            .filter { (date, entities) -> entities.toSet() != existingByDate[date].orEmpty().toSet() }
        if (changed.isEmpty()) return@withContext

        database.withTransaction {
            changed.keys.chunked(DATE_CHUNK_SIZE).forEach { moodDao.deleteMoodsForDates(it) }
            moodDao.insertMoods(changed.values.flatten())
        }
        Log.d("PBR_SYNC", "Reconciled moods for ${changed.size} day(s)")
    }

    /**
     * Observes mood data for a specific date (Reactive from DB).
     */
    fun getDailyMood(date: LocalDate): Flow<DailyMoodData?> {
        return moodDao.getMood(date.toString()).map { entities ->
            if (entities.isNotEmpty()) mapToDomain(entities, date.toString()) else null
        }
    }

    /**
     * Legacy synchronous accessor for Stats
     */
    suspend fun getMood(date: LocalDate): MoodEntry? {
        // Return the LATEST entry for the day based on time
        return getDailyMood(date).first()?.entries?.maxByOrNull { it.time }
    }

    /**
     * Adds a new mood entry: vault JSON first, then Room, then an immediate widget refresh. The git
     * push is only REQUESTED (batched in the background) — the UI and the widgets never wait on the
     * network any more.
     */
    suspend fun addEntry(date: LocalDate, entry: MoodEntry) {
        withContext(Dispatchers.IO) {
            writeMutex.withLock {
                val path = "$moodDir/$date.json"
                val currentData = readDayForUpdate(path, date)

                val newDto = MoodEntryDto(entry.time, entry.score, entry.label, entry.activities, entry.note)
                val updatedEntries = currentData.entries.filterNot { it.time == newDto.time } + newDto
                val avg = updatedEntries.map { it.score }.average()
                val updatedData = currentData.copy(
                    entries = updatedEntries,
                    summary = MoodSummaryDto(avg, calculateDailyEmoji(avg))
                )
                fileRepository.saveFileLocally(path, jsonParser.encodeToString(updatedData))

                moodDao.insertMood(toEntity(date.toString(), newDto))
            }
        }

        // triggerUpdate() re-renders the live MoodWidget instantly; scheduleSnapshotUpdate() rebuilds
        // the snapshot so the Companion widget's mood emoji (read from the snapshot) follows too.
        widgetUpdateManager.triggerUpdate()
        runCatching { widgetUpdateManager.scheduleSnapshotUpdate() }
        pushScheduler.requestPush()
    }

    /**
     * The day's current JSON, or a fresh one. An unreadable file is moved aside (kept next to the
     * original, never overwritten) instead of being silently replaced by a file holding only the
     * new entry — which used to wipe the day's history.
     */
    private suspend fun readDayForUpdate(path: String, date: LocalDate): DailyMoodDataDto {
        val content = try {
            fileRepository.readFile(path)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        if (content.isNullOrBlank()) return DailyMoodDataDto(date = date.toString())
        return try {
            jsonParser.decodeFromString<DailyMoodDataDto>(content)
        } catch (e: Exception) {
            val backup = fileRepository.moveAsideCorrupt(path)
                ?: throw IOException("Unreadable mood file for $date could not be preserved", e)
            Log.e("MoodRepository", "Unreadable mood file for $date moved aside to ${backup.name}", e)
            DailyMoodDataDto(date = date.toString())
        }
    }

    // --- Mappers ---

    private fun toEntity(dateKey: String, entry: MoodEntryDto): MoodEntity {
        val parsedDate = runCatching { LocalDate.parse(dateKey) }.getOrElse { LocalDate.now() }
        val parsedTime = runCatching { java.time.LocalTime.parse(entry.time) }.getOrElse { java.time.LocalTime.NOON }
        val timeStr = parsedTime.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))
        val timestamp = LocalDateTime.of(parsedDate, parsedTime)
            .atZone(ZoneId.systemDefault())
            .toEpochSecond() * 1000
        return MoodEntity(
            timestamp = timestamp,
            date = dateKey,
            time = timeStr,
            score = entry.score,
            label = entry.label,
            activities = entry.activities.joinToString(","),
            note = entry.note
        )
    }

    private fun mapToDomain(entities: List<MoodEntity>, dateIdx: String): DailyMoodData {
        val entries = entities.map {
            MoodEntry(it.time, it.score, it.label, it.activities.split(",").filter { s -> s.isNotBlank() }, it.note)
        }
        val avg = if (entries.isNotEmpty()) entries.map { it.score }.average() else 0.0
        val summary = MoodSummary(avg, calculateDailyEmoji(avg))
        return DailyMoodData(dateIdx, entries, summary)
    }

    private fun calculateDailyEmoji(avg: Double): String {
        return when {
            avg < 1.8 -> "😫"
            avg.isNaN() -> "😐"
            avg < 2.6 -> "😞"
            avg < 3.4 -> "😐"
            avg < 4.2 -> "🙂"
            else -> "🤩"
        }
    }

    private companion object {
        const val DATE_CHUNK_SIZE = 500
    }
}
