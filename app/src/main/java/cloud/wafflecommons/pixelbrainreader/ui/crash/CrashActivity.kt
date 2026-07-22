package cloud.wafflecommons.pixelbrainreader.ui.crash

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import cloud.wafflecommons.pixelbrainreader.BuildConfig
import cloud.wafflecommons.pixelbrainreader.MainActivity
import cloud.wafflecommons.pixelbrainreader.ui.theme.PixelBrainReaderTheme
import kotlin.system.exitProcess

class CrashActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val stackTrace = intent.getStringExtra(EXTRA_STACK_TRACE) ?: "No details available."

        setContent {
            PixelBrainReaderTheme {
                CrashScreen(
                    stackTrace = stackTrace,
                    onRestartClick = { restartApp() }
                )
            }
        }
    }

    private fun restartApp() {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        startActivity(intent)
        finish()
        android.os.Process.killProcess(android.os.Process.myPid())
        exitProcess(0)
    }

    @Composable
    fun CrashScreen(stackTrace: String, onRestartClick: () -> Unit) {
        val haptic = LocalHapticFeedback.current
        var showDetails by remember { mutableStateOf(false) }

        Scaffold { paddingValues ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(24.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = "Error",
                    modifier = Modifier.padding(bottom = 16.dp),
                    tint = MaterialTheme.colorScheme.error
                )

                Text(
                    text = "Oops, Pixel's brain stumbled",
                    style = MaterialTheme.typography.headlineMedium,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "An unexpected error occurred. We apologize for the inconvenience.",
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(32.dp))

                Button(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onRestartClick()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Text("Restart the app")
                }

                // Technical details are ONLY visible in debug builds. In
                // release the panel is hidden entirely — stack traces can
                // leak file paths, class names, and exception messages that
                // may include sensitive context (vault paths, error strings
                // formatted from user data, etc.).
                if (BuildConfig.DEBUG) {
                    Spacer(modifier = Modifier.height(16.dp))

                    OutlinedButton(
                        onClick = { showDetails = !showDetails },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (showDetails) "Hide technical details" else "Show technical details")
                    }

                    AnimatedVisibility(visible = showDetails) {
                        Column(modifier = Modifier.padding(top = 16.dp)) {
                            Text(
                                text = "Stack Trace:",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.error
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = stackTrace,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }
        }
    }

    companion object {
        const val EXTRA_STACK_TRACE = "extra_stack_trace"
    }
}
