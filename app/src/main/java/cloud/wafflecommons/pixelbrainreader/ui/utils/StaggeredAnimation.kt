package cloud.wafflecommons.pixelbrainreader.ui.utils

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun StaggeredEntry(
    index: Int,
    modifier: Modifier = Modifier,
    delayPerItem: Int = 50,
    content: @Composable () -> Unit
) {
    var isVisible by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (!isVisible) {
            delay((index * delayPerItem).toLong())
            isVisible = true
        }
    }

    val animAlpha by animateFloatAsState(
        targetValue = if (isVisible) 1f else 0f,
        animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
        label = "alpha"
    )
    val animTranslationY by animateDpAsState(
        targetValue = if (isVisible) 0.dp else 50.dp,
        animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
        label = "translationY"
    )

    Box(
        modifier = modifier
            .graphicsLayer {
                alpha = animAlpha
                translationY = animTranslationY.toPx()
            }
    ) {
        content()
    }
}
