package cloud.wafflecommons.pixelbrainreader.ui.components

import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonColors
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import cloud.wafflecommons.pixelbrainreader.ui.utils.HapticHelper.performHapticTick

@Composable
fun CortexIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: IconButtonColors = IconButtonDefaults.iconButtonColors(),
    content: @Composable () -> Unit
) {
    val view = LocalView.current
    
    IconButton(
        onClick = {
            view.performHapticTick()
            onClick()
        },
        modifier = modifier,
        enabled = enabled,
        colors = colors,
        content = content
    )
}
