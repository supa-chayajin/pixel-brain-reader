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

    private fun validateRepoUrl(url: String): Pair<String, String>? {
        val regex = Regex("github\\.com/([^/]+)/([^/.]+)")
        val match = regex.find(url) ?: return null
        val (owner, repo) = match.destructured
        return Pair(owner, repo)
    }

    fun onConnectClick() {
        if (!_isTokenValid.value) return

        val repoInfo = validateRepoUrl(_repoUrl.value) ?: return

        viewModelScope.launch {
            _isLoading.value = true
            
            // 1. Secure Storage (Vault)
            secretManager.saveToken(_token.value)
            secretManager.saveRepoInfo(repoInfo.first, repoInfo.second)
            
            // 2. Verification & Priming
            // We force a refresh of the root folder to verify credentials/network
            val result = repository.refreshFolder(repoInfo.first, repoInfo.second, "")
            
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
