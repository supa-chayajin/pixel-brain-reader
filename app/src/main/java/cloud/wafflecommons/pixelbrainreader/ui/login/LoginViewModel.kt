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

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _isTokenValid = MutableStateFlow(false)
    val isTokenValid: StateFlow<Boolean> = _isTokenValid.asStateFlow()

    private val _loginSuccess = MutableStateFlow(false)
    val loginSuccess: StateFlow<Boolean> = _loginSuccess.asStateFlow()

    fun onTokenChanged(newToken: String) {
        _token.value = newToken
        _isTokenValid.value = validateToken(newToken)
    }

    private fun validateToken(token: String): Boolean {
        return token.startsWith("ghp_") || token.startsWith("github_pat_")
    }

    fun onConnectClick() {
        if (!_isTokenValid.value) return

        viewModelScope.launch {
            _isLoading.value = true
            tokenManager.saveToken(_token.value)
            
            // Verification: Try to fetch contents
            testFetchContents()

            _isLoading.value = false
            _loginSuccess.value = true
        }
    }

    private suspend fun testFetchContents() {
        // Replace with your own repo for testing or a public one
        val result = repository.getContents("google", "guava", "")
        result.onSuccess { files ->
            Log.d("PixelBrainVerification", "Fetched ${files.size} files from google/guava")
            files.forEach { file ->
                Log.d("PixelBrainVerification", "File: ${file.name} (${file.type})")
            }
        }.onFailure { e ->
            Log.e("PixelBrainVerification", "Failed to fetch contents", e)
        }
    }
}
