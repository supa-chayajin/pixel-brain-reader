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
    private val repository: FileRepository
) : ViewModel() {

    private val _token = MutableStateFlow("")
    val token: StateFlow<String> = _token.asStateFlow()

    private val _repoUrl = MutableStateFlow("")
    val repoUrl: StateFlow<String> = _repoUrl.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _isTokenValid = MutableStateFlow(false)
    val isTokenValid: StateFlow<Boolean> = _isTokenValid.asStateFlow()

    private val _loginSuccess = MutableStateFlow(false)
    val loginSuccess: StateFlow<Boolean> = _loginSuccess.asStateFlow()

    fun onTokenChanged(newToken: String) {
        _token.value = newToken
        checkValidity()
    }

    fun onRepoUrlChanged(newUrl: String) {
        _repoUrl.value = newUrl
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

    fun onConnectClick() {
        if (!_isTokenValid.value) return

        val (owner, repo, provider) = validateRepoUrl(_repoUrl.value) ?: return

        viewModelScope.launch {
            _isLoading.value = true
            
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
                Log.e("StartLogin", "Login failed: ${result.exceptionOrNull()?.message}")
                // In a real app, we would show an error message here.
                // For now, if we fail to sync, we assume credentials *might* be wrong or network is down.
                // But specifically for Login, we probably want to block success if we can't verify.
                // However, offline login? Not possible for first run.
            }

            _isLoading.value = false
        }
    }
}
