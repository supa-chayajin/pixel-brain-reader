package cloud.wafflecommons.pixelbrainreader.ui.utils

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.luminance
import io.noties.markwon.syntax.Prism4jTheme
import io.noties.markwon.syntax.Prism4jThemeDarkula
import io.noties.markwon.syntax.Prism4jThemeDefault

object CodeTheme {
    /**
     * Prism theme matched to the current Material scheme: Darkula on dark
     * surfaces, the light default otherwise. A fully Material-dynamic theme
     * already lost one fight with the Prism4jTheme API signature — keeping the
     * stock themes is deliberate for v10.
     */
    fun create(colorScheme: ColorScheme): Prism4jTheme =
        if (colorScheme.surface.luminance() < 0.5f) {
            Prism4jThemeDarkula.create()
        } else {
            Prism4jThemeDefault.create()
        }
}
