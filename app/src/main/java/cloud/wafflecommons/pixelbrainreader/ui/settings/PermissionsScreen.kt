package cloud.wafflecommons.pixelbrainreader.ui.settings

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.DoNotDisturbOn
import androidx.compose.material.icons.rounded.HealthAndSafety
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityCompat
import androidx.health.connect.client.PermissionController
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle

/**
 * Settings › Privacy & safety › Permissions (13.4, S11): for each permission the app uses, what it
 * is for and whether it is granted, with a button that asks for it or opens the system's page. It
 * shows and opens what the manifest declares, and adds none. Read again each time the page comes
 * back, the system's pages being where most of them change.
 */
@Composable
fun PermissionsScreen(onBack: () -> Unit, viewModel: PermissionsViewModel = hiltViewModel()) {
    val granted by viewModel.granted.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) { lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { viewModel.refresh() } }

    // The runtime permissions the system no longer asks for (refused twice): their button opens the app's page.
    var systemStopsAsking by rememberSaveable { mutableStateOf(listOf<String>()) }
    var asking by remember { mutableStateOf<AppPermission?>(null) }
    val runtimeLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
        val permission = asking
        asking = null
        if (!allowed && permission != null && activity != null &&
            permission.manifest.none { ActivityCompat.shouldShowRequestPermissionRationale(activity, it) }
        ) {
            systemStopsAsking = systemStopsAsking + permission.key
        }
        viewModel.refresh()
    }
    val healthLauncher = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) {
        viewModel.refresh()
    }

    SettingsPageScaffold(page = SettingsPage.PERMISSIONS, onBack = onBack) {
        SettingsGroup {
            AppPermissions.ALL.forEach { permission ->
                row {
                    val read = permission.key in granted
                    val state = granted[permission.key]
                    SettingsRow(
                        title = permission.title,
                        description = permission.purpose,
                        icon = iconOf(permission),
                        anchor = permission.key,
                        trailing = if (read && state != null) {
                            { GrantedText(state) }
                        } else {
                            null
                        },
                        below = when {
                            !read -> null
                            state == null -> {
                                { UnavailableText(permission) }
                            }
                            state == true || permission.kind == PermissionKind.ALWAYS -> null
                            else -> {
                                {
                                    FilledTonalButton(
                                        onClick = {
                                            when (permission.kind) {
                                                PermissionKind.RUNTIME ->
                                                    if (permission.key in systemStopsAsking) {
                                                        open(context, appDetails(context))
                                                    } else {
                                                        asking = permission
                                                        runtimeLauncher.launch(permission.manifest.first())
                                                    }
                                                PermissionKind.PROMOTED -> open(context, promotionSettings(context))
                                                PermissionKind.NOTIFICATION_POLICY -> open(context, Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
                                                PermissionKind.HEALTH -> healthLauncher.launch(viewModel.healthPermissions())
                                                PermissionKind.ALWAYS -> Unit
                                            }
                                        },
                                        shapes = ButtonDefaults.shapes()
                                    ) {
                                        Text(
                                            if (permission.kind == PermissionKind.RUNTIME && permission.key !in systemStopsAsking ||
                                                permission.kind == PermissionKind.HEALTH
                                            ) {
                                                "Allow"
                                            } else {
                                                "Open settings"
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun GrantedText(granted: Boolean) {
    Text(
        if (granted) "Allowed" else "Not allowed",
        style = MaterialTheme.typography.labelLarge,
        color = if (granted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
    )
}

/** Why a permission has nothing to grant on this phone. */
@Composable
private fun UnavailableText(permission: AppPermission) {
    Text(
        when (permission.kind) {
            PermissionKind.PROMOTED -> "Needs a newer Android"
            PermissionKind.HEALTH -> "Health Connect isn't available on this phone"
            else -> "Not on this phone"
        },
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

private fun iconOf(permission: AppPermission): ImageVector = when (permission.key) {
    "notifications" -> Icons.Rounded.Notifications
    "live_updates" -> Icons.Rounded.Bolt
    "dnd" -> Icons.Rounded.DoNotDisturbOn
    "location" -> Icons.Rounded.LocationOn
    "microphone" -> Icons.Rounded.Mic
    "health" -> Icons.Rounded.HealthAndSafety
    "internet" -> Icons.Rounded.Language
    else -> Icons.Rounded.Shield
}

/** The app's own page in the system's settings, where a permission the system stopped asking for is granted. */
private fun appDetails(context: Context): Intent =
    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))

/** Live updates' switch for this app (36.1), the app's notification settings before. */
private fun promotionSettings(context: Context): Intent =
    if (Build.VERSION.SDK_INT_FULL >= Build.VERSION_CODES_FULL.BAKLAVA_1) {
        Intent(Settings.ACTION_APP_NOTIFICATION_PROMOTION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
    } else {
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
    }

private fun open(context: Context, intent: Intent) {
    try {
        context.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        Log.w(TAG, "No system page for it: ${e.javaClass.simpleName}")
    }
}

/** Walk the ContextWrapper chain to the hosting Activity: the system asks whether to explain a refusal there. */
private fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}

private const val TAG = "Permissions"
