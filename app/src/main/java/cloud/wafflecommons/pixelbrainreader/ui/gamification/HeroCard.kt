package cloud.wafflecommons.pixelbrainreader.ui.gamification

import androidx.compose.foundation.border
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.Face
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cloud.wafflecommons.pixelbrainreader.data.gamification.Attribute
import cloud.wafflecommons.pixelbrainreader.data.gamification.GamificationState
import cloud.wafflecommons.pixelbrainreader.R

@Composable
fun HeroCard(
    state: GamificationState,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        shape = RoundedCornerShape(16.dp)
        // elevation
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Avatar
            val heroDrawable = GamificationAssets.getHeroDrawable(state.profile.characterClass)
            
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .background(
                        color = MaterialTheme.colorScheme.surfaceContainerHigh, 
                        shape = RoundedCornerShape(8.dp)
                    )
                    .border(2.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp)), // Fixed: import added
                contentAlignment = Alignment.Center
            ) {
                 Image(
                     painter = painterResource(id = heroDrawable),
                     contentDescription = "Hero Avatar",
                     modifier = Modifier.size(64.dp), // Slightly smaller than box
                     contentScale = ContentScale.Fit
                     // filterQuality = FilterQuality.None // Removed: Not supported in current Compose version
                 )
            }
            
            Spacer(modifier = Modifier.width(16.dp))
            
            Column(modifier = Modifier.weight(1f)) {
                // Class & Level
                Text(
                    text = "Lvl ${state.profile.level} ${state.profile.characterClass.name.lowercase().capitalize()}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                // XP Bar
                val progress = (state.profile.currentXp / state.profile.xpToNextLevel).toFloat().coerceIn(0f, 1f)
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
                    color = Color(0xFFFFD700), // Gold
                    trackColor = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha=0.2f),
                )
                Text(
                    text = "${state.profile.currentXp.toInt()} / ${state.profile.xpToNextLevel.toInt()} XP",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha=0.7f),
                    modifier = Modifier.align(Alignment.End)
                )
            }
            
            Spacer(modifier = Modifier.width(12.dp))
            
            // Attributes Compact Grid
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                // Show top 3 attributes or specific ones? 
                // Showing all might be too much. Let's show non-zero or specific set.
                // For now, hardcode the main ones or the ones with highest value.
                // Let's just list VIG, MND, SOC as per previous design but with Icons.
                AttributeRow(Attribute.VIG, state.attributes[Attribute.VIG] ?: 0)
                AttributeRow(Attribute.MND, state.attributes[Attribute.MND] ?: 0)
                AttributeRow(Attribute.SOC, state.attributes[Attribute.SOC] ?: 0)
            }
        }
    }
}

@Composable
fun AttributeRow(attr: Attribute, value: Int) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Image(
            painter = painterResource(id = GamificationAssets.getAttributeIcon(attr)),
            contentDescription = attr.name,
            modifier = Modifier.size(20.dp),
            contentScale = ContentScale.Fit
            // filterQuality = FilterQuality.None // Removed
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text = value.toString(),
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onPrimaryContainer
        )
    }
}

fun String.capitalize() = replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
