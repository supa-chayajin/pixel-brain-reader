package cloud.wafflecommons.pixelbrainreader.data.auth

import cloud.wafflecommons.pixelbrainreader.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Drives the GitHub OAuth Device Flow and yields an access token that the rest of the app
 * treats exactly like a PAT (stored via SecretManager, handed to JGit). No client secret,
 * no backend — see [GitHubDeviceAuthService].
 */
@Singleton
class GitHubDeviceAuthRepository @Inject constructor(
    private val service: GitHubDeviceAuthService
) {
    private val clientId: String = BuildConfig.GITHUB_OAUTH_CLIENT_ID

    /** True when a client id was baked in (local.properties). Gates the UI entry point. */
    val isConfigured: Boolean get() = clientId.isNotBlank()

    /** Step 1: obtain the user code + verification URL to show the user. */
    suspend fun requestDeviceCode(): Result<DeviceCodeResponse> = withContext(Dispatchers.IO) {
        if (!isConfigured) return@withContext Result.failure(IllegalStateException("GitHub OAuth client id not configured"))
        try {
            Result.success(service.requestDeviceCode(clientId, DEFAULT_SCOPE))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Step 2: poll for the access token until the user approves, the code expires, or the
     * request is denied. Honors the server's `interval` and `slow_down` back-pressure.
     */
    suspend fun pollForToken(
        deviceCode: String,
        intervalSeconds: Int,
        expiresInSeconds: Int
    ): Result<String> = withContext(Dispatchers.IO) {
        var interval = intervalSeconds.coerceAtLeast(MIN_INTERVAL_SECONDS)
        val deadline = System.currentTimeMillis() + expiresInSeconds.coerceAtMost(MAX_LIFETIME_SECONDS) * 1000L

        while (System.currentTimeMillis() < deadline) {
            delay(interval * 1000L)
            val response = try {
                service.requestAccessToken(clientId, deviceCode)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Transient network hiccup — keep polling until the deadline.
                continue
            }

            response.accessToken?.takeIf { it.isNotBlank() }?.let { return@withContext Result.success(it) }

            when (response.error) {
                "authorization_pending" -> { /* keep waiting */ }
                "slow_down" -> interval += (response.interval ?: SLOW_DOWN_BUMP_SECONDS)
                "expired_token" -> return@withContext Result.failure(IllegalStateException("The code expired — please try again."))
                "access_denied" -> return@withContext Result.failure(IllegalStateException("Authorization was denied."))
                else -> response.errorDescription?.let { return@withContext Result.failure(IllegalStateException(it)) }
            }
        }
        Result.failure(IllegalStateException("Timed out waiting for GitHub authorization."))
    }

    companion object {
        private const val DEFAULT_SCOPE = "repo"
        private const val MIN_INTERVAL_SECONDS = 5
        private const val SLOW_DOWN_BUMP_SECONDS = 5
        private const val MAX_LIFETIME_SECONDS = 900 // hard cap ~15 min regardless of server value
    }
}
