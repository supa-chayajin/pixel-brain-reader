package cloud.wafflecommons.pixelbrainreader.di

import cloud.wafflecommons.pixelbrainreader.data.gamification.GamificationRepository
import cloud.wafflecommons.pixelbrainreader.data.health.HealthConnectManager
import cloud.wafflecommons.pixelbrainreader.data.local.dao.HabitDao
import cloud.wafflecommons.pixelbrainreader.data.repository.ChoreRepository
import cloud.wafflecommons.pixelbrainreader.data.repository.DailyDashboardRepository
import cloud.wafflecommons.pixelbrainreader.data.repository.HabitRepository
import cloud.wafflecommons.pixelbrainreader.data.repository.MoodRepository
import cloud.wafflecommons.pixelbrainreader.data.repository.ScratchRepository
import cloud.wafflecommons.pixelbrainreader.domain.gamification.GrantXpUseCase
import cloud.wafflecommons.pixelbrainreader.widget.manager.WidgetSnapshotManager
import cloud.wafflecommons.pixelbrainreader.widget.ui.WidgetUpdateManager
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * The bridge from the non-injectable Glance layer (`GlanceAppWidget` / `ActionCallback`) into the
 * Hilt graph. Widgets and their action callbacks reach dependencies via
 * `EntryPointAccessors.fromApplication(context.applicationContext, WidgetEntryPoint::class.java)`.
 *
 * Read-side: the interactive widgets read live domain state here (habits, chores, tasks, mood…)
 * instead of a pre-rendered snapshot, so a tap reflects instantly on the next `updateAll`.
 * Write-side: the callbacks mutate through the same repositories the in-app screens use, so a
 * widget action and an in-app action are indistinguishable to the vault/Room source of truth.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface WidgetEntryPoint {
    fun gamificationRepository(): GamificationRepository
    fun habitRepository(): HabitRepository
    fun moodRepository(): MoodRepository
    fun healthConnectManager(): HealthConnectManager
    fun habitDao(): HabitDao // For direct log access if needed

    // Added for the interactive widget suite (quick-log habits/chores/mood/tasks + capture).
    fun choreRepository(): ChoreRepository
    fun dailyDashboardRepository(): DailyDashboardRepository
    fun scratchRepository(): ScratchRepository
    fun grantXpUseCase(): GrantXpUseCase
    fun widgetSnapshotManager(): WidgetSnapshotManager
    fun widgetUpdateManager(): WidgetUpdateManager
}
