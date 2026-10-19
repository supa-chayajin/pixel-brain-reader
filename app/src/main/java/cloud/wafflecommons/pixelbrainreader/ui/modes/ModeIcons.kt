package cloud.wafflecommons.pixelbrainreader.ui.modes

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.rounded.BeachAccess
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.BusinessCenter
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Coffee
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.DoNotDisturbOn
import androidx.compose.material.icons.rounded.FamilyRestroom
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FitnessCenter
import androidx.compose.material.icons.rounded.Flight
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Laptop
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Park
import androidx.compose.material.icons.rounded.Pets
import androidx.compose.material.icons.rounded.School
import androidx.compose.material.icons.rounded.SelfImprovement
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.material.icons.rounded.Weekend
import androidx.compose.material.icons.rounded.Work
import androidx.compose.ui.graphics.vector.ImageVector
import cloud.wafflecommons.pixelbrainreader.data.modes.ContextMode
import cloud.wafflecommons.pixelbrainreader.data.modes.ModeAppearance

/** The icons a mode can wear, by the key stored in `context_modes.json`. */
object ModeIcons {
    val all: List<Pair<String, ImageVector>> = listOf(
        "work" to Icons.Rounded.Work,
        "business" to Icons.Rounded.BusinessCenter,
        "laptop" to Icons.Rounded.Laptop,
        "code" to Icons.Rounded.Code,
        "school" to Icons.Rounded.School,
        "book" to Icons.AutoMirrored.Rounded.MenuBook,
        "home" to Icons.Rounded.Home,
        "family" to Icons.Rounded.FamilyRestroom,
        "favorite" to Icons.Rounded.Favorite,
        "weekend" to Icons.Rounded.Weekend,
        "coffee" to Icons.Rounded.Coffee,
        "pets" to Icons.Rounded.Pets,
        "games" to Icons.Rounded.SportsEsports,
        "music" to Icons.Rounded.MusicNote,
        "palette" to Icons.Rounded.Palette,
        "fitness" to Icons.Rounded.FitnessCenter,
        "zen" to Icons.Rounded.SelfImprovement,
        "park" to Icons.Rounded.Park,
        "travel" to Icons.Rounded.Flight,
        "beach" to Icons.Rounded.BeachAccess,
        "bedtime" to Icons.Rounded.Bedtime,
        "night" to Icons.Rounded.DarkMode,
        "dnd" to Icons.Rounded.DoNotDisturbOn
    )

    private val byKey = all.toMap()

    /** An unknown key (an older or hand-edited file) falls back to the mode's default icon. */
    fun of(appearance: ModeAppearance, mode: ContextMode): ImageVector =
        byKey[appearance.iconKey(mode)] ?: byKey.getValue(ModeAppearance.defaultIcon(mode))
}
