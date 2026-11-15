package cloud.wafflecommons.pixelbrainreader.data.capture

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
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
 * bundled Latin model (no download, no network), then compressed to JPEG.
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

    private suspend fun read(bitmap: Bitmap): String {
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        return try {
            recognizer.process(InputImage.fromBitmap(bitmap, 0)).await().text
        } finally {
            recognizer.close()
        }
    }
}
