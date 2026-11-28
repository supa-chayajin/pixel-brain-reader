@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package cloud.wafflecommons.pixelbrainreader.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cloud.wafflecommons.pixelbrainreader.data.ai.NanoSelfTest

/**
 * Settings › Gemini Nano: the self-test's button and its latest result. 13.4: it sits under the
 * Self-test row's title and description (the row's `below`), so it draws neither itself.
 */
@Composable
fun NanoSelfTestRow(result: NanoSelfTest?, isRunning: Boolean, enabled: Boolean, onRun: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = onRun, shapes = ButtonDefaults.shapes(), enabled = enabled && !isRunning) {
            if (isRunning) {
                LoadingIndicator(modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text("Testing…")
            } else {
                Text("Run a test")
            }
        }
        result?.let { latest ->
            Text(
                latest.summary(),
                style = MaterialTheme.typography.bodySmall,
                color = if (latest.passed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
            )
        }
    }
}
