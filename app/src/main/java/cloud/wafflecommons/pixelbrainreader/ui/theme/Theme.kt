package cloud.wafflecommons.pixelbrainreader.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

// --- PALETTE PIXEL SAGE (High Contrast) ---
// Fallback palette for Android < 12 or when the user disables dynamic theming.
//
// Material 3 dark elevation curve: each step LIGHTER than the previous.
//   surface (#000) < containerLowest < containerLow < container < containerHigh < containerHighest
// Reversing this scale (the previous bug) made cards, dialogs, and borders
// disappear into the background.
private val AbsoluteBlack = Color(0xFF000000)

private val SagePrimary = Color(0xFFC5E0A3)
private val SageContainer = Color(0xFF3E4F30)

// Mode brand accents — routed through tertiary so the chat surface adapts
// dynamically (Material You) while still having a deterministic fallback here.
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
    // Elevation curve, dark -> light:
    surfaceContainerLowest = Color(0xFF0A0A0A),
    surfaceContainerLow    = Color(0xFF121212),
    surfaceContainer       = Color(0xFF1A1A1A),
    surfaceContainerHigh   = Color(0xFF1E1E1E),
    surfaceContainerHighest = Color(0xFF2A2A2A),
    // surfaceVariant drives AI chat bubbles + secondary chips; explicit so it
    // doesn't fall back to MD3's default mid-grey which clashes with our scale.
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

@Composable
fun PixelBrainReaderTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // ACTIVATION DES COULEURS DYNAMIQUES (Material You)
    // true = L'app utilise les couleurs du fond d'écran sur Android 12+
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    // Dark-first app: always resolve to a DARK scheme. The foldable inner display
    // can report day/light mode, which washed the whole UI out — pinning dark keeps
    // it consistent and vibrant. We keep Material You (dynamic) but force its dark
    // variant, falling back to the designed "Pixel Sage" dark palette.
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            dynamicDarkColorScheme(context)
        }
        else -> DarkColorScheme
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            // Dark background → light status-bar icons.
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = false
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography
    ) {
        // Paint the theme background across the whole window so the system window
        // background (light on the foldable inner display in day mode) never shows
        // through — this is what kept the app looking "not dark".
        Surface(
            modifier = androidx.compose.ui.Modifier.fillMaxSize(),
            color = colorScheme.background
        ) {
            content()
        }
    }
}
