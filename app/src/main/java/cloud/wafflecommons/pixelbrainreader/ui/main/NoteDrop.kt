package cloud.wafflecommons.pixelbrainreader.ui.main

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.mimeTypes
import androidx.compose.ui.draganddrop.toAndroidDragEvent
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import cloud.wafflecommons.pixelbrainreader.data.repository.AssetRepository
import cloud.wafflecommons.pixelbrainreader.data.sync.VaultPushScheduler
import cloud.wafflecommons.pixelbrainreader.data.vault.Attachments
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class NoteDropViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val assetRepository: AssetRepository,
    private val pushScheduler: VaultPushScheduler
) : ViewModel() {

    /** Saves the dropped files next to [notePath] and returns their embeds, in order. */
    suspend fun save(notePath: String, files: List<Pair<Uri, String>>): List<String> {
        val embeds = files.mapNotNull { (uri, mime) ->
            assetRepository.saveDropped(notePath, mime, displayName(uri)) { context.contentResolver.openInputStream(uri) }
                ?.let(Attachments::embed)
        }
        if (embeds.isNotEmpty()) pushScheduler.requestPush()
        return embeds
    }

    private fun displayName(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull()
}

private fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}

private val ACCEPTED_FILES = setOf("application/pdf")

/**
 * Drag & drop into a note (E6), e.g. from a browser beside the app on the unfolded screen: text
 * is handed to [onInsert] as it is; images and PDFs are stored in the vault's attachment folder
 * and handed over as `![[embeds]]`.
 */
@Composable
fun Modifier.noteDropTarget(notePath: String?, onInsert: (String) -> Unit): Modifier {
    val context = LocalContext.current
    val viewModel: NoteDropViewModel = hiltViewModel()
    val scope = rememberCoroutineScope()
    val path by rememberUpdatedState(notePath)
    val insert by rememberUpdatedState(onInsert)
    val target = remember {
        object : DragAndDropTarget {
            override fun onDrop(event: DragAndDropEvent): Boolean {
                val dragEvent = event.toAndroidDragEvent()
                val clip = dragEvent.clipData ?: return false
                // Another app's content URIs are readable only while these permissions are held.
                val permissions = context.activity()?.requestDragAndDropPermissions(dragEvent)
                val texts = mutableListOf<String>()
                val files = mutableListOf<Pair<Uri, String>>()
                for (index in 0 until clip.itemCount) {
                    val item = clip.getItemAt(index)
                    val uri = item.uri
                    val mime = uri?.let { context.contentResolver.getType(it) }.orEmpty()
                    if (uri != null && (mime.startsWith("image/") || mime in ACCEPTED_FILES)) {
                        files += uri to mime
                    } else {
                        (item.text ?: item.coerceToText(context))?.toString()?.takeIf { it.isNotBlank() }?.let(texts::add)
                    }
                }
                scope.launch {
                    try {
                        val embeds = path?.let { viewModel.save(it, files) }.orEmpty()
                        val markdown = (texts + embeds).joinToString("\n")
                        if (markdown.isNotBlank()) insert(markdown)
                    } finally {
                        permissions?.release()
                    }
                }
                return true
            }
        }
    }
    return dragAndDropTarget(
        shouldStartDragAndDrop = { event -> event.mimeTypes().any { it.startsWith("text/") || it.startsWith("image/") || it in ACCEPTED_FILES } },
        target = target
    )
}
