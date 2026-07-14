package cloud.wafflecommons.pixelbrainreader.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

// --- PALETTE PIXEL SAGE (High Contrast) ---
// Fallback palette for Android < 12 or when the user disables dynamic theming.
// Material 3 dark elevation curve: each step LIGHTER than the previous.
private val AbsoluteBlack = Color(0xFF000000)
private val SagePrimary = Color(0xFFC5E0A3)
private val SageContainer = Color(0xFF3E4F30)
// Mode brand accent — routed through tertiary so the chat surface adapts with Material You.
private val SparkAccent = Color(0xFFFFB077)

private val DarkColorScheme = darkColorScheme(
    primary = SagePrimary,
    onPrimary = Color.Black,
    primaryContainer = SageContainer,
    onPrimaryContainer = Color(0xFFE6F3D2),
    secondary = Color(0xFFE2E4D3),
    onSecondary = Color.Black,
    tertiary = SparkAccent,
    onTertiary = Color.Black,
    background = AbsoluteBlack,
    onBackground = Color(0xFFEFEFEF),
    surface = AbsoluteBlack,
    onSurface = Color(0xFFEFEFEF),
    surfaceContainerLowest = Color(0xFF0A0A0A),
    surfaceContainerLow = Color(0xFF121212),
    surfaceContainer = Color(0xFF1A1A1A),
    surfaceContainerHigh = Color(0xFF1E1E1E),
    surfaceContainerHighest = Color(0xFF2A2A2A),
    surfaceVariant = Color(0xFF2A2A2A),
    onSurfaceVariant = Color(0xFFC4C8BB),
    outline = Color(0xFF8C9183),
    outlineVariant = Color(0xFF43483D),
    scrim = AbsoluteBlack
)

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF4C662B),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFCEEBB1),
    onPrimaryContainer = Color(0xFF101F00),
    secondary = Color(0xFF5D624E),
    tertiary = Color(0xFF386668),
    onTertiary = Color.White,
    background = Color(0xFFFBFDF5),
    surface = Color(0xFFFBFDF5),
    surfaceVariant = Color(0xFFE1E4D5),
    onSurfaceVariant = Color(0xFF45483D),
    outline = Color(0xFF75786C),
    outlineVariant = Color(0xFFC4C8BB)
)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun PixelBrainReaderTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Material You — use the wallpaper palette on Android 12+.
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    // Full Material You: follow the SYSTEM light/dark + wallpaper colors (no dark-pin).
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            // Status-bar icons follow the theme (dark bg → light icons, and vice-versa).
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    // Expressive theme entry point — injects the spring MotionScheme + shape tokens so
    // every M3 component inherits expressive motion and rounder shapes app-wide.
    MaterialExpressiveTheme(
        colorScheme = colorScheme,
        motionScheme = MotionScheme.expressive(),
        shapes = AppShapes,
        typography = Typography
    ) {
        // Paint the theme background across the whole window so the system window
        // background never shows through (this kept the foldable from washing out).
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = colorScheme.background
        ) {
            content()
        }
    }
}
