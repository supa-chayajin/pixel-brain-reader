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
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.rounded.HomeWork
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material3.*
import kotlinx.coroutines.launch
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cloud.wafflecommons.pixelbrainreader.data.ai.NanoState
import cloud.wafflecommons.pixelbrainreader.data.repository.UserPreferencesRepository
import cloud.wafflecommons.pixelbrainreader.ui.components.CortexIconButton
import cloud.wafflecommons.pixelbrainreader.ui.theme.NavBarClearance
import cloud.wafflecommons.pixelbrainreader.ui.utils.StaggeredEntry
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.material.icons.filled.HealthAndSafety
import androidx.hilt.navigation.compose.hiltViewModel
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.widget.Toast
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.DateRange
import androidx.compose.material.icons.rounded.Mood
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.permission.HealthPermission
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts

/** Walk the ContextWrapper chain to the hosting Activity (avoids casting LocalContext directly). */
private fun Context.findActivity(): Activity? {
    var ctx: Context? = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onNavigateToHabitConfig: () -> Unit = {},
    onNavigateToHomeConfig: () -> Unit = {},
    onNavigateToReminders: () -> Unit = {},
    onNavigateToNavBarReorder: () -> Unit = {},
    onNavigateToMoodTags: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val moodEmojiMapping by viewModel.moodEmojiMapping.collectAsStateWithLifecycle()
    val nanoState by viewModel.nanoState.collectAsStateWithLifecycle()
    val scrollState = rememberScrollState()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current

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
             Toast.makeText(context, "Autorisations accordées !", Toast.LENGTH_SHORT).show()
             viewModel.checkHealthConnectStatus()
             viewModel.syncHealthData()
        } else {
             Toast.makeText(context, "Autorisations refusées ou partielles", Toast.LENGTH_SHORT).show()
             viewModel.checkHealthConnectStatus() // Update UI anyway
        }
    }


    val coroutineScope = rememberCoroutineScope()

    // V6: Credential Manager flow.
    // signIn() is suspending and triggered from the VM; AuthorizationClient may
    // surface a consent IntentSender, which we resolve through StartIntentSenderForResult.
    val activity = remember(context) { context.findActivity() }
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
                    Toast.makeText(context, "Compte Google associé", Toast.LENGTH_SHORT).show()
                is SettingsViewModel.GoogleAuthEvent.Failed ->
                    Toast.makeText(context, "Google : ${event.message}", Toast.LENGTH_LONG).show()
            }
        }
    }
    
    Scaffold(
        snackbarHost = {
            // Lift the snackbar above the floating ExpressiveNavBar (overlaid by
            // MainScreen), which would otherwise cover the toasts entirely.
            SnackbarHost(
                snackbarHostState,
                modifier = Modifier.padding(bottom = NavBarClearance)
            )
        },
        topBar = {
            cloud.wafflecommons.pixelbrainreader.ui.components.CortexTopAppBar(
                title = "Paramètres",
                navigationIcon = {
                    CortexIconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Retour"
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
            StaggeredEntry(index = 0) {
            SettingsSection(
                title = "Intégrations",
                icon = Icons.Default.HealthAndSafety
            ) {
                 val status = uiState.healthConnectStatus
                 val isConnected = uiState.healthConnectPermissionsGranted
                 
                 Card(
                     colors = CardDefaults.cardColors(
                         containerColor = if (isConnected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
                     ),
                     onClick = {
                         haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                         if (!isConnected) {
                             if (status == HealthConnectClient.SDK_UNAVAILABLE || status == HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED) {
                                 // Prompt install (simplified)
                             } else {
                                 Toast.makeText(context, "Demande des autorisations...", Toast.LENGTH_SHORT).show()
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
                                 text = if (isConnected) "Connecté, synchronisation active" else "Synchroniser pas et sommeil",
                                 style = MaterialTheme.typography.bodyMedium
                             )
                         }
                         if (!isConnected) {
                             Button(onClick = { 
                                 Toast.makeText(context, "Demande des autorisations...", Toast.LENGTH_SHORT).show()
                                 val perms = setOf(
                                     HealthPermission.getReadPermission(StepsRecord::class),
                                     HealthPermission.getReadPermission(SleepSessionRecord::class),
                                     HealthPermission.getReadPermission(HeartRateRecord::class)
                                 )
                                 healthPermissionLauncher.launch(perms)
                             }) {
                                 Text("Connecter")
                             }
                         } else {
                             Icon(imageVector = Icons.Default.CheckCircle, contentDescription = "Connecté")
                         }
                     }
                 }
            }
            }

            // 0b. Google Ecosystem
            StaggeredEntry(index = 1) {
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
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        if (!isEnabled) {
                            activity?.let { viewModel.connectGoogle(it) }
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
                                text = if (isLinked) "Synchronisé (lecture seule)" else "Connectez votre compte Google",
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                        Switch(
                            checked = isEnabled,
                            onCheckedChange = { checked ->
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                if (checked) {
                                    activity?.let { viewModel.connectGoogle(it) }
                                } else {
                                    viewModel.setGoogleSyncEnabled(false)
                                }
                            }
                        )
                    }
                }
            }
            }

            // 1. Intelligence Section
            StaggeredEntry(index = 2) {
            SettingsSection(
                title = "Intelligence",
                icon = Icons.Default.Psychology
            ) {
                cloud.wafflecommons.pixelbrainreader.data.model.AiModel.entries.forEach { model ->
                    val isSelected = (uiState.currentAiModel == model)

                    val subtitle = when(model) {
                         cloud.wafflecommons.pixelbrainreader.data.model.AiModel.CORTEX_LOCAL -> "Gemini Nano. 100 % privé et hors ligne."
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
            }

            // Theme follows the system automatically (no manual selector — Android's
            // force-dark handling makes a per-app light/dark override unreliable).

            // 3. Knowledge Vault — manual RAG indexing (kept beside Intelligence — both are the on-device brain)
            StaggeredEntry(index = 3) {
            SettingsSection(
                title = "Coffre de connaissances (RAG)",
                icon = Icons.Default.Psychology
            ) {
                val indexingState by viewModel.indexingState.collectAsStateWithLifecycle()
                val isRunning = indexingState is SettingsViewModel.IndexingState.Running ||
                    indexingState is SettingsViewModel.IndexingState.Enqueued

                Text(
                    text = "L'indexation du coffre est désormais manuelle. Appuyez ci-dessous pour n'indexer que les fichiers modifiés depuis votre dernier index. Les notes privées sont incluses si le coffre a été déverrouillé.",
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
                                SettingsViewModel.IndexingState.Enqueued -> "En file d'attente…"
                                SettingsViewModel.IndexingState.Running -> "Indexation…"
                                else -> "En cours…"
                            }
                        )
                    } else {
                        Icon(Icons.Default.Memory, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Indexer le coffre de connaissances (delta)")
                    }
                }

                // Live status chip — reflects the terminal state of the last
                // run; the user can dismiss it to return to a clean idle button.
                when (val state = indexingState) {
                    is SettingsViewModel.IndexingState.Succeeded -> {
                        Spacer(Modifier.height(8.dp))
                        AssistChip(
                            onClick = { viewModel.dismissIndexingState() },
                            label = { Text("Dernière exécution : réussie ✓") },
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
                            label = { Text("Échec de la dernière exécution : ${state.reason}") },
                            colors = AssistChipDefaults.assistChipColors(
                                labelColor = MaterialTheme.colorScheme.error
                            )
                        )
                    }
                    else -> Unit
                }
            }
            }

            // 4. Life OS Automations
            StaggeredEntry(index = 4) {
            SettingsSection(
                title = "Automatisations Life OS",
                icon = Icons.AutoMirrored.Filled.List
            ) {
                ListItem(
                    modifier = Modifier.clickable {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onNavigateToHabitConfig()
                    },
                    leadingContent = { Icon(Icons.Rounded.DateRange, contentDescription = null) }
                ) {
                    Text("Gérer les habitudes et automatisations")
                }

                ListItem(
                    modifier = Modifier.clickable {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onNavigateToHomeConfig()
                    },
                    leadingContent = { Icon(Icons.Rounded.CleaningServices, contentDescription = null) }
                ) {
                    Text("Gérer la maison et les corvées")
                }

                ListItem(
                    modifier = Modifier.clickable {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onNavigateToReminders()
                    },
                    leadingContent = { Icon(Icons.Filled.Notifications, contentDescription = null) }
                ) {
                    Text("Rappels et notifications")
                }

                ListItem(
                    modifier = Modifier.clickable {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onNavigateToMoodTags()
                    },
                    leadingContent = { Icon(Icons.Rounded.Mood, contentDescription = null) }
                ) {
                    Text("Gérer les tags d'humeur")
                }

                Spacer(Modifier.height(8.dp))

                val isSyncingConfigs by viewModel.isSyncingConfigs.collectAsStateWithLifecycle()

                // Full config bridge between the vault and the app. Import pulls
                // Habits + Rooms + Chores from the vault into Room (fixes a fresh
                // sign-in where chores/rooms aren't yet imported); Export writes
                // the same set back to the vault and pushes. Both wipe-and-replace,
                // so Import first after a sign-in, Export after local edits.
                Text(
                    text = "Importer ramène les habitudes, pièces et corvées du coffre dans l'application. Exporter les renvoie vers le coffre et pousse. Les deux remplacent l'ensemble complet — importez d'abord après connexion, exportez après vos modifications ici.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(Modifier.height(8.dp))

                Button(
                    onClick = {
                        viewModel.importAllFromVault { success ->
                            coroutineScope.launch {
                                snackbarHostState.showSnackbar(
                                    if (success) "Tout importé (habitudes, pièces, corvées) depuis le coffre."
                                    else "Échec de l'import. Vérifiez les journaux Git."
                                )
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isSyncingConfigs
                ) {
                    if (isSyncingConfigs) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), color = MaterialTheme.colorScheme.onPrimary)
                    } else {
                        Icon(Icons.Default.Download, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Tout importer (Coffre → App)")
                    }
                }

                Spacer(Modifier.height(8.dp))

                OutlinedButton(
                    onClick = {
                        viewModel.exportAllToVault { success ->
                            coroutineScope.launch {
                                snackbarHostState.showSnackbar(
                                    if (success) "Tout exporté (habitudes, pièces, corvées) vers le coffre et poussé."
                                    else "Échec de l'export. Vérifiez les journaux Git."
                                )
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isSyncingConfigs
                ) {
                    Icon(Icons.Default.Upload, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Tout exporter (App → Coffre)")
                }
            }
            }

            // 5. Interface & Sound — app-level UI preferences
            StaggeredEntry(index = 5) {
            SettingsSection(
                title = "Interface et son",
                icon = Icons.Default.Tune
            ) {
                ListItem(
                    modifier = Modifier.clickable {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onNavigateToNavBarReorder()
                    },
                    leadingContent = { Icon(Icons.AutoMirrored.Filled.List, contentDescription = null) }
                ) {
                    Text("Ordre de la barre de navigation")
                }

                ListItem(
                    leadingContent = { Icon(Icons.Filled.VolumeUp, contentDescription = null) },
                    trailingContent = {
                        Switch(
                            checked = uiState.soundEffectsEnabled,
                            onCheckedChange = {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                viewModel.setSoundEffects(it)
                            }
                        )
                    }
                ) {
                    Text("Effets sonores")
                }
            }
            }

            // 6. About
            StaggeredEntry(index = 6) {
             SettingsSection(
                title = "À propos",
                icon = Icons.Default.Info
            ) {
                Text(
                    text = "Pixel Brain Reader v${uiState.appVersion}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            
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
    val haptic = LocalHapticFeedback.current
    val hapticOnClick = {
        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        onClick()
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = hapticOnClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(
            selected = selected,
            onClick = hapticOnClick,
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
                            text = "Vérification de la disponibilité du modèle…",
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
                        Text("Télécharger Gemini Nano (~1,5 Go)")
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "Reste sur l'appareil. Requis avant de sélectionner l'IA locale.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                is NanoState.Downloading -> {
                    val label = if (state.totalBytes > 0L) {
                        "Téléchargement du modèle… ${formatBytes(state.bytesDownloaded)} / ${formatBytes(state.totalBytes)}"
                    } else {
                        "Téléchargement du modèle…"
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
                                text = "Modèle prêt pour une utilisation hors ligne",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "Gérer le stockage dans les paramètres AICore",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        CortexIconButton(onClick = onManageStorage) {
                            Icon(
                                imageVector = Icons.Outlined.FolderOpen,
                                contentDescription = "Gérer le stockage du modèle dans les paramètres AICore"
                            )
                        }
                    }
                }
                is NanoState.Unavailable -> {
                    Text(
                        text = "IA locale indisponible sur cet appareil : ${state.reason}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                is NanoState.Error -> {
                    Text(
                        text = "Échec du téléchargement : ${state.cause.localizedMessage ?: state.cause.message ?: state.cause::class.java.simpleName}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = onDownload,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Réessayer le téléchargement")
                    }
                }
            }
        }
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0L) return "0 Mo"
    val mb = bytes / 1_000_000.0
    if (mb >= 1024.0) return "%.2f Go".format(mb / 1024.0)
    return "%.0f Mo".format(mb)
}
