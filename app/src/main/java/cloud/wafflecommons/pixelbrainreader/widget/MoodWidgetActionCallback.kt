package cloud.wafflecommons.pixelbrainreader.widget

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.state.updateAppWidgetState
import cloud.wafflecommons.pixelbrainreader.data.repository.DailyNoteRepository
import cloud.wafflecommons.pixelbrainreader.data.repository.MoodRepository
import cloud.wafflecommons.pixelbrainreader.data.repository.MoodEntry
import java.time.LocalDate
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.first
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class MoodWidgetActionCallback : ActionCallback {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface WidgetEntryPoint {
        fun repository(): DailyNoteRepository
        fun moodRepository(): MoodRepository
        fun fileRepository(): cloud.wafflecommons.pixelbrainreader.data.repository.FileRepository
        fun fileContentDao(): cloud.wafflecommons.pixelbrainreader.data.local.dao.FileContentDao
    }

    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters
    ) {
        val score = parameters[SCORE_KEY] ?: return
        val entry = MoodEntry(
            time = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm")),
            score = score,
            label = getMoodLabel(score),
            activities = emptyList(),
            note = "Logged from Widget"
        )

        // Hilt Injection in non-Hilt context
        val entryPoint = EntryPointAccessors.fromApplication(
            context.applicationContext,
            WidgetEntryPoint::class.java
        )
        val dailyNoteRepository = entryPoint.repository()
        val moodRepository = entryPoint.moodRepository()
        val fileRepository = entryPoint.fileRepository()
        val fileContentDao = entryPoint.fileContentDao()

        // 1. Add Entry via Repository (Autonomous module handles JSON/Folder/Summary)
        moodRepository.addEntry(LocalDate.now(), entry)

        // 2. Update Widget State
        updateAppWidgetState(context, glanceId) { prefs ->
            prefs[MoodTrackerWidget.LAST_EMOJI_KEY] = entry.label
            prefs[MoodTrackerWidget.LAST_UPDATE_KEY] = entry.time
        }
        
        // 3. Trigger Refresh
        MoodTrackerWidget().update(context, glanceId)
    }

    private fun getMoodLabel(score: Int): String = when (score) {
        1 -> "😫"
        2 -> "😞"
        3 -> "😐"
        4 -> "🙂"
        5 -> "🤩"
        else -> "😐"
    }

    companion object {
        val SCORE_KEY = ActionParameters.Key<Int>("mood_score")
    }
}
