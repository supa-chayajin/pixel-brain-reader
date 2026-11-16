@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package cloud.wafflecommons.pixelbrainreader.ui.privatevault

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.biometric.BiometricManager
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
import androidx.compose.material.icons.filled.Password
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.*
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
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
import cloud.wafflecommons.pixelbrainreader.data.device.VaultWriteGate
import cloud.wafflecommons.pixelbrainreader.data.privatevault.AutoLock
import cloud.wafflecommons.pixelbrainreader.data.privatevault.PasswordRules
import cloud.wafflecommons.pixelbrainreader.data.privatevault.PrivateVaultSession
import cloud.wafflecommons.pixelbrainreader.data.privatevault.VaultPasswordBinding
import cloud.wafflecommons.pixelbrainreader.ui.components.ComposeCortexEditor
import cloud.wafflecommons.pixelbrainreader.ui.components.CortexIconButton
import cloud.wafflecommons.pixelbrainreader.ui.theme.PixelBrainReaderTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.concurrent.Executor
import javax.inject.Inject
import androidx.compose.material.icons.rounded.Lock

@AndroidEntryPoint
class PrivateJournalActivity : FragmentActivity() {

    @Inject lateinit var binding: VaultPasswordBinding
    @Inject lateinit var session: PrivateVaultSession
    private val viewModel: PrivateJournalViewModel by viewModels()
    private val peopleViewModel: PeopleViewModel by viewModels()

    /** The prompt comes up at the first start, and on a return after the auto-lock: not after "Use password". */
    private var promptOnStart = true
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
                        peopleViewModel = peopleViewModel,
                        initialTab = if (intent.getStringExtra(EXTRA_TAB) == TAB_PEOPLE) JournalTab.PEOPLE else JournalTab.NOTES,
                        onAuthenticate = { launchBiometric() },
                        onBack = { finish() }
                    )
                }
            }
        }
        
        setupBiometrics()

        // 12.19: the toolkit and a meeting's prep open a person (and a 1:1's entry) once unlocked.
        // A fresh launch only: a recreation must not reopen what the owner already closed.
        if (savedInstanceState == null) {
            intent.getStringExtra(EXTRA_PERSON)?.let { person ->
                val day = intent.getStringExtra(EXTRA_ONE_ON_ONE)?.let { runCatching { java.time.LocalDate.parse(it) }.getOrNull() }
                peopleViewModel.openWhenUnlocked(person, day)
            }
        }
    }

    /** On screen: the session doesn't count down (12.18, V2). */
    override fun onStart() {
        super.onStart()
        session.hold()
        if (promptOnStart && !session.isOpen()) launchBiometric()
        promptOnStart = false
    }

    /** Off screen (another app, Home, the screen turning off): the auto-lock's delay starts. */
    override fun onStop() {
        // Unlocked when it left: if the auto-lock closes it meanwhile, the return asks again.
        promptOnStart = session.isOpen()
        session.release()
        super.onStop()
    }

    /**
     * 12.18 (V3, owner's D5): the prompt opens the password's biometric key, so only a strong
     * biometric can answer it. The system tries the face first when the phone rates it strong, then
     * the fingerprint; "Use password" is the last resort and the phone's PIN never opens it.
     */
    private fun setupBiometrics() {
        executor = ContextCompat.getMainExecutor(this)
        biometricPrompt = BiometricPrompt(this, executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    super.onAuthenticationError(errorCode, errString)
                    // "Use password" and a dismissal are choices, not errors.
                    if (errorCode in CHOSEN_EXITS) return
                    viewModel.onAuthError(errString.toString())
                }

                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    val cipher = result.cryptoObject?.cipher ?: run {
                        viewModel.onAuthError("Type your password.")
                        return
                    }
                    lifecycleScope.launch {
                        val password = withContext(Dispatchers.Default) { binding.unwrap(cipher) }
                        if (password == null) {
                            viewModel.onAuthError("This phone's copy of the password can't be read: type it once.")
                            return@launch
                        }
                        viewModel.unlock(password, viaBiometric = true)
                        java.util.Arrays.fill(password, '\u0000')
                    }
                }
            })

        promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock the private journal")
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .setNegativeButtonText("Use password")
            // A recognized face opens the journal at once, without a confirm tap.
            .setConfirmationRequired(false)
            .build()
    }

    /** Asks for the face or fingerprint when the password is bound; without a binding the password is typed. */
    private fun launchBiometric() {
        lifecycleScope.launch {
            val cipher = withContext(Dispatchers.IO) { binding.cipherForUnlock() } ?: return@launch
            if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return@launch
            biometricPrompt.authenticate(promptInfo, BiometricPrompt.CryptoObject(cipher))
        }
    }

    companion object {
        const val EXTRA_TAB = "tab"
        const val EXTRA_PERSON = "person"
        const val EXTRA_ONE_ON_ONE = "one_on_one"
        const val TAB_PEOPLE = "people"

        /** The journal on its People tab (12.19): a [person]'s page, and their 1:1 entry of [oneOnOne] when given. */
        fun peopleIntent(context: android.content.Context, person: String? = null, oneOnOne: java.time.LocalDate? = null): Intent =
            Intent(context, PrivateJournalActivity::class.java).putExtra(EXTRA_TAB, TAB_PEOPLE).apply {
                person?.let { putExtra(EXTRA_PERSON, it) }
                oneOnOne?.let { putExtra(EXTRA_ONE_ON_ONE, it.toString()) }
            }

        private val CHOSEN_EXITS = setOf(
            BiometricPrompt.ERROR_NEGATIVE_BUTTON,
            BiometricPrompt.ERROR_USER_CANCELED,
            BiometricPrompt.ERROR_CANCELED
        )
    }
}

