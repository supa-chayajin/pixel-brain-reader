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
import cloud.wafflecommons.pixelbrainreader.ui.theme.SemanticPalette

@Composable
fun HeroCard(
    state: GamificationState,
    isHealthSynergyActive: Boolean = false,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
        shape = RoundedCornerShape(16.dp)
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
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center
            ) {
                 Image(
                     painter = painterResource(id = heroDrawable),
                     contentDescription = "Hero Avatar",
                     modifier = Modifier.size(64.dp),
                     contentScale = ContentScale.Fit
                 )
            }
            
            Spacer(modifier = Modifier.width(16.dp))
            
            Column(modifier = Modifier.weight(1f)) {
                // Class & Level
                Text(
                    text = "Lvl ${state.profile.level} ${state.profile.characterClass.name.lowercase().capitalize()}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                // XP Bar
                val progress = (state.profile.currentXp / state.profile.xpToNextLevel).toFloat().coerceIn(0f, 1f)
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
                    color = SemanticPalette.XpGold,
                    trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha=0.1f),
                )
                Text(
                    text = "${state.profile.currentXp.toInt()} / ${state.profile.xpToNextLevel.toInt()} XP",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.End)
                )
            }
            
            Spacer(modifier = Modifier.width(12.dp))
            
            // Attributes Compact Grid
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                AttributeRow(Attribute.VIG, state.attributes[Attribute.VIG] ?: 0)
                AttributeRow(Attribute.MND, state.attributes[Attribute.MND] ?: 0)
                AttributeRow(Attribute.SOC, state.attributes[Attribute.SOC] ?: 0)
            }
        }
        
        // Active Buffs Section
        if (isHealthSynergyActive) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = androidx.compose.material.icons.Icons.Default.Face,
                    contentDescription = "Buff Active",
                    tint = SemanticPalette.Success,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Endurance Buff Active (+50 XP)",
                    style = MaterialTheme.typography.labelMedium,
                    color = SemanticPalette.Success,
                    fontWeight = FontWeight.Bold
                )
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
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text = value.toString(),
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

fun String.capitalize() = replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
