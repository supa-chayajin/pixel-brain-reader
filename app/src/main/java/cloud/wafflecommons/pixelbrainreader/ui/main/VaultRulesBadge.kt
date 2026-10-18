package cloud.wafflecommons.pixelbrainreader.ui.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Rule
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import cloud.wafflecommons.pixelbrainreader.data.repository.VaultRulesRepository
import cloud.wafflecommons.pixelbrainreader.data.vault.LintIssue
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class VaultRulesViewModel @Inject constructor(private val repository: VaultRulesRepository) : ViewModel() {

    private val _issues = MutableStateFlow<List<LintIssue>>(emptyList())
    val issues: StateFlow<List<LintIssue>> = _issues.asStateFlow()

    private var job: Job? = null

    /** Re-checks a moment after the text stops changing (the editor calls this per keystroke). */
    fun check(path: String, content: String) {
        job?.cancel()
        job = viewModelScope.launch {
            delay(CHECK_DELAY_MS)
            _issues.value = try {
                repository.lint(path, content)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emptyList()
            }
        }
    }

    fun fixed(path: String, content: String): String = repository.fixed(path, content)

    private companion object {
        const val CHECK_DELAY_MS = 800L
    }
}

/** The vault's linting contract, advisory (E7): a count on the note header, the details in a sheet. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VaultRulesBadge(path: String, content: String, onFix: (String) -> Unit, viewModel: VaultRulesViewModel = hiltViewModel()) {
    LaunchedEffect(path, content) { viewModel.check(path, content) }
    val issues by viewModel.issues.collectAsStateWithLifecycle()
    var open by remember { mutableStateOf(false) }
    if (issues.isEmpty()) return
    IconButton(onClick = { open = true }, modifier = Modifier.size(36.dp)) {
        BadgedBox(badge = { Badge { Text("${issues.size}") } }) {
            Icon(Icons.Rounded.Rule, contentDescription = "Vault rules", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (open) {
        ModalBottomSheet(onDismissRequest = { open = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            Column(modifier = Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Vault rules", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(
                    "What the vault's linter would report. Advisory: the desktop mutator stays the reference.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                issues.forEach { issue ->
                    Text("${issue.rule} · ${issue.message}" + if (issue.fixable) " (fixable)" else "", style = MaterialTheme.typography.bodyMedium)
                }
                if (issues.any { it.fixable }) {
                    Button(onClick = { onFix(viewModel.fixed(path, content)); open = false }) { Text("Fix what's safe") }
                }
            }
        }
    }
}
