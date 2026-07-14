package cloud.wafflecommons.pixelbrainreader.ui.components

import androidx.compose.animation.core.animateIntAsState
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun CortexAnimatedCounter(
    targetValue: Int,
    modifier: Modifier = Modifier,
    style: TextStyle = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
    color: androidx.compose.ui.graphics.Color = androidx.compose.ui.graphics.Color.Unspecified,
    prefix: String = "",
    suffix: String = ""
) {
    val animatedValue by animateIntAsState(
        targetValue = targetValue,
        animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
        label = "Counter Animation"
    )

    Text(
        text = "$prefix$animatedValue$suffix",
        modifier = modifier,
        style = style,
        color = color
    )
}
