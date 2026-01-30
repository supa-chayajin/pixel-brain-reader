package cloud.wafflecommons.pixelbrainreader.ui.privatevault

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.biometric.BiometricPrompt
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import cloud.wafflecommons.pixelbrainreader.ui.components.ComposeCortexEditor
import cloud.wafflecommons.pixelbrainreader.ui.theme.PixelBrainReaderTheme
import dagger.hilt.android.AndroidEntryPoint
import java.io.File
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.concurrent.Executor
import javax.inject.Inject

@AndroidEntryPoint
class PrivateJournalActivity : FragmentActivity() {

    @Inject lateinit var secretManager: cloud.wafflecommons.pixelbrainreader.data.local.security.SecretManager
    private val viewModel: PrivateJournalViewModel by viewModels()
    private lateinit var executor: Executor
    private lateinit var biometricPrompt: BiometricPrompt
    private lateinit var promptInfo: BiometricPrompt.PromptInfo

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // CRITICAL: Prevent Screenshots and Recents Preview
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )

        setContent {
            PixelBrainReaderTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    PrivateVaultScreen(
                        viewModel = viewModel,
                        onAuthenticate = { launchBiometric() },
                        onBack = { finish() }
                    )
                }
            }
        }
        
        setupBiometrics()
        // Auto-launch unless already unlocked (e.g. rotation)
        if (viewModel.uiState.value.isLocked) {
           launchBiometric()
        }
    }

    private fun setupBiometrics() {
        executor = ContextCompat.getMainExecutor(this)
        biometricPrompt = BiometricPrompt(this, executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    super.onAuthenticationError(errorCode, errString)
                     viewModel.onAuthError("Auth Error: $errString")
                }

                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    
                    // Retrieve Password securely
                    val storedPwd = secretManager.getVaultPassword()
                    if (!storedPwd.isNullOrEmpty()) {
                        viewModel.unlock(storedPwd.toCharArray())
                    } else {
                        // Biometric Success, but no password stored.
                        // Force manual unlock to set the password.
                        android.widget.Toast.makeText(this@PrivateJournalActivity, "Please unlock with password to enable biometrics.", android.widget.Toast.LENGTH_LONG).show()
                    }
                }

                override fun onAuthenticationFailed() {
                    super.onAuthenticationFailed()
                    viewModel.onAuthError("Authentication failed")
                }
            })

        promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock Private Vault")
            .setSubtitle("Log in using your biometric credential")
            .setNegativeButtonText("Use Password")
            .build()
    }

    private fun launchBiometric() {
        biometricPrompt.authenticate(promptInfo)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrivateVaultScreen(
    viewModel: PrivateJournalViewModel,
    onAuthenticate: () -> Unit,
    onBack: () -> Unit
) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    
    // Handle One-Time Events (Toasts)
    LaunchedEffect(key1 = true) {
        viewModel.uiEvent.collect { event ->
            when(event) {
                is PrivateJournalViewModel.UiEvent.ShowToast -> {
                    android.widget.Toast.makeText(context, event.message, android.widget.Toast.LENGTH_SHORT).show()
                }
                is PrivateJournalViewModel.UiEvent.OpenEditor -> {
                    // Handled by state.selectedFile currently
                }
            }
        }
    }

    var showCreateDialog by remember { mutableStateOf(false) }
    
    // New Note Dialog
    if (showCreateDialog) {
        val todayStr = java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ISO_DATE)
        var filename by remember { mutableStateOf(todayStr) }
        
        AlertDialog(
            onDismissRequest = { showCreateDialog = false },
            title = { Text("New Private Note") },
            text = {
                OutlinedTextField(
                    value = filename,
                    onValueChange = { filename = it },
                    label = { Text("Filename") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(onClick = {
                    if (filename.isNotBlank()) {
                        viewModel.createNote(filename)
                        showCreateDialog = false
                    }
                }) { Text("Create") }
            },
            dismissButton = {
                TextButton(onClick = { showCreateDialog = false }) { Text("Cancel") }
            }
        )
    }
    
    if (state.isLocked) {
        // --- Locked Screen ---
        Box(
            modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface), 
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
                Icon(
                    imageVector = Icons.Default.Lock,
                    contentDescription = "Locked",
                    modifier = Modifier.size(72.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(24.dp))
                Text(
                    "Private Vault Locked", 
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "Secure encryption active. Screenshot disabled.", 
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                
                Spacer(modifier = Modifier.height(48.dp))
                
                Button(
                    onClick = onAuthenticate,
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    shape = MaterialTheme.shapes.large
                ) {
                    Text("Unlock with Biometrics")
                }
                
                if (state.errorMessage != null) {
                    Spacer(modifier = Modifier.height(24.dp))
                    Text(
                        text = state.errorMessage!!, 
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                
                // Fallback Password Entry
                Spacer(modifier = Modifier.height(32.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(modifier = Modifier.height(32.dp))
                
                OutlinedTextField(
                    value = state.passwordInput,
                    onValueChange = { viewModel.onPasswordInputChanged(it) },
                    label = { Text("Master Password") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(16.dp))
                FilledTonalButton(
                    onClick = { viewModel.unlockWithPassword() },
                    enabled = state.passwordInput.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Unlock with Password")
                }
            }
        }
    } else {
        // --- Unlocked Content ---
        if (state.selectedFile != null) {
             // Editor Mode
             // Handle Back Press to close editor first
             BackHandler {
                 viewModel.saveNote() // Auto-save on back
                 viewModel.closeNote()
             }
             
             PrivateEditor(
                 file = state.selectedFile,
                 content = state.editorContent,
                 title = state.selectedFile?.name?.removeSuffix(".md.enc") ?: "New Note",
                 onContentChange = { viewModel.onEditorContentChange(it) },
                 onSave = { viewModel.saveNote() },
                 onClose = { 
                     viewModel.saveNote() 
                     viewModel.closeNote() 
                 },
                 onDelete = { viewModel.deleteCurrentNote() }
             )
        } else {
             // List Mode
             Scaffold(
                 topBar = {
                     TopAppBar(
                         title = { 
                             Column {
                                 Text("Private Vault", fontWeight = FontWeight.SemiBold)
                                 Text(
                                     "${state.files.size} secure notes", 
                                     style = MaterialTheme.typography.bodySmall,
                                     color = MaterialTheme.colorScheme.onSurfaceVariant
                                 )
                             }
                         },
                         navigationIcon = {
                             IconButton(onClick = onBack) {
                                 Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                             }
                         }
                     )
                 },
                 floatingActionButton = {
                     FloatingActionButton(
                         onClick = { showCreateDialog = true },
                         containerColor = MaterialTheme.colorScheme.secondaryContainer,
                         contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                     ) {
                         Icon(Icons.Default.Add, "New Note")
                     }
                 }
             ) { padding ->
                if (state.files.isEmpty()) {
                     Box(
                         modifier = Modifier.padding(padding).fillMaxSize(), 
                         contentAlignment = Alignment.Center
                     ) {
                         Column(horizontalAlignment = Alignment.CenterHorizontally) {
                             Icon(
                                 Icons.Default.Lock, 
                                 contentDescription = null, 
                                 modifier = Modifier.size(64.dp).alpha(0.2f),
                                 tint = MaterialTheme.colorScheme.onSurface 
                             )
                             Spacer(Modifier.height(16.dp))
                             Text(
                                 "Vault is empty",
                                 style = MaterialTheme.typography.titleMedium,
                                 color = MaterialTheme.colorScheme.onSurfaceVariant
                             )
                         }
                     }
                 } else {
                     LazyVerticalGrid(
                         columns = GridCells.Adaptive(minSize = 160.dp),
                         modifier = Modifier.padding(padding).fillMaxSize(),
                         contentPadding = PaddingValues(16.dp),
                         verticalArrangement = Arrangement.spacedBy(16.dp),
                         horizontalArrangement = Arrangement.spacedBy(16.dp)
                     ) {
                         items(state.files, key = { it.name }) { file ->
                             VaultFileItem(file = file, onClick = { viewModel.openNote(file) })
                         }
                     }
                 }
             }
        }
    }
}

@Composable
fun VaultFileItem(file: File, onClick: () -> Unit) {
    ElevatedCard(
        onClick = onClick,
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp),
        modifier = Modifier.fillMaxWidth().height(140.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp).fillMaxSize(),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                 Icon(
                    Icons.Default.Lock, 
                    contentDescription = "Secure",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
                // We could add a "More interactions" icon here later
            }
            
            Column {
                Text(
                    file.name.removeSuffix(".md.enc"),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(4.dp))
                
                val lastMod = LocalDateTime.ofInstant(
                     Instant.ofEpochMilli(file.lastModified()), 
                     ZoneId.systemDefault()
                )
                val diff = ChronoUnit.MINUTES.between(lastMod, LocalDateTime.now())
                val timeStr = when {
                     diff < 1 -> "Just now"
                     diff < 60 -> "$diff m ago"
                     diff < 1440 -> "${diff / 60} h ago"
                     else -> lastMod.format(DateTimeFormatter.ofPattern("MMM dd"))
                }
                Text(
                    timeStr, 
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrivateEditor(
    file: File?,
    content: String,
    title: String,
    onContentChange: (String) -> Unit,
    onSave: () -> Unit,
    onClose: () -> Unit,
    onDelete: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { 
                    Text(
                        title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    ) 
                },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                         Icon(Icons.Default.Close, "Close")
                    }
                },
                actions = {
                    IconButton(onClick = onDelete) {
                        Icon(Icons.Default.Delete, "Delete", tint = MaterialTheme.colorScheme.error)
                    }
                    IconButton(onClick = onSave) {
                        Icon(Icons.Default.Save, "Save", tint = MaterialTheme.colorScheme.primary)
                    }
                }
            )
        },
        // IMPORTANT: Prevent Content from being covered by Keyboard
        contentWindowInsets = WindowInsets.ime
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize().padding(horizontal = 16.dp)) {
            ComposeCortexEditor(
                content = content,
                onContentChange = onContentChange,
                modifier = Modifier.fillMaxSize(),
                useMonospace = true
            )
        }
    }
}

// Add modifier alpha extension for older compose versions if needed, or use drawWithContent


