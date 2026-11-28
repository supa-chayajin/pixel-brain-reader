package cloud.wafflecommons.pixelbrainreader.ui.settings

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cloud.wafflecommons.pixelbrainreader.data.device.VaultWriteGate
import kotlinx.coroutines.launch

/**
 * Settings › Connections › Google Calendar & Tasks (13.4, S-b): the switch that links the Google
 * account for the read-only import of today's events and tasks. A read-only device imports nothing.
 */
@Composable
fun GoogleSettingsScreen(onBack: () -> Unit, viewModel: GoogleSettingsViewModel = hiltViewModel()) {
    val enabled = viewModel.enabled.collectAsStateWithLifecycle().value
    val linked = viewModel.linked.collectAsStateWithLifecycle().value
    val readOnly = VaultWriteGate.readOnly
    val context = LocalContext.current
    val activity = remember(context) { context.hostingActivity() }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // AuthorizationClient may need the user's consent: an IntentSender, resolved here and handed back.
    val consentLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        viewModel.onConsentResolved(result.data)
    }
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is GoogleSettingsViewModel.GoogleAuthEvent.ConsentRequired ->
                    consentLauncher.launch(IntentSenderRequest.Builder(event.intentSender).build())
                GoogleSettingsViewModel.GoogleAuthEvent.Linked ->
                    scope.launch { snackbarHostState.showSnackbar("Google account linked") }
                is GoogleSettingsViewModel.GoogleAuthEvent.Failed ->
                    scope.launch { snackbarHostState.showSnackbar("Google: ${event.message}") }
            }
        }
    }

    SettingsPageScaffold(page = SettingsPage.GOOGLE, onBack = onBack, snackbarHostState = snackbarHostState) {
        SettingsGroup {
            row {
                SettingsSwitchRow(
                    title = SettingsPage.GOOGLE.title,
                    checked = enabled,
                    onCheckedChange = { on ->
                        if (on) activity?.let(viewModel::connect) else viewModel.disconnect()
                    },
                    description = if (linked) "Synced, read-only" else "Connect your Google account, read-only access",
                    icon = SettingsPage.GOOGLE.icon,
                    enabled = !readOnly && activity != null
                )
            }
            row { SettingsNote("The app imports your events and tasks and never changes them.") }
            if (readOnly) {
                row { SettingsNote("Calendar and tasks aren't imported on a read-only device.") }
            }
        }
    }
}

/** Walk the ContextWrapper chain to the hosting Activity (never a cast of LocalContext itself). */
private fun Context.hostingActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}
