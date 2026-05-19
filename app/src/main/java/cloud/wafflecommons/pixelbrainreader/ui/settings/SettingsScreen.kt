package cloud.wafflecommons.pixelbrainreader.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BrightnessMedium
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.rounded.HomeWork
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material3.*
import kotlinx.coroutines.launch
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cloud.wafflecommons.pixelbrainreader.data.ai.NanoState
import cloud.wafflecommons.pixelbrainreader.data.repository.AppThemeConfig
import cloud.wafflecommons.pixelbrainreader.data.repository.UserPreferencesRepository
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.material.icons.filled.HealthAndSafety
import androidx.hilt.navigation.compose.hiltViewModel
import android.app.Activity
import android.widget.Toast
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.DateRange
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.permission.HealthPermission
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onNavigateToHabitConfig: () -> Unit = {},
    onNavigateToHomeConfig: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val moodEmojiMapping by viewModel.moodEmojiMapping.collectAsStateWithLifecycle()
    val nanoState by viewModel.nanoState.collectAsStateWithLifecycle()
    val scrollState = rememberScrollState()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    // Surface gating errors from the AI selection (e.g. picking Local AI before
    // the model is downloaded) as a snackbar.
    LaunchedEffect(Unit) {
        viewModel.nanoModelEvents.collect { event ->
            when (event) {
                is SettingsViewModel.NanoModelEvent.MustDownloadFirst ->
                    snackbarHostState.showSnackbar(event.reason)
            }
        }
    }
    
    // Health Connect Permission Launcher
    val permissions = remember {
        setOf(
            HealthPermission.getReadPermission(StepsRecord::class),
            HealthPermission.getReadPermission(SleepSessionRecord::class),
            HealthPermission.getReadPermission(HeartRateRecord::class)
        )
    }

    val healthPermissionLauncher = rememberLauncherForActivityResult(
        PermissionController.createRequestPermissionResultContract()
    ) { granted ->
        if (granted.containsAll(permissions)) {
             Toast.makeText(context, "Permissions Granted!", Toast.LENGTH_SHORT).show()
             viewModel.checkHealthConnectStatus()
             viewModel.syncHealthData()
        } else {
             Toast.makeText(context, "Permissions Denied or Partial", Toast.LENGTH_SHORT).show()
             viewModel.checkHealthConnectStatus() // Update UI anyway
        }
    }


    val coroutineScope = rememberCoroutineScope()

    // V6: Credential Manager flow.
    // signIn() is suspending and triggered from the VM; AuthorizationClient may
    // surface a consent IntentSender, which we resolve through StartIntentSenderForResult.
    val activity = LocalContext.current as Activity
    val consentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        viewModel.onConsentResolved(result.data)
    }

    LaunchedEffect(Unit) {
        viewModel.googleAuthEvents.collect { event ->
            when (event) {
                is SettingsViewModel.GoogleAuthEvent.ConsentRequired ->
                    consentLauncher.launch(IntentSenderRequest.Builder(event.intentSender).build())
                SettingsViewModel.GoogleAuthEvent.Linked ->
                    Toast.makeText(context, "Google account linked", Toast.LENGTH_SHORT).show()
                is SettingsViewModel.GoogleAuthEvent.Failed ->
                    Toast.makeText(context, "Google: ${event.message}", Toast.LENGTH_LONG).show()
            }
        }
    }
    
    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            cloud.wafflecommons.pixelbrainreader.ui.components.CortexTopAppBar(
                title = "Settings",
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(scrollState)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            
            // 0. Integrations (Health Connect)
            SettingsSection(
                title = "Integrations",
                icon = Icons.Default.HealthAndSafety
            ) {
                 val status = uiState.healthConnectStatus
                 val isConnected = uiState.healthConnectPermissionsGranted
                 
                 Card(
                     colors = CardDefaults.cardColors(
                         containerColor = if (isConnected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
                     ),
                     onClick = {
                         if (!isConnected) {
                             if (status == HealthConnectClient.SDK_UNAVAILABLE || status == HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED) {
                                 // Prompt install (simplified)
                             } else {
                                 Toast.makeText(context, "Requesting Permissions...", Toast.LENGTH_SHORT).show()
                                 val perms = setOf(
                                     HealthPermission.getReadPermission(StepsRecord::class),
                                     HealthPermission.getReadPermission(SleepSessionRecord::class),
                                     HealthPermission.getReadPermission(HeartRateRecord::class)
                                 )
                                 healthPermissionLauncher.launch(perms)
                             }
                         }
                     }
                 ) {
                     Row(
                         modifier = Modifier.padding(16.dp).fillMaxWidth(),
                         verticalAlignment = Alignment.CenterVertically,
                         horizontalArrangement = Arrangement.SpaceBetween
                     ) {
                         Column {
                             Text(
                                 text = "Health Connect",
                                 style = MaterialTheme.typography.titleMedium,
                                 fontWeight = FontWeight.Bold
                             )
                             Text(
                                 text = if (isConnected) "Connected & Syncing Active" else "Sync Steps & Sleep",
                                 style = MaterialTheme.typography.bodyMedium
                             )
                         }
                         if (!isConnected) {
                             Button(onClick = { 
                                 Toast.makeText(context, "Requesting Permissions...", Toast.LENGTH_SHORT).show()
                                 val perms = setOf(
                                     HealthPermission.getReadPermission(StepsRecord::class),
                                     HealthPermission.getReadPermission(SleepSessionRecord::class),
                                     HealthPermission.getReadPermission(HeartRateRecord::class)
                                 )
                                 healthPermissionLauncher.launch(perms)
                             }) {
                                 Text("Connect")
                             }
                         } else {
                             Icon(imageVector = Icons.Default.CheckCircle, contentDescription = "Connected")
                         }
                     }
                 }
            }

            // 0b. Google Ecosystem
            SettingsSection(
                title = "Écosystème Google",
                icon = Icons.Rounded.AccountCircle
            ) {
                val isEnabled = uiState.isGoogleSyncEnabled
                val isLinked = uiState.isGoogleAccountLinked

                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = if (isLinked) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant
                    ),
                    onClick = {
                        if (!isEnabled) {
                            viewModel.connectGoogle(activity)
                        } else {
                            viewModel.setGoogleSyncEnabled(false)
                        }
                    }
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp).fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Google Calendar & Tasks",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = if (isLinked) "Synced (Read-Only)" else "Connect your Google account",
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                        Switch(
                            checked = isEnabled,
                            onCheckedChange = { checked ->
                                if (checked) {
                                    viewModel.connectGoogle(activity)
                                } else {
                                    viewModel.setGoogleSyncEnabled(false)
                                }
                            }
                        )
                    }
                }
            }

            // 1. Intelligence Section
            SettingsSection(
                title = "Intelligence",
                icon = Icons.Default.Psychology
            ) {
                cloud.wafflecommons.pixelbrainreader.data.model.AiModel.entries.forEach { model ->
                    val isSelected = (uiState.currentAiModel == model)

                    val subtitle = when(model) {
                         cloud.wafflecommons.pixelbrainreader.data.model.AiModel.GEMINI_FLASH -> "Fast & Efficient. Requires Internet."
                         cloud.wafflecommons.pixelbrainreader.data.model.AiModel.GEMINI_PRO -> "Maximum reasoning. Requires Internet."
                         cloud.wafflecommons.pixelbrainreader.data.model.AiModel.CORTEX_LOCAL -> "Gemini Nano. 100% Private & Offline."
                    }

                    // Local AI radio is disabled while the on-device model is not yet
                    // Ready — the user must explicitly download it via the panel below.
                    val isLocal = model == cloud.wafflecommons.pixelbrainreader.data.model.AiModel.CORTEX_LOCAL
                    val enabled = !isLocal || nanoState is NanoState.Ready

                    IntelligenceOption(
                        title = model.displayName,
                        subtitle = subtitle,
                        selected = isSelected,
                        enabled = enabled,
                        onClick = { viewModel.updateAiModel(model) }
                    )

                    if (isLocal) {
                        NanoModelLifecyclePanel(
                            state = nanoState,
                            onDownload = viewModel::onDownloadNanoModel,
                            onManageStorage = viewModel::onOpenNanoModelSettings
                        )
                    }
                }
            }

            // 2. Interface Section
            SettingsSection(
                title = "Interface",
                icon = Icons.Default.BrightnessMedium
            ) {
                 Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    AppThemeConfig.entries.forEach { config ->
                        FilterChip(
                            selected = (uiState.themeConfig == config),
                            onClick = { viewModel.updateTheme(config) },
                            label = { 
                                Text(
                                    when(config) {
                                        AppThemeConfig.FOLLOW_SYSTEM -> "System"
                                        AppThemeConfig.LIGHT -> "Light"
                                        AppThemeConfig.DARK -> "Dark"
                                    }
                                )
                            }
                        )
                    }
                }
            }

            // 3. Life OS & Gamification
            SettingsSection(
                title = "Life OS Automations",
                icon = Icons.AutoMirrored.Filled.List
            ) {
                ListItem(
                    modifier = Modifier.clickable { onNavigateToHabitConfig() },
                    headlineContent = { Text("Manage Habits & Automations") },
                    leadingContent = { Icon(Icons.Rounded.DateRange, contentDescription = null) }
                )
                
                ListItem(
                    modifier = Modifier.clickable { onNavigateToHomeConfig() },
                    headlineContent = { Text("Manage House & Chores") },
                    leadingContent = { Icon(Icons.Rounded.CleaningServices, contentDescription = null) }
                )
                
                Spacer(Modifier.height(8.dp))
                
                val isSyncingConfigs by viewModel.isSyncingConfigs.collectAsStateWithLifecycle()

                Button(
                    onClick = {
                        viewModel.syncAllConfigsToVault { success ->
                             coroutineScope.launch {
                                 if(success) {
                                     snackbarHostState.showSnackbar("All configuration (Habits, Home OS) synced and pushed!")
                                 } else {
                                     snackbarHostState.showSnackbar("Failed to sync configurations. Check Git logs.")
                                 }
                             }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isSyncingConfigs
                ) {
                    if (isSyncingConfigs) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), color = MaterialTheme.colorScheme.onPrimary)
                    } else {
                        Icon(Icons.Default.Sync, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Export & Sync All Configurations (Vault)")
                    }
                }

                Spacer(Modifier.height(8.dp))
                
                OutlinedButton(
                    onClick = {
                        viewModel.forceSyncHabits {
                            coroutineScope.launch {
                                snackbarHostState.showSnackbar("Habit configuration pulled and imported from Vault.")
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isSyncingConfigs
                ) {
                    Icon(Icons.Default.Sync, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Force Import Habits (Vault -> App)")
                }
            }

            // 3.5 Knowledge Vault — manual RAG indexing
            SettingsSection(
                title = "Knowledge Vault (RAG)",
                icon = Icons.Default.Psychology
            ) {
                val indexingState by viewModel.indexingState.collectAsStateWithLifecycle()
                val isRunning = indexingState is SettingsViewModel.IndexingState.Running ||
                    indexingState is SettingsViewModel.IndexingState.Enqueued

                Text(
                    text = "Embedding the vault is now manual. Tap below to embed only the files that changed since your last index. Private notes are included if the vault has been unlocked.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(Modifier.height(12.dp))

                Button(
                    onClick = { viewModel.triggerVaultIndexing() },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isRunning
                ) {
                    if (isRunning) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            color = MaterialTheme.colorScheme.onPrimary,
                            strokeWidth = 2.dp
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            when (indexingState) {
                                SettingsViewModel.IndexingState.Enqueued -> "Queued…"
                                SettingsViewModel.IndexingState.Running -> "Indexing…"
                                else -> "Working…"
                            }
                        )
                    } else {
                        Icon(Icons.Default.Memory, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Index Knowledge Vault (delta)")
                    }
                }

                // Live status chip — reflects the terminal state of the last
                // run; the user can dismiss it to return to a clean idle button.
                when (val state = indexingState) {
                    is SettingsViewModel.IndexingState.Succeeded -> {
                        Spacer(Modifier.height(8.dp))
                        AssistChip(
                            onClick = { viewModel.dismissIndexingState() },
                            label = { Text("Last run: succeeded ✓") },
                            leadingIcon = {
                                Icon(
                                    Icons.Default.CheckCircle,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        )
                    }
                    is SettingsViewModel.IndexingState.Failed -> {
                        Spacer(Modifier.height(8.dp))
                        AssistChip(
                            onClick = { viewModel.dismissIndexingState() },
                            label = { Text("Last run failed: ${state.reason}") },
                            colors = AssistChipDefaults.assistChipColors(
                                labelColor = MaterialTheme.colorScheme.error
                            )
                        )
                    }
                    else -> Unit
                }
            }

            // 4. About
             SettingsSection(
                title = "About",
                icon = Icons.Default.Info
            ) {
                Text(
                    text = "Pixel Brain Reader v${uiState.appVersion}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            
            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
fun SettingsSection(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    content: @Composable () -> Unit
) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = icon, 
                contentDescription = null, 
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.primary
            )
        }
        Spacer(modifier = Modifier.height(16.dp))
        content()
    }
}

@Composable
fun IntelligenceOption(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit,
    enabled: Boolean = true
) {
    val rowAlpha = if (enabled) 1f else 0.5f
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(
            selected = selected,
            onClick = onClick,
            enabled = enabled
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.alpha(rowAlpha)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * Cohesive panel rendered directly below the "Local AI" radio option. Drives the
 * full on-device model lifecycle — Download, Progress, Ready, Error — without
 * leaving the existing AI selection section.
 */
@Composable
private fun NanoModelLifecyclePanel(
    state: NanoState,
    onDownload: () -> Unit,
    onManageStorage: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 52.dp, end = 4.dp, top = 4.dp, bottom = 12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 1.dp
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            when (state) {
                NanoState.Unknown, NanoState.Checking -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            text = "Checking model availability…",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
                NanoState.NotDownloaded -> {
                    Button(
                        onClick = onDownload,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Download, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Download Gemini Nano (~1.5 GB)")
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "Stays on-device. Required before selecting Local AI.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                is NanoState.Downloading -> {
                    val label = if (state.totalBytes > 0L) {
                        "Downloading model… ${formatBytes(state.bytesDownloaded)} / ${formatBytes(state.totalBytes)}"
                    } else {
                        "Downloading model…"
                    }
                    Text(text = label, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(8.dp))
                    if (state.progress in 0f..1f) {
                        LinearProgressIndicator(
                            progress = { state.progress },
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }
                NanoState.Ready -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Model ready for offline use",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "Manage storage in AICore settings",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(onClick = onManageStorage) {
                            Icon(
                                imageVector = Icons.Outlined.FolderOpen,
                                contentDescription = "Manage model storage in AICore settings"
                            )
                        }
                    }
                }
                is NanoState.Unavailable -> {
                    Text(
                        text = "Local AI unavailable on this device: ${state.reason}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                is NanoState.Error -> {
                    Text(
                        text = "Download failed: ${state.cause.localizedMessage ?: state.cause.message ?: state.cause::class.java.simpleName}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = onDownload,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Retry download")
                    }
                }
            }
        }
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0L) return "0 MB"
    val mb = bytes / 1_000_000.0
    if (mb >= 1024.0) return "%.2f GB".format(mb / 1024.0)
    return "%.0f MB".format(mb)
}
