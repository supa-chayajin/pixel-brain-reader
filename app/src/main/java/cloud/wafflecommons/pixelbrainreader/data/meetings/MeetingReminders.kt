package cloud.wafflecommons.pixelbrainreader.data.meetings

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import cloud.wafflecommons.pixelbrainreader.data.device.VaultWriteGate
import cloud.wafflecommons.pixelbrainreader.data.local.dao.DailyDashboardDao
import cloud.wafflecommons.pixelbrainreader.data.local.entity.TimelineEntryEntity
import cloud.wafflecommons.pixelbrainreader.data.repository.UserPreferencesRepository
import cloud.wafflecommons.pixelbrainreader.data.system.AppClock
import cloud.wafflecommons.pixelbrainreader.data.workers.MeetingPrepWorker
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** When the meeting prep notifications come (D4, owner 2026-11-11: WorkManager, no exact alarm), pure. */
object MeetingReminders {

    /** How long before a meeting its prep is offered. */
    val LEAD: Duration = Duration.ofMinutes(10)

    const val TAG = "meeting_prep"

    /** A notification to come: the calendar event and how long until it is due. */
    data class Due(val eventId: String, val delayMs: Long)

    /** Today's calendar entries still more than [LEAD] away, each with the wait until its notification. */
    fun due(entries: List<TimelineEntryEntity>, now: LocalDateTime): List<Due> = entries.mapNotNull { entry ->
        val eventId = entry.googleEventId ?: return@mapNotNull null
        val delay = Duration.between(now, LocalDateTime.of(entry.date, entry.time).minus(LEAD)).toMillis()
        if (delay <= 0) null else Due(eventId, delay)
    }

    /** One unique work per calendar event: a new import replaces it, never doubles it. */
    fun workName(eventId: String): String = "${TAG}_$eventId"
}

/**
 * Keeps one WorkManager job per meeting still to come today, from today's calendar entries (the
 * Google import brings them at each sync the owner asks for). Off: every job is cancelled. Never on
 * a read-only device (no notification there).
 */
@Singleton
class MeetingReminderScheduler @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val userPrefs: UserPreferencesRepository,
    private val dashboardDao: DailyDashboardDao,
    private val appClock: AppClock
) {
    suspend fun reschedule() = withContext(Dispatchers.IO) {
        if (VaultWriteGate.readOnly) return@withContext
        val workManager = WorkManager.getInstance(context)
        if (!userPrefs.meetingPrepReminders.first()) {
            workManager.cancelAllWorkByTag(MeetingReminders.TAG)
            return@withContext
        }
        val entries = dashboardDao.getTimelineSnapshot(appClock.today.value)
        for (due in MeetingReminders.due(entries, LocalDateTime.now())) {
            workManager.enqueueUniqueWork(
                MeetingReminders.workName(due.eventId),
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<MeetingPrepWorker>()
                    .setInitialDelay(due.delayMs, TimeUnit.MILLISECONDS)
                    .setInputData(workDataOf(MeetingPrepWorker.KEY_EVENT to due.eventId))
                    .addTag(MeetingReminders.TAG)
                    .build()
            )
        }
    }
}