/** The journal's two tabs (12.19): its notes, and the People notes. */
enum class JournalTab { NOTES, PEOPLE }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrivateVaultScreen(
    viewModel: PrivateJournalViewModel,
    peopleViewModel: PeopleViewModel,
    initialTab: JournalTab,
    onAuthenticate: () -> Unit,
    onBack: () -> Unit
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(initialTab) }
    var addingPerson by rememberSaveable { mutableStateOf(false) }
    if (addingPerson) {
        AddPersonDialog(
            onAdd = { name, kind, role ->
                peopleViewModel.add(name, kind, role)
                addingPerson = false
            },
            onDismiss = { addingPerson = false }
        )
    }
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
                }, shapes = ButtonDefaults.shapes()) { Text("Create") }
            },
            dismissButton = {
                TextButton(onClick = { showCreateDialog = false }, shapes = ButtonDefaults.shapes()) { Text("Cancel") }
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

                // Only once the password is bound to the biometric key (V3): before, the password is typed.
                val biometricReady by viewModel.biometricReady.collectAsStateWithLifecycle()
                if (biometricReady) {
                    // The system's own words for the strong biometrics enrolled ("Use face or fingerprint").
                    val unlockLabel = remember(context) {
                        context.getSystemService(android.hardware.biometrics.BiometricManager::class.java)
                            ?.getStrings(android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_STRONG)
                            ?.buttonLabel?.toString()
                    } ?: "Unlock with biometrics"
                    Button(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onAuthenticate()
                        },
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                        shapes = ButtonDefaults.shapes(shape = MaterialTheme.shapes.large)
                    ) {
                        Text(unlockLabel)
                    }
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
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        viewModel.unlockWithPassword()
                    }, shapes = ButtonDefaults.shapes(),
                    enabled = state.passwordInput.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Unlock with Password")
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
        val openPerson by peopleViewModel.openId.collectAsStateWithLifecycle()
        val oneOnOne by peopleViewModel.entry.collectAsStateWithLifecycle()
        val people by peopleViewModel.list.collectAsStateWithLifecycle()
        val personId = openPerson
        when {
            tab == JournalTab.PEOPLE && oneOnOne != null -> OneOnOneEditor(peopleViewModel)
            tab == JournalTab.PEOPLE && personId != null -> PersonPage(peopleViewModel, personId)
            tab == JournalTab.NOTES && state.selectedFile != null -> {
             // Editor Mode
             // Handle Back Press to close editor first
             BackHandler {
                 viewModel.closeNote()
             }
             
             PrivateEditor(
                 file = state.selectedFile,
                 content = state.editorContent,
                 editorRevision = state.editorRevision,
                 title = state.selectedFile?.name?.removeSuffix(".md.enc") ?: "New Note",
                 saveState = viewModel.saveState.collectAsStateWithLifecycle().value,
                 onContentChange = { viewModel.onEditorContentChange(it) },
                 onClose = { viewModel.closeNote() },
                 onForceSave = { viewModel.forceSaveImmediate() },
                 onOpenAssist = { viewModel.openAssist() },
                 onBeautify = { viewModel.beautifyMarkdown(it) },
                 onDelete = { viewModel.deleteCurrentNote() }
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
            }
            else -> Scaffold(
                topBar = {
                    Column {
                        cloud.wafflecommons.pixelbrainreader.ui.components.CortexTopAppBar(
                            title = "Private Vault",
                            subtitle = if (tab == JournalTab.NOTES) "${state.files.size} secure notes"
                                else if (people.people.size == 1) "1 person" else "${people.people.size} people",
                            icon = Icons.Rounded.Lock,
                            navigationIcon = {
                                CortexIconButton(onClick = onBack) {
                                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                                }
                            },
                            actions = { JournalMenu(viewModel) }
                        )
                        PrimaryTabRow(selectedTabIndex = tab.ordinal) {
                            Tab(selected = tab == JournalTab.NOTES, onClick = { tab = JournalTab.NOTES }, text = { Text("Notes") })
                            Tab(selected = tab == JournalTab.PEOPLE, onClick = { tab = JournalTab.PEOPLE }, text = { Text("People") })
                        }
                    }
                },
                floatingActionButton = {
                    if (!VaultWriteGate.readOnly) FloatingActionButton(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            if (tab == JournalTab.NOTES) showCreateDialog = true else addingPerson = true
                        },
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                    ) {
                        Icon(Icons.Default.Add, if (tab == JournalTab.NOTES) "New Note" else "Add a person")
                    }
                }
            ) { padding ->
                if (tab == JournalTab.PEOPLE) {
                    PeopleList(peopleViewModel, padding)
                } else {
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
                             Box(Modifier.animateItem()) {
                                 VaultFileItem(file = file, onClick = { viewModel.openNote(file) })
                             }
                         }
                     }
                 }
                }
            }
        }
        CoachSheet(peopleViewModel)
    }
}

