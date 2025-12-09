package cloud.wafflecommons.pixelbrainreader.ui.utils

import android.text.SpannableStringBuilder
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.toArgb
import io.noties.markwon.syntax.Prism4jTheme

object CodeTheme {
    fun create(colorScheme: ColorScheme): Prism4jTheme {
        // Fallback to Default Theme due to API signature mismatch in Prism4jTheme interface
        // This ensures the app compiles and has syntax highlighting.
        return io.noties.markwon.syntax.Prism4jThemeDefault.create()
    }

    // private class CustomPrismTheme(...) : Prism4jTheme { ... } implementation commented out/removed
}
