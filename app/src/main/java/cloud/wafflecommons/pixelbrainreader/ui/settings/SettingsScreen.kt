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
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cloud.wafflecommons.pixelbrainreader.data.repository.AppThemeConfig
import cloud.wafflecommons.pixelbrainreader.data.repository.UserPreferencesRepository
import androidx.hilt.navigation.compose.hiltViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val scrollState = rememberScrollState()
    val snackbarHostState = remember { SnackbarHostState() }


    
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
            
            // 1. Intelligence Section
            SettingsSection(
                title = "Intelligence",
                icon = Icons.Default.Psychology
            ) {
                Text(
                    "Select the brain that powers your insights.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(16.dp))
                
                // Gemini 1.5 Pro
                IntelligenceOption(
                    title = "Gemini 1.5 Pro",
                    subtitle = "Maximum reasoning. Requires Internet.",
                    selected = (uiState.currentAiModel == UserPreferencesRepository.AiModel.GEMINI_PRO),
                    onClick = { viewModel.updateAiModel(UserPreferencesRepository.AiModel.GEMINI_PRO) }
                )

                // Gemini 1.5 Flash
                IntelligenceOption(
                    title = "Gemini 1.5 Flash",
                    subtitle = "Fast & Efficient. Requires Internet.",
                    selected = (uiState.currentAiModel == UserPreferencesRepository.AiModel.GEMINI_FLASH),
                    onClick = { viewModel.updateAiModel(UserPreferencesRepository.AiModel.GEMINI_FLASH) }
                )

                // Cortex (On-Device)
                val isCortex = (uiState.currentAiModel == UserPreferencesRepository.AiModel.CORTEX_ON_DEVICE)
                Card(
                     colors = CardDefaults.cardColors(
                         containerColor = if (isCortex) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
                     ),
                     border = if (isCortex) null else androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                     modifier = Modifier
                         .fillMaxWidth()
                         .padding(vertical = 4.dp)
                         .clickable { viewModel.updateAiModel(UserPreferencesRepository.AiModel.CORTEX_ON_DEVICE) }
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = isCortex,
                            onClick = { viewModel.updateAiModel(UserPreferencesRepository.AiModel.CORTEX_ON_DEVICE) }
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.Lock,
                                    contentDescription = "Private",
                                    modifier = Modifier.size(14.dp),
                                    tint = if (isCortex) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.primary
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "Cortex (On-Device)",
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                    color = if (isCortex) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface
                                )
                            }
                            Text(
                                "Gemini Nano. 100% Private & Offline.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (isCortex) MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
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
