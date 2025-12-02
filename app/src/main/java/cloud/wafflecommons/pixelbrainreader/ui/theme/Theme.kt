package cloud.wafflecommons.pixelbrainreader.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
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
// Fond Noir Absolu pour faire ressortir les cartes
private val AbsoluteBlack = Color(0xFF000000)
private val DarkSurfaceCard = Color(0xFF1E1E1E) // Gris anthracite pour les cartes
private val DarkDetailBackground = Color(0xFF121212) // Un peu plus sombre pour le lecteur

// Accent Sage (Vert Pixel)
private val SagePrimary = Color(0xFFC5E0A3)
private val SageContainer = Color(0xFF3E4F30)

private val DarkColorScheme = darkColorScheme(
    primary = SagePrimary,
    onPrimary = Color.Black,
    secondary = Color(0xFFE2E4D3),
    onSecondary = Color.Black,
    tertiary = Color(0xFFA5D0D2),

    // Le secret du design : Noir Pur en fond
    background = AbsoluteBlack,
    surface = AbsoluteBlack,

    // Les conteneurs pour les cartes flottantes
    surfaceContainer = AbsoluteBlack,
    surfaceContainerLow = DarkSurfaceCard,
    surfaceContainerHigh = DarkDetailBackground,

    onSurface = Color(0xFFEFEFEF),
    onSurfaceVariant = Color(0xFFC4C8BB)
)

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF4C662B),
    onPrimary = Color.White,
    secondary = Color(0xFF5D624E),
    tertiary = Color(0xFF386668),
    background = Color(0xFFFBFDF5),
    surface = Color(0xFFFBFDF5)
)

@Composable
fun PixelBrainReaderTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        // Optionnel : Désactiver le dynamicColor si tu veux forcer ton thème Sage partout
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            // Barre d'état transparente
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
