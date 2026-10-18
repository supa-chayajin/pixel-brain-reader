package cloud.wafflecommons.pixelbrainreader.ui.guard

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GppMaybe
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import cloud.wafflecommons.pixelbrainreader.data.guard.ConfidentialityGuard
import cloud.wafflecommons.pixelbrainreader.data.guard.GuardFinding
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/** Just the hold state, for screens that show the banner (no settings load, no git work). */
@HiltViewModel
class GuardStatusViewModel @Inject constructor(guard: ConfidentialityGuard) : ViewModel() {
    val held: StateFlow<List<GuardFinding>> = guard.held
}

/** "Sync held" — counts only, so it is safe on a shared screen. */
@Composable
fun GuardHoldBanner(held: List<GuardFinding>, onReview: () -> Unit, modifier: Modifier = Modifier) {
    val notes = held.map { it.path }.distinct().size
    ElevatedCard(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
    ) {
        Row(modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.GppMaybe, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer)
            Spacer(Modifier.width(12.dp))
            Text(
                text = "Sync held: " + (if (held.size == 1) "1 item" else "${held.size} items") +
                    " in " + (if (notes == 1) "1 note" else "$notes notes") + " to review",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onReview) { Text("Review") }
        }
    }
}
