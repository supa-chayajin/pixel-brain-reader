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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.*
import kotlinx.coroutines.launch
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cloud.wafflecommons.pixelbrainreader.data.repository.AppThemeConfig
import cloud.wafflecommons.pixelbrainreader.data.repository.UserPreferencesRepository
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.material.icons.filled.HealthAndSafety
import androidx.hilt.navigation.compose.hiltViewModel
import android.widget.Toast
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.permission.HealthPermission

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onNavigateToHabitConfig: () -> Unit = {},
    onNavigateToHomeConfig: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val moodEmojiMapping by viewModel.moodEmojiMapping.collectAsState()
    val scrollState = rememberScrollState()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    
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

                    IntelligenceOption(
                        title = model.displayName,
                        subtitle = subtitle,
                        selected = isSelected,
                        onClick = { viewModel.updateAiModel(model) }
                    )
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
                    leadingContent = { Icon(Icons.AutoMirrored.Filled.List, contentDescription = null) }
                )
                
                ListItem(
                    modifier = Modifier.clickable { onNavigateToHomeConfig() },
                    headlineContent = { Text("Ma Maison & Tâches") },
                    supportingContent = { Text("Gérez vos pièces et vos cycles de nettoyage") },
                    leadingContent = { Icon(Icons.Rounded.HomeWork, contentDescription = null) }
                )
                
                Spacer(Modifier.height(8.dp))
                
                OutlinedButton(
                    onClick = {
                        viewModel.forceSyncHabits {
                            coroutineScope.launch {
                                snackbarHostState.showSnackbar("Habits synchronized with Vault configuration")
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Sync, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.width(8.dp))
                    Text("Force Sync Habits from Vault JSON", color = MaterialTheme.colorScheme.error)
                }

                Spacer(Modifier.height(16.dp))

                // Mood Emoji Mapping
                Text("Mood Emoji Configuration", style = MaterialTheme.typography.titleSmall)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    (1..5).forEach { score ->
                        val currentEmoji = moodEmojiMapping[score] ?: ""
                        OutlinedTextField(
                            value = currentEmoji,
                            onValueChange = { newVal ->
                                // Limit to 2 characters to generally restrict to one emoji/cluster
                                if (newVal.length <= 2) {
                                    viewModel.updateMoodEmoji(score, newVal)
                                }
                            },
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 4.dp),
                            singleLine = true,
                            label = { Text("$score") },
                            textStyle = androidx.compose.ui.text.TextStyle(textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                        )
                    }
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
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(
            selected = selected,
            onClick = onClick
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column {
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
