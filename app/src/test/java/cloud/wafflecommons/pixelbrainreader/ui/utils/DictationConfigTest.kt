package cloud.wafflecommons.pixelbrainreader.ui.utils

import android.speech.RecognizerIntent
import org.junit.Assert.assertEquals
import org.junit.Test

/** Settled decisions #4/#5: dictation is French and asks for offline recognition. */
class DictationConfigTest {

    @Test
    fun `dictation is pinned to French and prefers offline recognition`() {
        val extras = DictationConfig.extras(preferOffline = true)

        assertEquals("fr-FR", extras[RecognizerIntent.EXTRA_LANGUAGE])
        assertEquals("fr-FR", extras[RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE])
        assertEquals("fr-FR", extras[RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE])
        assertEquals(true, extras[RecognizerIntent.EXTRA_PREFER_OFFLINE])
        assertEquals(RecognizerIntent.LANGUAGE_MODEL_FREE_FORM, extras[RecognizerIntent.EXTRA_LANGUAGE_MODEL])
    }
}
