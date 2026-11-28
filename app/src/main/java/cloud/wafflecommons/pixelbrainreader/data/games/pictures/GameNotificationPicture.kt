package cloud.wafflecommons.pixelbrainreader.data.games.pictures

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.toBitmap
import cloud.wafflecommons.pixelbrainreader.data.games.GameArt
import cloud.wafflecommons.pixelbrainreader.data.games.GameArtRules
import cloud.wafflecommons.pixelbrainreader.di.GamePictureLoader
import coil.ImageLoader
import coil.annotation.ExperimentalCoilApi
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.request.SuccessResult
import coil.size.Scale
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.absoluteValue

/**
 * The session notification's large icon (13.4, P1): the owner's file, else a console picture from
 * the image cache only (never the network), else the initials drawn. A console picture this phone
 * never showed makes no call at all: the disk cache is looked at first, so the ledger counts
 * nothing for it. The initials sit on a full square in the game's tone of the system's dynamic
 * palette (the system clips the icon to its own shape). Never a tint from the picture (P6).
 */
@Singleton
class GameNotificationPicture @Inject constructor(
    @param:ApplicationContext private val context: Context,
    @param:GamePictureLoader private val loader: ImageLoader
) {

    /** [art]'s icon, [sizePx] square; null when there is no picture and no initials. On IO; no log. */
    suspend fun largeIcon(art: GameArt, sizePx: Int = SIZE_PX): Bitmap? = withContext(Dispatchers.IO) {
        try {
            for (model in GameArtRules.models(art)) {
                if (model is String && !inDiskCache(model)) continue
                val request = ImageRequest.Builder(context)
                    .data(model)
                    .size(sizePx)
                    .scale(Scale.FILL)
                    .allowHardware(false)
                    .networkCachePolicy(CachePolicy.DISABLED)
                    .build()
                val drawable = (loader.execute(request) as? SuccessResult)?.drawable ?: continue
                val bitmap = (drawable as? BitmapDrawable)?.bitmap ?: drawable.toBitmap(sizePx, sizePx)
                return@withContext square(bitmap, sizePx)
            }
            initials(art, sizePx)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // A picture is decoration: the notification goes out without one.
            null
        }
    }

    /** True when the picture at [url] is in the loader's disk cache (the snapshot closed at once). */
    @OptIn(ExperimentalCoilApi::class)
    private fun inDiskCache(url: String): Boolean = try {
        loader.diskCache?.openSnapshot(url)?.use { true } ?: false
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // An unreadable cache entry is a picture this phone doesn't have.
        false
    }

    /** [source] cropped to a centred square of [side], in software (the notification parcels it). */
    private fun square(source: Bitmap, side: Int): Bitmap {
        val soft = if (source.config == Bitmap.Config.HARDWARE) source.copy(Bitmap.Config.ARGB_8888, false) else source
        if (soft.width == side && soft.height == side) return soft
        val scale = maxOf(side.toFloat() / soft.width, side.toFloat() / soft.height)
        val width = soft.width * scale
        val height = soft.height * scale
        val left = (side - width) / 2f
        val top = (side - height) / 2f
        val out = createBitmap(side, side)
        Canvas(out).drawBitmap(soft, null, RectF(left, top, left + width, top + height), Paint(Paint.FILTER_BITMAP_FLAG))
        return out
    }

    /** [art]'s initials, bold and centred on its tone; null without initials. */
    private fun initials(art: GameArt, side: Int): Bitmap? {
        if (art.initials.isEmpty()) return null
        val night = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val tone = TONES[toneIndex(art.key)]
        val container = context.getColor(if (night) tone.darkContainer else tone.lightContainer)
        val content = context.getColor(if (night) tone.darkContent else tone.lightContent)
        val bitmap = createBitmap(side, side)
        val canvas = Canvas(bitmap)
        canvas.drawColor(container)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = content
            textSize = side * INITIALS_SHARE
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        val baseline = side / 2f - (paint.descent() + paint.ascent()) / 2f
        canvas.drawText(art.initials, side / 2f, baseline, paint)
        return bitmap
    }

    /** The system palette's colours of one accent: its container and content, light and dark. */
    private class Tone(val lightContainer: Int, val lightContent: Int, val darkContainer: Int, val darkContent: Int)

    companion object {
        const val SIZE_PX = 256

        /** The initials' size against the icon's side, as the app's pictures draw them. */
        private const val INITIALS_SHARE = 0.38f

        /** Primary, secondary, tertiary: the order of the app's tones (toneFor). */
        private val TONES = listOf(
            Tone(
                android.R.color.system_accent1_100, android.R.color.system_accent1_900,
                android.R.color.system_accent1_700, android.R.color.system_accent1_100
            ),
            Tone(
                android.R.color.system_accent2_100, android.R.color.system_accent2_900,
                android.R.color.system_accent2_700, android.R.color.system_accent2_100
            ),
            Tone(
                android.R.color.system_accent3_100, android.R.color.system_accent3_900,
                android.R.color.system_accent3_700, android.R.color.system_accent3_100
            )
        )

        /** toneFor's index for [key] (a key whose hash has no absolute value too). */
        private fun toneIndex(key: String): Int = Math.floorMod(key.hashCode().absoluteValue, TONES.size)
    }
}