/** The journal's menu (12.18): lock now, and how long it waits off screen before it locks (V2). */
@Composable
private fun JournalMenu(viewModel: PrivateJournalViewModel) {
    val autoLock by viewModel.autoLockMinutes.collectAsStateWithLifecycle()
    var open by remember { mutableStateOf(false) }
    var choosingDelay by remember { mutableStateOf(false) }
    var changingPassword by remember { mutableStateOf(false) }

    CortexIconButton(onClick = { open = true }) {
        Icon(Icons.Default.MoreVert, "More")
    }
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
        DropdownMenuItem(
            text = { Text("Lock now") },
            leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null) },
            onClick = {
                open = false
                viewModel.lockNow()
            }
        )
        DropdownMenuItem(
            text = { Text("Auto-lock: ${AutoLock.label(autoLock).replaceFirstChar { it.lowercase() }}") },
            leadingIcon = { Icon(Icons.Default.Timer, contentDescription = null) },
            onClick = {
                open = false
                choosingDelay = true
            }
        )
        if (!VaultWriteGate.readOnly) {
            DropdownMenuItem(
                text = { Text("Change password") },
                leadingIcon = { Icon(Icons.Default.Password, contentDescription = null) },
                onClick = {
                    open = false
                    changingPassword = true
                }
            )
        }
    }

    if (changingPassword) {
        ChangePasswordDialog(viewModel, onClose = { changingPassword = false })
    }

    if (choosingDelay) {
        AlertDialog(
            onDismissRequest = { choosingDelay = false },
            title = { Text("Auto-lock") },
            text = {
                Column {
                    Text(
                        "When the journal leaves the screen (another app, Home, the screen turning off), it locks " +
                            "after this delay. Locking also ends private notes in Chat answers.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    AutoLock.CHOICES.forEach { minutes ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    viewModel.setAutoLockMinutes(minutes)
                                    choosingDelay = false
                                }
                                .padding(vertical = 4.dp)
                        ) {
                            RadioButton(selected = minutes == autoLock, onClick = null)
                            Spacer(Modifier.width(12.dp))
                            Text(AutoLock.label(minutes), style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { choosingDelay = false }, shapes = ButtonDefaults.shapes()) { Text("Done") }
            }
        )
    }
}

