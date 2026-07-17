package cloud.wafflecommons.pixelbrainreader.ui.login

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cloud.wafflecommons.pixelbrainreader.data.local.security.SecretManager
import cloud.wafflecommons.pixelbrainreader.data.repository.FileRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class LoginViewModel @Inject constructor(
    private val secretManager: SecretManager,
    private val repository: FileRepository,
    private val gitHubDeviceAuth: cloud.wafflecommons.pixelbrainreader.data.auth.GitHubDeviceAuthRepository
) : ViewModel() {

    private val _token = MutableStateFlow("")
    val token: StateFlow<String> = _token.asStateFlow()

    /** Whether the "Login with GitHub" entry point is available (client id configured). */
    val isGitHubAuthAvailable: Boolean = gitHubDeviceAuth.isConfigured

    /** Non-null while the user must approve a device code on github.com/login/device. */
    private val _deviceState = MutableStateFlow<GitHubDeviceState?>(null)
    val deviceState: StateFlow<GitHubDeviceState?> = _deviceState.asStateFlow()

    data class GitHubDeviceState(val userCode: String, val verificationUri: String)

    private val _repoUrl = MutableStateFlow("")
    val repoUrl: StateFlow<String> = _repoUrl.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _isTokenValid = MutableStateFlow(false)
    val isTokenValid: StateFlow<Boolean> = _isTokenValid.asStateFlow()

    private val _loginSuccess = MutableStateFlow(false)
    val loginSuccess: StateFlow<Boolean> = _loginSuccess.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    fun onTokenChanged(newToken: String) {
        _token.value = newToken
        checkValidity()
    }

    fun onRepoUrlChanged(newUrl: String) {
        _repoUrl.value = newUrl.trim()
        checkValidity()
    }

    private fun checkValidity() {
        val tokenValid = validateToken(_token.value)
        val repoValid = validateRepoUrl(_repoUrl.value) != null
        _isTokenValid.value = tokenValid && repoValid
    }

    private fun validateToken(token: String): Boolean {
        // Simple check, can be refined for PAT formats
        return token.isNotEmpty()
    }

    private fun validateRepoUrl(url: String): Triple<String, String, String>? {
        val githubRegex = Regex("github\\.com/([^/]+)/([^/.]+)")
        val gitlabRegex = Regex("gitlab\\.com/(.+)/([^/.]+)")

        // Check GitHub
        githubRegex.find(url)?.let { match ->
            val (owner, repo) = match.destructured
            return Triple(owner, repo, "github")
        }

        // Check GitLab
        gitlabRegex.find(url)?.let { match ->
            val (group, project) = match.destructured
            // GitLab "Owner" concept is Group/User path. "Repo" is project slug.
            // But API needs Project ID or URL Encoded path.
            // For now, let's treat group as owner and project as repo name.
            return Triple(group, project, "gitlab")
        }

        return null
    }

    /**
     * "Login with GitHub" — OAuth Device Flow. Fetches a user code, surfaces it (the UI opens
     * the browser), polls until approved, then drops the resulting access token into the same
     * token field the PAT flow uses. The user still supplies the repo URL and taps Connect.
     */
    fun startGitHubDeviceFlow() {
        if (!gitHubDeviceAuth.isConfigured) {
            _errorMessage.value = "GitHub login isn't configured on this build. Use a Personal Access Token."
            return
        }
        viewModelScope.launch {
            _isLoading.value = true
            _errorMessage.value = null
            val code = gitHubDeviceAuth.requestDeviceCode().getOrElse { e ->
                _errorMessage.value = "GitHub sign-in failed: ${e.message}"
                _isLoading.value = false
                return@launch
            }
            _deviceState.value = GitHubDeviceState(code.userCode, code.verificationUri)

            val tokenResult = gitHubDeviceAuth.pollForToken(code.deviceCode, code.interval, code.expiresIn)
            _deviceState.value = null
            _isLoading.value = false
            tokenResult.fold(
                onSuccess = { token ->
                    _token.value = token
                    checkValidity()
                    _errorMessage.value = null
                },
                onFailure = { e -> _errorMessage.value = "GitHub sign-in failed: ${e.message}" }
            )
        }
    }

    /** User dismissed the device-code dialog. (Polling stops when the VM scope is cleared.) */
    fun cancelGitHubDeviceFlow() {
        _deviceState.value = null
        _isLoading.value = false
    }

    fun onConnectClick() {
        if (!_isTokenValid.value) return

        val (owner, repo, provider) = validateRepoUrl(_repoUrl.value) ?: run {
            _errorMessage.value = "Invalid Repository URL"
            return
        }

        if (_repoUrl.value.startsWith("git@") || _repoUrl.value.startsWith("ssh://")) {
            _errorMessage.value = "SSH URLs are not supported. Please use HTTPS."
            return
        }

        viewModelScope.launch {
            _isLoading.value = true
            _errorMessage.value = null
            
            // 1. Secure Storage (Vault)
            secretManager.saveToken(_token.value.replace("\n", "").replace("\r", "").trim())
            secretManager.saveProvider(provider)
            secretManager.saveRepoInfo(owner, repo)
            
            // 2. Verification & Priming
            // We force a Full Mirror Sync to verify credentials and populate DB
            val result = repository.syncRepository(owner, repo)
            
            if (result.isSuccess) {
               _loginSuccess.value = true
               Log.d("StartLogin", "Vault sealed and Database primed.")
            } else {
                val error = result.exceptionOrNull()
                Log.e("StartLogin", "Login failed: ${error?.message}")
                
                val userFriendlyMessage = when {
                    error?.message?.contains("not authorized", ignoreCase = true) == true -> 
                        "Authentication failed. Please check your Token and its permissions (repo scope)."
                    error?.message?.contains("not found", ignoreCase = true) == true ->
                        "Repository not found. Please check the URL."
                    else -> error?.message ?: "An unexpected error occurred during sync."
                }
                _errorMessage.value = userFriendlyMessage
            }

            _isLoading.value = false
        }
    }
}
