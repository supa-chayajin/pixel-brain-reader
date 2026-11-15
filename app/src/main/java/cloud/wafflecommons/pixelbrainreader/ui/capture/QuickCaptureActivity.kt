package cloud.wafflecommons.pixelbrainreader.ui.capture

import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cloud.wafflecommons.pixelbrainreader.BuildConfig
import cloud.wafflecommons.pixelbrainreader.data.device.VaultWriteGate
import cloud.wafflecommons.pixelbrainreader.data.repository.CaptureRepository
import cloud.wafflecommons.pixelbrainreader.ui.theme.PixelBrainReaderTheme
import cloud.wafflecommons.pixelbrainreader.ui.utils.DictationListeningDialog
import cloud.wafflecommons.pixelbrainreader.ui.utils.rememberOnDeviceDictation
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import javax.inject.Inject

/**
 * Capture from anywhere (X4, 12.21; the owner's D6): one screen that types or dictates a note into
 * the inbox, and nothing else. It opens from the Quick Settings tile and, as the phone's notes app
 * (Android's Notes role, `CREATE_NOTE`), from the lock screen, over it: in its own task, it shows
 * nothing of the vault and leads nowhere else; Save or Cancel ends it.
 */
@AndroidEntryPoint
class QuickCaptureActivity : ComponentActivity() {

    @Inject lateinit var captureRepository: CaptureRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        // As the main window: no screenshots or Recents preview in a release build.
        if (!BuildConfig.DEBUG) {
            window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        }
        val source = if (intent?.action == ACTION_CREATE_NOTE) "notes" else "tile"
        setContent {
            PixelBrainReaderTheme {
                QuickCaptureScreen(
                    readOnly = VaultWriteGate.readOnly,
                    onSave = { text -> captureRepository.saveQuickNote(LocalDateTime.now(), text, source) },
                    onDone = { finish() }
                )
            }
        }
    }

    private companion object {
        /** `Intent.ACTION_CREATE_NOTE` (API 34), as the Notes role sends it. */
        const val ACTION_CREATE_NOTE = "android.intent.action.CREATE_NOTE"
    }
}

@Composable
private fun QuickCaptureScreen(readOnly: Boolean, onSave: suspend (String) -> String, onDone: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var text by rememberSaveable { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    val dictation = rememberOnDeviceDictation(
        // Capture never reaches the network, the lock screen least of all.
        allowNetworkFallback = false,
        onResult = { spoken -> text = if (text.isBlank()) spoken else "$text $spoken" },
        onMessage = { message -> Toast.makeText(context, message, Toast.LENGTH_LONG).show() }
    )
    DictationListeningDialog(dictation)
    LaunchedEffect(Unit) { if (!readOnly) focus.requestFocus() }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(
            modifier = Modifier.fillMaxSize().safeDrawingPadding().imePadding().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("Capture", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            if (readOnly) {
                Text("Read-only device: capture on your phone.", style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDone, modifier = Modifier.align(Alignment.End)) { Text("Close") }
            } else {
                Text(
                    "It goes into your inbox as a new note.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    placeholder = { Text("What's on your mind?") },
                    modifier = Modifier.fillMaxWidth().weight(1f).focusRequester(focus)
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    FilledTonalIconButton(onClick = { dictation.start() }) { Icon(Icons.Rounded.Mic, contentDescription = "Dictate") }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onDone) { Text("Cancel") }
                    Button(
                        enabled = text.isNotBlank() && !saving,
                        onClick = {
                            saving = true
                            scope.launch {
                                try {
                                    onSave(text)
                                    Toast.makeText(context, "Saved to your inbox", Toast.LENGTH_SHORT).show()
                                    onDone()
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    saving = false
                                    Toast.makeText(context, "Couldn't save the note", Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                    ) { Text("Save") }
                }
            }
        }
    }
}
