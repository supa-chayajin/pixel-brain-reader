package cloud.wafflecommons.pixelbrainreader.ui.privatevault

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import cloud.wafflecommons.pixelbrainreader.ui.components.ComposeCortexEditor
import cloud.wafflecommons.pixelbrainreader.ui.components.CortexIconButton
import cloud.wafflecommons.pixelbrainreader.ui.theme.PixelBrainReaderTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
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
                     viewModel.onAuthError("Erreur d'authentification : $errString")
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
                        android.widget.Toast.makeText(this@PrivateJournalActivity, "Veuillez déverrouiller avec le mot de passe pour activer la biométrie.", android.widget.Toast.LENGTH_LONG).show()
                    }
                }

                override fun onAuthenticationFailed() {
                    super.onAuthenticationFailed()
                    viewModel.onAuthError("Échec de l'authentification")
                }
            })

        promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Déverrouiller le coffre privé")
            .setSubtitle("Authentifiez-vous avec vos données biométriques")
            .setNegativeButtonText("Utiliser le mot de passe")
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
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    
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
            title = { Text("Nouvelle note privée") },
            text = {
                OutlinedTextField(
                    value = filename,
                    onValueChange = { filename = it },
                    label = { Text("Nom du fichier") },
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
                }) { Text("Créer") }
            },
            dismissButton = {
                TextButton(onClick = { showCreateDialog = false }) { Text("Annuler") }
            }
        )
    }
    
    AnimatedVisibility(
        visible = state.isLocked,
        enter = fadeIn() + slideInVertically(),
        exit = fadeOut() + slideOutVertically()
    ) {
        // --- Locked Screen ---
        Box(
            modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface), 
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
                Icon(
                    imageVector = Icons.Default.Lock,
                    contentDescription = "Verrouillé",
                    modifier = Modifier.size(72.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(24.dp))
                Text(
                    "Coffre privé verrouillé",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "Chiffrement sécurisé actif. Captures d'écran désactivées.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                
                Spacer(modifier = Modifier.height(48.dp))
                
                Button(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onAuthenticate()
                    },
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    shape = MaterialTheme.shapes.large
                ) {
                    Text("Déverrouiller par biométrie")
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
                    label = { Text("Mot de passe principal") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(16.dp))
                FilledTonalButton(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        viewModel.unlockWithPassword()
                    },
                    enabled = state.passwordInput.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Déverrouiller avec le mot de passe")
                }
            }
        }
    }
    AnimatedVisibility(
        visible = !state.isLocked,
        enter = fadeIn() + slideInVertically(),
        exit = fadeOut() + slideOutVertically()
    ) {
        // --- Unlocked Content ---
        if (state.selectedFile != null) {
             // Editor Mode
             // Handle Back Press to close editor first
             BackHandler {
                 viewModel.closeNote()
             }
             
             PrivateEditor(
                 file = state.selectedFile,
                 content = state.editorContent,
                 editorRevision = state.editorRevision,
                 title = state.selectedFile?.name?.removeSuffix(".md.enc") ?: "Nouvelle note",
                 saveState = viewModel.saveState.collectAsStateWithLifecycle().value,
                 onContentChange = { viewModel.onEditorContentChange(it) },
                 onClose = { viewModel.closeNote() },
                 onForceSave = { viewModel.forceSaveImmediate() },
                 onOpenAssist = { viewModel.openAssist() },
                 onBeautify = { viewModel.beautifyMarkdown(it) }
             )

             val assist by viewModel.assistState.collectAsStateWithLifecycle()
             if (assist.visible) {
                 WritingAssistSheet(
                     state = assist,
                     onAction = { viewModel.runWritingAssist(it) },
                     onReplace = { viewModel.applyAssistReplace() },
                     onAppend = { viewModel.applyAssistAppend() },
                     onDismiss = { viewModel.dismissAssist() }
                 )
             }
        } else {
             // List Mode
             Scaffold(
                 topBar = {
                     TopAppBar(
                         title = { 
                             Column {
                                 Text("Coffre privé", fontWeight = FontWeight.SemiBold)
                                 Text(
                                     "${state.files.size} notes sécurisées",
                                     style = MaterialTheme.typography.bodySmall,
                                     color = MaterialTheme.colorScheme.onSurfaceVariant
                                 )
                             }
                         },
                         navigationIcon = {
                             CortexIconButton(onClick = onBack) {
                                 Icon(Icons.AutoMirrored.Filled.ArrowBack, "Retour")
                             }
                         }
                     )
                 },
                 floatingActionButton = {
                     FloatingActionButton(
                         onClick = {
                             haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                             showCreateDialog = true
                         },
                         containerColor = MaterialTheme.colorScheme.secondaryContainer,
                         contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                     ) {
                         Icon(Icons.Default.Add, "Nouvelle note")
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
                                 "Le coffre est vide",
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
                             Box(Modifier.animateItem()) {
                                 VaultFileItem(file = file, onClick = { viewModel.openNote(file) })
                             }
                         }
                     }
                 }
             }
        }
    }
}

