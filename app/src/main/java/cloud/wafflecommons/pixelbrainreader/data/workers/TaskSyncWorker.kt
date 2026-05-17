package cloud.wafflecommons.pixelbrainreader.data.workers

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import cloud.wafflecommons.pixelbrainreader.data.local.dao.TaskDao
import cloud.wafflecommons.pixelbrainreader.data.repository.GoogleTaskRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

private const val TAG = "TaskSyncWorker"
private const val MAX_RETRY_ATTEMPTS = 5

/**
 * Drains the [cloud.wafflecommons.pixelbrainreader.data.local.entity.DailyTaskEntity]
 * outbox to Google Tasks.
 *
 * For each row where `isDirty = 1` OR `pendingDeletion = 1`:
 *  - pendingDeletion + googleTaskId   → DELETE on Google, then remove locally
 *  - pendingDeletion + no googleTaskId → just remove locally (never reached Google)
 *  - dirty + googleTaskId             → PUSH completion status to Google, clear dirty
 *  - dirty + no googleTaskId          → CREATE on Google, write the new id back
 *
 * Network constraint is enforced at enqueue time (see PixelBrainApplication).
 * Partial failures return [Result.retry] so WorkManager applies its backoff;
 * each row is processed independently so already-pushed work is not redone.
 */
@HiltWorker
class TaskSyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val taskDao: TaskDao,
    private val googleTaskRepository: GoogleTaskRepository
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val dirty = taskDao.getDirtyTasksSnapshot()
        if (dirty.isEmpty()) {
            Log.d(TAG, "No dirty tasks; nothing to drain")
            return Result.success()
        }

        Log.i(TAG, "Draining ${dirty.size} dirty task(s)")
        var pushed = 0
        var failed = 0

        for (task in dirty) {
            try {
                val ok = when {
                    task.pendingDeletion && task.googleTaskId != null -> {
                        val res = googleTaskRepository.deleteTask(task.googleTaskId)
                        if (res.isSuccess) {
                            taskDao.deleteTask(task.id)
                            true
                        } else false
                    }
                    task.pendingDeletion -> {
                        // Never reached Google — just remove locally.
                        taskDao.deleteTask(task.id)
                        true
                    }
                    task.googleTaskId != null -> {
                        val res = googleTaskRepository.pushCompletion(task.googleTaskId, task.isDone)
                        if (res.isSuccess) {
                            taskDao.clearDirty(task.id)
                            true
                        } else false
                    }
                    else -> {
                        // Created locally and flagged for push — create on Google + link back.
                        val res = googleTaskRepository.createTask(task.label)
                        res.fold(
                            onSuccess = {
                                taskDao.markPushedWithGoogleId(task.id, it)
                                true
                            },
                            onFailure = { false }
                        )
                    }
                }
                if (ok) pushed++ else failed++
            } catch (e: Exception) {
                Log.e(TAG, "Failed to drain task ${task.id}", e)
                failed++
            }
        }

        Log.i(TAG, "Drain complete: $pushed pushed, $failed failed (attempt $runAttemptCount)")
        return if (failed > 0 && runAttemptCount < MAX_RETRY_ATTEMPTS) Result.retry() else Result.success()
    }

    companion object {
        const val UNIQUE_NAME = "TaskSyncWorker"
    }
}
