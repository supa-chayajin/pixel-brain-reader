package cloud.wafflecommons.pixelbrainreader.ui.login

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cloud.wafflecommons.pixelbrainreader.data.local.TokenManager
import cloud.wafflecommons.pixelbrainreader.data.repository.GithubRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class LoginViewModel @Inject constructor(
    private val tokenManager: TokenManager,
    private val repository: GithubRepository
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
        return token.startsWith("ghp_") || token.startsWith("github_pat_")
    }

    private fun validateRepoUrl(url: String): Pair<String, String>? {
        // Regex to extract owner and repo from URLs like:
        // https://github.com/owner/repo
        // https://github.com/owner/repo.git
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
            tokenManager.saveToken(_token.value)
            tokenManager.saveRepoInfo(repoInfo.first, repoInfo.second)
            
            // Verification: Try to fetch contents
            testFetchContents(repoInfo.first, repoInfo.second)

            _isLoading.value = false
            _loginSuccess.value = true
        }
    }

    private suspend fun testFetchContents(owner: String, repo: String) {
        val result = repository.getContents(owner, repo, "")
        result.onSuccess { files ->
            Log.d("PixelBrainVerification", "Fetched ${files.size} files from $owner/$repo")
            files.forEach { file ->
                Log.d("PixelBrainVerification", "File: ${file.name} (${file.type})")
            }
        }.onFailure { e ->
            Log.e("PixelBrainVerification", "Failed to fetch contents", e)
        }
    }
}
