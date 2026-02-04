package cloud.wafflecommons.pixelbrainreader.di

import cloud.wafflecommons.pixelbrainreader.data.gamification.GamificationRepository
import cloud.wafflecommons.pixelbrainreader.data.health.HealthConnectManager
import cloud.wafflecommons.pixelbrainreader.data.local.dao.HabitDao
import cloud.wafflecommons.pixelbrainreader.data.repository.HabitRepository
import cloud.wafflecommons.pixelbrainreader.data.repository.MoodRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@EntryPoint
@InstallIn(SingletonComponent::class)
interface WidgetEntryPoint {
    fun gamificationRepository(): GamificationRepository
    fun habitRepository(): HabitRepository
    fun moodRepository(): MoodRepository
    fun healthConnectManager(): HealthConnectManager
    fun habitDao(): HabitDao // For direct log access if needed
}
