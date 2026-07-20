package cloud.wafflecommons.pixelbrainreader.data.ai

import android.util.Log
import cloud.wafflecommons.pixelbrainreader.data.gamification.Attribute
import cloud.wafflecommons.pixelbrainreader.data.gamification.CharacterClass
import cloud.wafflecommons.pixelbrainreader.data.gamification.GamificationRepository
import cloud.wafflecommons.pixelbrainreader.data.health.HealthConnectManager
import cloud.wafflecommons.pixelbrainreader.data.repository.MoodRepository
import kotlinx.coroutines.flow.firstOrNull
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class OracleGenerator @Inject constructor(
    private val healthConnectManager: HealthConnectManager,
    private val moodRepository: MoodRepository,
    private val gamificationRepository: GamificationRepository,
    private val geminiRagManager: GeminiRagManager
) {
    
    private val FALLBACK_INSIGHT = "Les brumes sont épaisses aujourd'hui. Repose-toi bien, Héros, et laisse ton esprit te guider."

    suspend fun generateDailyInsight(): String {
        return try {
            // 1. Gather Context
            val now = Instant.now()
            val yesterdayStart = now.minus(1, ChronoUnit.DAYS)
            
            // Health
            val sleepDuration = healthConnectManager.readSleepDuration(yesterdayStart, now)
            val steps = healthConnectManager.readSteps(yesterdayStart, now)
            val sleepHours = sleepDuration.toHours()
            val sleepStatus = if (sleepHours >= 7) "Good" else "Low"
            
            // Mood (Yesterday's average)
            val yesterdayDate = LocalDate.now().minusDays(1)
            val moodData = moodRepository.getDailyMood(yesterdayDate).firstOrNull()
            val mood = moodData?.summary?.mainEmoji ?: "Neutral"
            
            // Gamification
            val gameState = gamificationRepository.gamificationState.firstOrNull()
            val profile = gameState?.profile
            val characterClass = profile?.characterClass?.name ?: "ADVENTURER"
            val weakAttr = gameState?.attributes?.minByOrNull { it.value }?.key ?: Attribute.VIG
            
            // 2. prompt
            val prompt = """
                Agis comme un Mentor sage et ancien pour un aventurier de classe $characterClass.
                L'utilisateur a dormi ${sleepHours}h (Statut : $sleepStatus), a marché $steps pas hier, et se sentait $mood.
                Son attribut le plus faible est actuellement $weakAttr.
                Analyse le lien entre son état physique et son humeur.
                Donne UNE quête concrète, épique et courte pour améliorer sa journée (max 30 mots).
                Ton : épique, encourageant, style RPG.
                ${AiLanguage.DIRECTIVE}
            """.trimIndent()
            
            Log.d("OracleGenerator", "Generating insight: $prompt")
            
            // 3. Generate
            val flow = geminiRagManager.generateResponse(prompt, useRAG = false)
            var result = ""
            flow.collect { 
                if (!it.startsWith("Thinking")) result = it 
            }
            
            if (result.isBlank()) FALLBACK_INSIGHT else result.replace("\"", "").trim()
            
        } catch (e: Exception) {
            Log.e("OracleGenerator", "Failed to generate insight", e)
            FALLBACK_INSIGHT
        }
    }
}
