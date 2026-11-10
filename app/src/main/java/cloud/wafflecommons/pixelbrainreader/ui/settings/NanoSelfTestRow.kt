package cloud.wafflecommons.pixelbrainreader.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cloud.wafflecommons.pixelbrainreader.data.ai.NanoSelfTest

/** Settings → Intelligence: run the on-device AI's self-test and read its latest result. */
@Composable
fun NanoSelfTestRow(result: NanoSelfTest?, isRunning: Boolean, enabled: Boolean, onRun: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Self-test", style = MaterialTheme.typography.titleSmall)
                Text(
                    "One real request and one streamed answer through the app's own path, timed",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            OutlinedButton(onClick = onRun, enabled = enabled && !isRunning) {
                if (isRunning) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("Testing…")
                } else {
                    Text("Run a test")
                }
            }
        }
        result?.let { latest ->
            Text(
                latest.summary(),
                style = MaterialTheme.typography.bodySmall,
                color = if (latest.passed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}
