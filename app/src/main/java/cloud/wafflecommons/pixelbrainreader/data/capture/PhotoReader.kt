package cloud.wafflecommons.pixelbrainreader.data.capture

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.net.Uri
import androidx.core.graphics.createBitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** A photo as the inbox keeps it: a JPEG at most [PhotoCapture.MAX_SIDE] on its longest side, and the text read on it. */
class PreparedPhoto(val jpeg: ByteArray, val text: String)

/**
 * Reads a photo on the phone (X2): decoded straight to its kept size, its text read by ML Kit's
 * bundled Latin model (no download, no network), then compressed to JPEG. Since 13.4 it also makes
 * a game's own cover ([prepareCover]): smaller, and without the text.
 */
@Singleton
class PhotoReader @Inject constructor(@param:ApplicationContext private val context: Context) {

    /** [uri]'s photo, ready to keep; an [IOException] when it isn't an image the phone can read. */
    suspend fun prepare(uri: Uri): PreparedPhoto = withContext(Dispatchers.IO) {
        val bitmap = try {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
                val (width, height) = PhotoCapture.targetSize(info.size.width, info.size.height)
                decoder.setTargetSize(width, height)
                // ML Kit and the JPEG encoder read the pixels: no hardware bitmap.
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        } catch (e: IllegalArgumentException) {
            throw IOException("not an image", e)
        }
        try {
            val text = read(bitmap)
            val jpeg = ByteArrayOutputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, PhotoCapture.JPEG_QUALITY, out)
                out.toByteArray()
            }
            PreparedPhoto(jpeg, text)
        } finally {
            bitmap.recycle()
        }
    }

    /**
     * [uri]'s picture as a game's own cover (13.4, P5): decoded straight to at most
     * [PhotoCapture.COVER_SIDE] on its longest side, its shape kept, then a JPEG at
     * [PhotoCapture.COVER_QUALITY]. A JPEG has no transparency, so a picture that has some is
     * flattened onto white first (the file's own pixels, never a colour of the app's screens). No
     * text is read. An [IOException] when it isn't an image the phone can read, or can no longer be
     * opened (a share's permission gone).
     */
    suspend fun prepareCover(uri: Uri): ByteArray = withContext(Dispatchers.IO) {
        val bitmap = try {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
                val (width, height) = PhotoCapture.targetSize(info.size.width, info.size.height, PhotoCapture.COVER_SIDE)
                decoder.setTargetSize(width, height)
                // The JPEG encoder and the flattening read the pixels: no hardware bitmap.
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        } catch (e: IllegalArgumentException) {
            throw IOException("not an image", e)
        } catch (e: SecurityException) {
            throw IOException("can't be opened", e)
        }
        var flattened: Bitmap? = null
        try {
            val opaque = if (bitmap.hasAlpha()) onWhite(bitmap).also { flattened = it } else bitmap
            ByteArrayOutputStream().use { out ->
                if (!opaque.compress(Bitmap.CompressFormat.JPEG, PhotoCapture.COVER_QUALITY, out)) throw IOException("not encoded")
                out.toByteArray()
            }
        } finally {
            flattened?.recycle()
            bitmap.recycle()
        }
    }

    /** [bitmap] drawn over white, without transparency: what its JPEG shows. */
    private fun onWhite(bitmap: Bitmap): Bitmap {
        val flat = createBitmap(bitmap.width, bitmap.height)
        Canvas(flat).apply {
            drawColor(Color.WHITE)
            drawBitmap(bitmap, 0f, 0f, null)
        }
        return flat
    }

    private suspend fun read(bitmap: Bitmap): String {
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        return try {
            recognizer.process(InputImage.fromBitmap(bitmap, 0)).await().text
        } finally {
            recognizer.close()
        }
    }
}
