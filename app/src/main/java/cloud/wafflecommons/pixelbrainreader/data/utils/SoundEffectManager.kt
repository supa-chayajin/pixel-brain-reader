package cloud.wafflecommons.pixelbrainreader.data.utils

import android.media.AudioManager
import android.media.ToneGenerator
import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Lightweight UI sound-effect player, paired with the existing haptics at key interaction
 * points (habit/task completion, errors). Uses [ToneGenerator] so it ships no audio assets;
 * swap for a SoundPool + res/raw samples later if richer sounds are wanted.
 *
 * OFF by default — [enabled] is driven from the user's "Sound effects" preference. All calls
 * are no-ops when disabled, so call sites can fire unconditionally.
 */
@Singleton
class SoundEffectManager @Inject constructor() {

    @Volatile
    var enabled: Boolean = false

    // Lazily created; construction can throw if the audio resources are unavailable.
    private val toneGenerator: ToneGenerator? by lazy {
        try {
            ToneGenerator(AudioManager.STREAM_MUSIC, VOLUME)
        } catch (e: RuntimeException) {
            Log.w("SoundEffectManager", "ToneGenerator unavailable", e)
            null
        }
    }

    /** A positive, light confirmation (e.g. habit/task completed). */
    fun success() = play(ToneGenerator.TONE_PROP_ACK, 150)

    /** A short tick (e.g. toggling, small increments). */
    fun tick() = play(ToneGenerator.TONE_PROP_BEEP, 90)

    /** A gentle "uncheck / undo" blip. */
    fun undo() = play(ToneGenerator.TONE_PROP_BEEP2, 90)

    /** An error / failure cue. */
    fun error() = play(ToneGenerator.TONE_SUP_ERROR, 200)

    private fun play(tone: Int, durationMs: Int) {
        if (!enabled) return
        try {
            toneGenerator?.startTone(tone, durationMs)
        } catch (e: Exception) {
            // Never let a UI sound crash an interaction.
            Log.w("SoundEffectManager", "startTone failed", e)
        }
    }

    private companion object {
        const val VOLUME = 70 // 0..100
    }
}
