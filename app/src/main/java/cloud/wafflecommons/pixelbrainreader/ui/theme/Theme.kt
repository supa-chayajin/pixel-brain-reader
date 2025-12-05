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
// Ces couleurs serviront de repli (fallback) pour les versions d'Android < 12
// ou si l'utilisateur désactive les thèmes dynamiques.
private val AbsoluteBlack = Color(0xFF000000)
private val DarkSurfaceCard = Color(0xFF1E1E1E)
private val DarkDetailBackground = Color(0xFF121212)

private val SagePrimary = Color(0xFFC5E0A3)
private val SageContainer = Color(0xFF3E4F30)

private val DarkColorScheme = darkColorScheme(
    primary = SagePrimary,
    onPrimary = Color.Black,
    secondary = Color(0xFFE2E4D3),
    onSecondary = Color.Black,
    tertiary = Color(0xFFA5D0D2),
    background = AbsoluteBlack,
    surface = AbsoluteBlack,
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
    // ACTIVATION DES COULEURS DYNAMIQUES (Material You)
    // true = L'app utilise les couleurs du fond d'écran sur Android 12+
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        // Si dynamicColor est activé et qu'on est sur Android 12+ (S), on utilise les couleurs système
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        // Sinon, on utilise votre palette personnalisée "Pixel Sage"
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            // La barre d'état s'adapte à la luminosité du thème
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