/**
 * Changing the journal's password (12.18, V1): every private note is re-encrypted, crash-safe. The
 * current password is typed again; git history keeps the old versions under it (owner's D1).
 */
@Composable
private fun ChangePasswordDialog(viewModel: PrivateJournalViewModel, onClose: () -> Unit) {
    val change by viewModel.passwordChange.collectAsStateWithLifecycle()
    // Plain remember, never saved state: a password has no place in a saved instance bundle.
    var current by remember { mutableStateOf("") }
    var new by remember { mutableStateOf("") }
    var repeat by remember { mutableStateOf("") }
    val working = change is PrivateJournalViewModel.PasswordChange.Working
    val close = {
        viewModel.dismissPasswordChange()
        onClose()
    }

    AlertDialog(
        onDismissRequest = { if (!working) close() },
        title = { Text("Change password") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when (val state = change) {
                    is PrivateJournalViewModel.PasswordChange.Done -> Text(
                        "Password changed: ${state.files} private note(s) re-encrypted with the new one.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    else -> {
                        Text(
                            "Every private note is re-encrypted with the new password. Your vault's history keeps " +
                                "older versions of them under the current one.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        OutlinedTextField(
                            value = current,
                            onValueChange = { current = it },
                            label = { Text("Current password") },
                            singleLine = true,
                            enabled = !working,
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = new,
                            onValueChange = { new = it },
                            label = { Text("New password") },
                            supportingText = { Text("At least ${PasswordRules.MIN_LENGTH} characters") },
                            singleLine = true,
                            enabled = !working,
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = repeat,
                            onValueChange = { repeat = it },
                            label = { Text("New password again") },
                            singleLine = true,
                            enabled = !working,
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth()
                        )
                        if (state is PrivateJournalViewModel.PasswordChange.Working) {
                            LinearWavyProgressIndicator(
                                progress = { if (state.total > 0) state.done.toFloat() / state.total else 0f },
                                modifier = Modifier.fillMaxWidth()
                            )
                            Text(
                                if (state.total > 0) "Re-encrypting ${state.done} of ${state.total} notes…" else "Checking every note…",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        if (state is PrivateJournalViewModel.PasswordChange.Problem) {
                            Text(state.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (change is PrivateJournalViewModel.PasswordChange.Done) {
                TextButton(onClick = close, shapes = ButtonDefaults.shapes()) { Text("Done") }
            } else {
                Button(
                    onClick = { viewModel.changePassword(current, new, repeat) }, shapes = ButtonDefaults.shapes(),
                    enabled = !working && current.isNotEmpty() && new.isNotEmpty() && repeat.isNotEmpty()
                ) { Text("Change") }
            }
        },
        dismissButton = {
            if (change !is PrivateJournalViewModel.PasswordChange.Done) {
                TextButton(onClick = close, shapes = ButtonDefaults.shapes(), enabled = !working) { Text("Cancel") }
            }
        }
    )
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
    editorRevision: Int,
    title: String,
    saveState: cloud.wafflecommons.pixelbrainreader.ui.components.SaveState,
    onContentChange: (String) -> Unit,
    onClose: () -> Unit,
    onForceSave: () -> Unit,
    onOpenAssist: () -> Unit = {},
    onBeautify: suspend (String) -> String? = { null },
    onDelete: () -> Unit = {}
) {
    var showDeleteDialog by remember { mutableStateOf(false) }
    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Delete Private Note?") },
            text = { Text("Are you sure you want to permanently delete \"$title\"? This cannot be undone.") },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteDialog = false
                        onDelete()
                    }, shapes = ButtonDefaults.shapes(),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }, shapes = ButtonDefaults.shapes()) {
                    Text("Cancel")
                }
            }
        )
    }

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

    // Speech-to-text, ON THE DEVICE ONLY: this is the encrypted journal, so its audio never goes to
    // a network recognizer (no fallback). The transcript is spliced at the caret.
    val currentEditorValue by rememberUpdatedState(editorValue)
    val dictation = cloud.wafflecommons.pixelbrainreader.ui.utils.rememberOnDeviceDictation(
        allowNetworkFallback = false,
        onResult = { spoken ->
            val cur = currentEditorValue
            val start = cur.selection.min
            val end = cur.selection.max
            val before = cur.text.substring(0, start)
            val after = cur.text.substring(end)
            val needsSpace = before.isNotEmpty() && !before.last().isWhitespace()
            val insert = (if (needsSpace) " " else "") + spoken
            applyChange(TextFieldValue(before + insert + after, TextRange(start + insert.length)))
        },
        onMessage = { message -> Toast.makeText(context, message, Toast.LENGTH_LONG).show() }
    )
    cloud.wafflecommons.pixelbrainreader.ui.utils.DictationListeningDialog(dictation)

    fun startDictation() = dictation.start()

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
                    Toast.makeText(context, "Note changed — beautify skipped", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    Scaffold(
        topBar = {
            cloud.wafflecommons.pixelbrainreader.ui.components.CortexTopAppBar(
                title = title,
                icon = Icons.Rounded.Lock,
                navigationIcon = {
                    CortexIconButton(onClick = onClose) {
                         Icon(Icons.Default.Close, "Close")
                    }
                },
                actions = {
                    // A read-only device writes nothing and has no AI: the note is only read.
                    if (!VaultWriteGate.readOnly) {
                        // Dictate straight into the encrypted note.
                        CortexIconButton(onClick = { startDictation() }) {
                            Icon(Icons.Default.Mic, contentDescription = "Dictate")
                        }
                        // Beautify the SELECTED text as Markdown. Disabled when nothing is selected.
                        CortexIconButton(
                            onClick = { beautifySelection() },
                            enabled = hasSelection && !isBeautifying
                        ) {
                            if (isBeautifying) {
                                LoadingIndicator(modifier = Modifier.size(20.dp))
                            } else {
                                Icon(Icons.Default.AutoFixHigh, contentDescription = "Beautify selection as Markdown")
                            }
                        }
                        CortexIconButton(onClick = onOpenAssist) {
                            Icon(Icons.Default.AutoAwesome, contentDescription = "Writing assistant")
                        }
                        if (file != null) {
                            CortexIconButton(onClick = { showDeleteDialog = true }) {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = "Delete note",
                                    tint = MaterialTheme.colorScheme.error
                                )
                            }
                        }
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
                useMonospace = true,
                readOnly = VaultWriteGate.readOnly
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
            Text("Writing assistant", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(
                "Suggestions generated on-device, in French — nothing leaves the phone.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                androidx.compose.material3.FilledTonalButton(
                    onClick = { onAction(PrivateJournalViewModel.AssistAction.IMPROVE) },
                    modifier = Modifier.weight(1f)
                ) { Text("✨ Improve") }
                androidx.compose.material3.FilledTonalButton(
                    onClick = { onAction(PrivateJournalViewModel.AssistAction.CONTINUE) },
                    modifier = Modifier.weight(1f)
                ) { Text("➡️ Continue") }
            }
            androidx.compose.material3.FilledTonalButton(
                onClick = { onAction(PrivateJournalViewModel.AssistAction.INSPIRE) },
                modifier = Modifier.fillMaxWidth()
            ) { Text("💡 Inspire me") }

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
                            Text("Append")
                        }
                        androidx.compose.material3.Button(onClick = onReplace, modifier = Modifier.weight(1f)) {
                            Text("Replace")
                        }
                    }
                }
            }
        }
    }
}

// Add modifier alpha extension for older compose versions if needed, or use drawWithContent


