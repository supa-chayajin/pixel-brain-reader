package cloud.wafflecommons.pixelbrainreader.ui.utils

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

/**
 * French dictation that stays on the device (settled decision #4, CLAUDE.md).
 *
 * The three voice inputs used to hand audio to the system recognizer (`RecognizerIntent`), which
 * may transcribe online — including the encrypted private journal. This runs Android's on-device
 * recognizer instead. If the device can't recognize French offline yet, it requests the language
 * model and, only where [allowNetworkFallback] is true, falls back to the system recognizer with
 * a visible notice. The private journal never falls back.
 */
object DictationConfig {
    const val LANGUAGE = "fr-FR"

    /** Recognizer extras shared by the on-device recognizer and the fallback. */
    fun extras(preferOffline: Boolean): Map<String, Any> = mapOf(
        RecognizerIntent.EXTRA_LANGUAGE_MODEL to RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
        RecognizerIntent.EXTRA_LANGUAGE to LANGUAGE,
        RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE to LANGUAGE,
        RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE to LANGUAGE,
        RecognizerIntent.EXTRA_PARTIAL_RESULTS to true,
        RecognizerIntent.EXTRA_PREFER_OFFLINE to preferOffline
    )

    fun intent(preferOffline: Boolean): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        extras(preferOffline).forEach { (key, value) ->
            when (value) {
                is String -> putExtra(key, value)
                is Boolean -> putExtra(key, value)
            }
        }
    }
}

/** Drives one dictation surface; [start] asks for the mic permission the first time. */
@Stable
class DictationController internal constructor() {
    var isListening by mutableStateOf(false)
        internal set
    var partialText by mutableStateOf("")
        internal set

    internal var startAction: () -> Unit = {}
    internal var stopAction: () -> Unit = {}

    fun start() = startAction()
    fun stop() = stopAction()
}

/** Shown while [controller] listens: the live transcript and a Stop button. */
@Composable
fun DictationListeningDialog(controller: DictationController) {
    if (!controller.isListening) return
    AlertDialog(
        onDismissRequest = { controller.stop() },
        icon = { Icon(Icons.Rounded.Mic, contentDescription = null, modifier = Modifier.size(28.dp)) },
        title = { Text("Listening… (French)") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = controller.partialText.ifBlank { "Speak now — transcribed on this device." },
                    style = MaterialTheme.typography.bodyLarge
                )
            }
        },
        confirmButton = { TextButton(onClick = { controller.stop() }) { Text("Stop") } }
    )
}

@Composable
fun rememberOnDeviceDictation(
    allowNetworkFallback: Boolean,
    onResult: (String) -> Unit,
    onMessage: (String) -> Unit
): DictationController {
    val context = LocalContext.current
    val controller = remember { DictationController() }
    val currentOnResult by rememberUpdatedState(onResult)
    val currentOnMessage by rememberUpdatedState(onMessage)
    val session = remember { DictationSession() }

    val fallbackLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
                ?.takeIf { it.isNotBlank() }
                ?.let { currentOnResult(it) }
        }
    }

    fun unavailable() {
        if (!allowNetworkFallback) {
            currentOnMessage("Offline French dictation isn't ready on this device yet — try again once the language pack is downloaded.")
            return
        }
        try {
            fallbackLauncher.launch(DictationConfig.intent(preferOffline = true))
            currentOnMessage("Offline French isn't available yet: using the system recognizer, which may use the network.")
        } catch (e: ActivityNotFoundException) {
            currentOnMessage("Speech recognition isn't available on this device.")
        }
    }

    fun listen() {
        if (!SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
            unavailable()
            return
        }
        session.release(controller)
        val recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        session.recognizer = recognizer
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                session.release(controller)
                if (!text.isNullOrBlank()) currentOnResult(text)
            }

            override fun onPartialResults(partialResults: Bundle?) {
                partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                    ?.let { controller.partialText = it }
            }

            override fun onError(error: Int) {
                when (error) {
                    SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> {
                        // The French model isn't on the device yet: ask the platform to fetch it.
                        runCatching { recognizer.triggerModelDownload(DictationConfig.intent(preferOffline = true)) }
                        session.release(controller)
                        unavailable()
                    }
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                        session.release(controller)
                        currentOnMessage("Didn't catch that — try again.")
                    }
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                        session.release(controller)
                        currentOnMessage("Microphone permission is needed to dictate.")
                    }
                    else -> {
                        session.release(controller)
                        currentOnMessage("Dictation stopped (error $error).")
                    }
                }
            }

            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })
        controller.partialText = ""
        controller.isListening = true
        recognizer.startListening(DictationConfig.intent(preferOffline = true))
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) listen() else currentOnMessage("Microphone permission is needed for on-device dictation.")
    }

    controller.startAction = {
        if (hasMicPermission(context)) listen() else permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }
    controller.stopAction = { session.recognizer?.stopListening() ?: session.release(controller) }

    DisposableEffect(Unit) { onDispose { session.release(controller) } }
    return controller
}

/** The live recognizer of one dictation surface (one at a time). */
private class DictationSession {
    var recognizer: SpeechRecognizer? = null

    fun release(controller: DictationController) {
        recognizer?.destroy()
        recognizer = null
        controller.isListening = false
        controller.partialText = ""
    }
}

private fun hasMicPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