@Composable
fun VaultFileItem(file: File, onClick: () -> Unit) {
    val haptic = LocalHapticFeedback.current
    ElevatedCard(
        onClick = {
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            onClick()
        },
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
                    contentDescription = "Sécurisé",
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
                     diff < 1 -> "À l'instant"
                     diff < 60 -> "il y a $diff min"
                     diff < 1440 -> "il y a ${diff / 60} h"
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
    editorRevision: Int,
    title: String,
    saveState: cloud.wafflecommons.pixelbrainreader.ui.components.SaveState,
    onContentChange: (String) -> Unit,
    onClose: () -> Unit,
    onForceSave: () -> Unit,
    onOpenAssist: () -> Unit = {},
    onBeautify: suspend (String) -> String? = { null }
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) {
                onForceSave()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Selection-aware editor state. The ViewModel owns the plain-string content (which drives the
    // encrypted autosave); this local TextFieldValue adds the cursor/selection the toolbar needs
    // for the "beautify selection" action. Offsets stay 1:1 (MarkdownVisualTransformation is Identity).
    var editorValue by remember { mutableStateOf(TextFieldValue(content, TextRange(content.length))) }
    // Reconcile ONLY on external replacements (note open, AI apply), keyed on editorRevision — NOT on
    // `content`, which is a laggy StateFlow echo of the user's own keystrokes and would jump the caret.
    LaunchedEffect(editorRevision) {
        if (content != editorValue.text) {
            editorValue = TextFieldValue(content, TextRange(content.length))
        }
    }
    var isBeautifying by remember { mutableStateOf(false) }
    val hasSelection = !editorValue.selection.collapsed

    fun applyChange(newValue: TextFieldValue) {
        editorValue = newValue
        onContentChange(newValue.text)
    }

    // Speech-to-text: the system dialog captures audio; we splice the transcript at the caret.
    val speechLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val spoken = result.data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
            if (!spoken.isNullOrBlank()) {
                val cur = editorValue
                val start = cur.selection.min
                val end = cur.selection.max
                val before = cur.text.substring(0, start)
                val after = cur.text.substring(end)
                val needsSpace = before.isNotEmpty() && !before.last().isWhitespace()
                val insert = (if (needsSpace) " " else "") + spoken
                val newText = before + insert + after
                applyChange(TextFieldValue(newText, TextRange(start + insert.length)))
            }
        }
    }

    fun startDictation() {
        // Pin dictation to French — the private journal is a French-first surface.
        val french = java.util.Locale.FRANCE.toLanguageTag() // "fr-FR"
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, french)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, french)
            putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, french)
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Dictez votre note…")
        }
        try {
            speechLauncher.launch(intent)
        } catch (e: android.content.ActivityNotFoundException) {
            Toast.makeText(context, "Reconnaissance vocale indisponible sur cet appareil", Toast.LENGTH_SHORT).show()
        }
    }

    fun beautifySelection() {
        if (!hasSelection || isBeautifying) return
        val sel = editorValue.selection
        val start = sel.min
        val end = sel.max
        val baseText = editorValue.text
        val selected = baseText.substring(start, end)
        scope.launch {
            isBeautifying = true
            val formatted = try { onBeautify(selected) } finally { isBeautifying = false }
            if (!formatted.isNullOrBlank()) {
                // The editor is still editable during the (multi-second) AI call. If the note changed
                // meanwhile, the captured offsets are stale — splicing would crash or corrupt, so skip.
                if (editorValue.text == baseText) {
                    val newText = baseText.substring(0, start) + formatted + baseText.substring(end)
                    applyChange(TextFieldValue(newText, TextRange(start, start + formatted.length)))
                } else {
                    Toast.makeText(context, "Note modifiée — embellissement ignoré", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

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
                    CortexIconButton(onClick = onClose) {
                         Icon(Icons.Default.Close, "Fermer")
                    }
                },
                actions = {
                    // Dictate straight into the encrypted note.
                    CortexIconButton(onClick = { startDictation() }) {
                        Icon(Icons.Default.Mic, contentDescription = "Dicter")
                    }
                    // Beautify the SELECTED text as Markdown. Disabled when nothing is selected.
                    CortexIconButton(
                        onClick = { beautifySelection() },
                        enabled = hasSelection && !isBeautifying
                    ) {
                        if (isBeautifying) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(Icons.Default.AutoFixHigh, contentDescription = "Embellir la sélection en Markdown")
                        }
                    }
                    CortexIconButton(onClick = onOpenAssist) {
                        Icon(Icons.Default.AutoAwesome, contentDescription = "Assistant d'écriture")
                    }
                    cloud.wafflecommons.pixelbrainreader.ui.components.SaveStatusIndicator(
                        state = saveState,
                        modifier = Modifier.padding(end = 16.dp)
                    )
                }
            )
        },
        // Inset content from BOTH the keyboard and the system bars: the editor's
        // bounded height then lets BasicTextField follow the caret, and content no
        // longer slides under the status bar.
        contentWindowInsets = WindowInsets.systemBars.union(WindowInsets.ime)
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize().padding(horizontal = 16.dp)) {
            ComposeCortexEditor(
                value = editorValue,
                onValueChange = { applyChange(it) },
                modifier = Modifier.fillMaxSize(),
                useMonospace = true
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WritingAssistSheet(
    state: PrivateJournalViewModel.AssistState,
    onAction: (PrivateJournalViewModel.AssistAction) -> Unit,
    onReplace: () -> Unit,
    onAppend: () -> Unit,
    onDismiss: () -> Unit
) {
    androidx.compose.material3.ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Assistant d'écriture", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(
                "Suggestions générées sur l'appareil, en français — rien ne quitte le téléphone.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                androidx.compose.material3.FilledTonalButton(
                    onClick = { onAction(PrivateJournalViewModel.AssistAction.IMPROVE) },
                    modifier = Modifier.weight(1f)
                ) { Text("✨ Améliorer") }
                androidx.compose.material3.FilledTonalButton(
                    onClick = { onAction(PrivateJournalViewModel.AssistAction.CONTINUE) },
                    modifier = Modifier.weight(1f)
                ) { Text("➡️ Continuer") }
            }
            androidx.compose.material3.FilledTonalButton(
                onClick = { onAction(PrivateJournalViewModel.AssistAction.INSPIRE) },
                modifier = Modifier.fillMaxWidth()
            ) { Text("💡 Inspire-moi") }

            when {
                state.isLoading -> {
                    Row(modifier = Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.Center) {
                        androidx.compose.material3.CircularProgressIndicator()
                    }
                }
                state.error != null -> {
                    Text(
                        "⚠️ ${state.error}",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                state.result != null -> {
                    androidx.compose.material3.HorizontalDivider()
                    Text(
                        state.result,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        androidx.compose.material3.OutlinedButton(onClick = onAppend, modifier = Modifier.weight(1f)) {
                            Text("Ajouter à la suite")
                        }
                        androidx.compose.material3.Button(onClick = onReplace, modifier = Modifier.weight(1f)) {
                            Text("Remplacer")
                        }
                    }
                }
            }
        }
    }
}

// Add modifier alpha extension for older compose versions if needed, or use drawWithContent


