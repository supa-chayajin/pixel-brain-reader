package cloud.wafflecommons.pixelbrainreader.data.remote

import cloud.wafflecommons.pixelbrainreader.data.local.security.SecretManager
import okhttp3.Interceptor
import okhttp3.Response
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AuthInterceptor @Inject constructor(
    private val secretManager: SecretManager
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()
        val token = secretManager.getToken()

        // SecOps Check: Inject "Bearer" token from the Vault.
        val newRequest = if (!token.isNullOrEmpty()) {
            originalRequest.newBuilder()
                .addHeader("Authorization", "Bearer $token")
                .build()
        } else {
            originalRequest
        }

        val response = chain.proceed(newRequest)

        if (response.code == 401) {
            secretManager.clear()
            // TODO: Trigger global logout event (Navigation to Login)
        }

        return response
    }
}
