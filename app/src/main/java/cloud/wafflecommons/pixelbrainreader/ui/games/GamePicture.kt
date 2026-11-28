@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package cloud.wafflecommons.pixelbrainreader.ui.games

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isFinite
import androidx.compose.ui.unit.min
import cloud.wafflecommons.pixelbrainreader.data.games.GameArt
import cloud.wafflecommons.pixelbrainreader.data.games.GameArtRules
import cloud.wafflecommons.pixelbrainreader.data.games.GamePictures
import cloud.wafflecommons.pixelbrainreader.di.GamePicturesEntryPoint
import cloud.wafflecommons.pixelbrainreader.ui.components.badgeShapeFor
import cloud.wafflecommons.pixelbrainreader.ui.components.toneFor
import coil.ImageLoader
import coil.compose.AsyncImage
import dagger.hilt.android.EntryPointAccessors

/** A game picture's size on each surface (13.4, GP3). */
object GamePictureSizes {
    /** The pickers' and the folds' rows. */
    val Row = 40.dp

    /** Daily's game lines (on screen only: the burned line stays text). */
    val Daily = 24.dp

    /** CortexTopAppBar's emblem (the open map's header). */
    val Header = 44.dp

    /** The game's page (P3), and on a wide window. */
    val Page = 144.dp
    val PageWide = 176.dp

    /** The game being played: the wide card's height. */
    val NowPlaying = 180.dp

    /** The grid's adaptive cell: about 3 a row on a phone, 6 to 7 from 840 dp. */
    val CellMin = 100.dp
}

/**
 * The games pictures' loader (the privacy ledger's "Game pictures", the consoles' hosts only), from
 * the Hilt graph and remembered: never Coil's app-wide default.
 */
@Composable
fun rememberGameImageLoader(): ImageLoader {
    val context = LocalContext.current.applicationContext
    return remember(context) { EntryPointAccessors.fromApplication(context, GamePicturesEntryPoint::class.java).imageLoader() }
}

/** The game's own expressive shape, the one its 13.2 badge had. */
@Composable
fun gameShape(art: GameArt): Shape = badgeShapeFor(art.key).toShape()

fun pictureDescription(art: GameArt): String = "Picture of ${art.title}"

/**
 * A game's picture (13.4, GP3): [art]'s models in turn (the next one after a failed load), cropped to
 * fill, over its tone's container; the initials (or the pad icon) in its tone while nothing has
 * loaded. [shape] null: the caller clips (a card, the carousel's mask). Size it with [modifier].
 *
 * The tone is the device's (toneFor), never a colour taken from the picture (P6), and a picture that
 * loaded hides the initials, so a transparent one shows its tone, never letters. Only what is drawn
 * loads (a lazy grid builds what is on screen); the loader is asked for only when there is something
 * to load, so the initials alone need no Hilt graph. A null [contentDescription] makes it decorative:
 * TalkBack reads nothing of it, the row's own text says the game. No log.
 */
@Composable
fun GamePicture(
    art: GameArt,
    modifier: Modifier = Modifier,
    shape: Shape? = gameShape(art),
    contentDescription: String? = pictureDescription(art),
    sizeHint: Int = GamePictures.SIZE_HINT
) {
    val tone = toneFor(art.key)
    val models = remember(art, sizeHint) { GameArtRules.models(art, sizeHint) }
    // A picture replaced at the same path (a new stamp) is tried again from the first model.
    var attempt by remember(models, art.ownFileStamp) { mutableIntStateOf(0) }
    var loaded by remember(models) { mutableStateOf(false) }
    val clipped = if (shape != null) modifier.clip(shape) else modifier
    val described = if (contentDescription != null) {
        Modifier.semantics {
            this.contentDescription = contentDescription
            role = Role.Image
        }
    } else {
        // Decorative: a merging parent (a picker's row, a clickable line) would otherwise read the
        // hidden initials into its own label ("GT, Glass Tower"), since merging keeps hidden children.
        Modifier.clearAndSetSemantics {}
    }
    BoxWithConstraints(
        modifier = clipped.background(tone.container).then(described),
        contentAlignment = Alignment.Center
    ) {
        val smaller = min(maxWidth, maxHeight)
        val side = if (smaller.isFinite) smaller else GamePictureSizes.Row
        if (!loaded && side > 0.dp) {
            if (art.initials.isNotEmpty()) {
                Initials(art.initials, side, tone.content)
            } else {
                Icon(
                    Icons.Rounded.SportsEsports,
                    contentDescription = null,
                    tint = tone.content,
                    modifier = Modifier.size(side * 0.55f)
                )
            }
        }
        val model = models.getOrNull(attempt)
        if (model != null) {
            // The same file at a new stamp loads again: Coil's key for a file follows its last change.
            key(art.ownFileStamp) {
                AsyncImage(
                    model = model,
                    contentDescription = null,
                    imageLoader = rememberGameImageLoader(),
                    modifier = Modifier.matchParentSize(),
                    contentScale = ContentScale.Crop,
                    onSuccess = { loaded = true },
                    onError = {
                        loaded = false
                        attempt += 1
                    }
                )
            }
        }
    }
}

/** The initials at 38 % of the picture's smaller side, whatever the font scale: they fit their shape. */
@Composable
private fun Initials(text: String, side: Dp, color: Color) {
    val size = with(LocalDensity.current) { (side * INITIALS_SHARE).toSp() }
    Text(
        text = text,
        color = color,
        style = LocalTextStyle.current.merge(
            TextStyle(
                fontSize = size,
                lineHeight = size,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None)
            )
        ),
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Clip,
        // The picture's description says it; the letters would be read twice.
        modifier = Modifier.semantics { hideFromAccessibility() }
    )
}

private const val INITIALS_SHARE = 0.38f
