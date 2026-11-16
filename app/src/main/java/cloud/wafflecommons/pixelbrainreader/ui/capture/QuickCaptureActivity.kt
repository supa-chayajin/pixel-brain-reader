package cloud.wafflecommons.pixelbrainreader.ui.capture

import android.app.KeyguardManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddPhotoAlternate
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.IconButtonDefaults
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import cloud.wafflecommons.pixelbrainreader.BuildConfig
import cloud.wafflecommons.pixelbrainreader.data.capture.PhotoCapture
import cloud.wafflecommons.pixelbrainreader.data.capture.PhotoReader
import cloud.wafflecommons.pixelbrainreader.data.capture.PreparedPhoto
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
 *
 * 13.0 (X2): once the phone is unlocked, a photo too — its text read on the phone into the note,
 * the photo kept beside it. Over the lock screen there is no photo button: the picker shows the
 * gallery.
 */
@AndroidEntryPoint
class QuickCaptureActivity : ComponentActivity() {

    @Inject lateinit var captureRepository: CaptureRepository
    @Inject lateinit var photoReader: PhotoReader

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        // As the main window: no screenshots or Recents preview in a release build.
        if (!BuildConfig.DEBUG) {
            window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        }
        val source = if (intent?.action == ACTION_CREATE_NOTE) "notes" else "tile"
        val keyguard = getSystemService(KeyguardManager::class.java)
        setContent {
            PixelBrainReaderTheme {
                QuickCaptureScreen(
                    readOnly = VaultWriteGate.readOnly,
                    isLocked = { keyguard?.isKeyguardLocked ?: true },
                    reader = photoReader,
                    onSave = { text, photo ->
                        if (photo == null) captureRepository.saveQuickNote(LocalDateTime.now(), text, source)
                        else captureRepository.savePhotoNote(LocalDateTime.now(), text, photo.jpeg, source)
                    },
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

/** The photo under way, kept across a rotation: its read runs here, not in the screen's scope. */
class CapturePhotoState : ViewModel() {
    var photo by mutableStateOf<PreparedPhoto?>(null)
    var reading by mutableStateOf(false)
        private set

    /** The text a finished read found, until the screen takes it into the note. */
    var found by mutableStateOf<String?>(null)
        private set

    var failed by mutableStateOf(false)
        private set

    fun read(uri: Uri, reader: PhotoReader) {
        reading = true
        failed = false
        viewModelScope.launch {
            try {
                val prepared = reader.prepare(uri)
                photo = prepared
                found = prepared.text
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Not an image (an IOException), or ML Kit's own failure: the photo isn't kept.
                failed = true
            } finally {
                reading = false
            }
        }
    }

    fun taken() {
        found = null
        failed = false
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun QuickCaptureScreen(
    readOnly: Boolean,
    isLocked: () -> Boolean,
    reader: PhotoReader,
    onSave: suspend (String, PreparedPhoto?) -> String,
    onDone: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val photos: CapturePhotoState = viewModel()
    var text by rememberSaveable { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var locked by remember { mutableStateOf(isLocked()) }
    LifecycleResumeEffect(Unit) {
        locked = isLocked()
        onPauseOrDispose { }
    }
    val focus = remember { FocusRequester() }
    val dictation = rememberOnDeviceDictation(
        // Capture never reaches the network, the lock screen least of all.
        allowNetworkFallback = false,
        onResult = { spoken -> text = if (text.isBlank()) spoken else "$text $spoken" },
        onMessage = { message -> Toast.makeText(context, message, Toast.LENGTH_LONG).show() }
    )
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) photos.read(uri, reader)
    }
    // A finished read: its text after what was typed, or why there is none.
    LaunchedEffect(photos.found, photos.failed) {
        val found = photos.found
        when {
            photos.failed -> Toast.makeText(context, "That photo couldn't be read", Toast.LENGTH_LONG).show()
            found == null -> return@LaunchedEffect
            found.isBlank() -> Toast.makeText(context, "No text found: the photo is attached as it is", Toast.LENGTH_LONG).show()
            else -> text = PhotoCapture.withRead(text, found)
        }
        photos.taken()
    }
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
                TextButton(onClick = onDone, shapes = ButtonDefaults.shapes(), modifier = Modifier.align(Alignment.End)) { Text("Close") }
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
                if (photos.reading) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        LoadingIndicator(modifier = Modifier.size(32.dp))
                        Text("Reading the photo…", style = MaterialTheme.typography.bodyMedium)
                    }
                }
                photos.photo?.let { photo -> AttachedPhoto(photo, onRemove = { photos.photo = null }) }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    FilledTonalIconButton(onClick = { dictation.start() }, shapes = IconButtonDefaults.shapes()) { Icon(Icons.Rounded.Mic, contentDescription = "Dictate") }
                    if (!locked) {
                        FilledTonalIconButton(
                            enabled = !photos.reading, shapes = IconButtonDefaults.shapes(),
                            onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
                        ) { Icon(Icons.Rounded.AddPhotoAlternate, contentDescription = "Add a photo") }
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onDone, shapes = ButtonDefaults.shapes()) { Text("Cancel") }
                    Button(
                        enabled = (text.isNotBlank() || photos.photo != null) && !saving && !photos.reading, shapes = ButtonDefaults.shapes(),
                        onClick = {
                            saving = true
                            scope.launch {
                                try {
                                    onSave(text, photos.photo)
                                    Toast.makeText(context, "Saved to your inbox", Toast.LENGTH_SHORT).show()
                                    onDone()
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    saving = false
                                    val message = if (e is CaptureRepository.NoVaultYetException) {
                                        "Log in to Pixel Brain first: there's no vault on this phone yet"
                                    } else {
                                        "Couldn't save the note"
                                    }
                                    Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                    ) { Text("Save") }
                }
            }
        }
    }
}

@Composable
private fun AttachedPhoto(photo: PreparedPhoto, onRemove: () -> Unit) {
    val thumbnail = remember(photo) { BitmapFactory.decodeByteArray(photo.jpeg, 0, photo.jpeg.size)?.asImageBitmap() }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (thumbnail != null) {
            Image(
                bitmap = thumbnail,
                contentDescription = "The attached photo",
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(64.dp).clip(MaterialTheme.shapes.medium)
            )
        }
        Text("Photo attached", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        TextButton(onClick = onRemove, shapes = ButtonDefaults.shapes()) { Text("Remove") }
    }
}
