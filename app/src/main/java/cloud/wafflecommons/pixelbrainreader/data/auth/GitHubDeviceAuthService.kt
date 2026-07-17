package cloud.wafflecommons.pixelbrainreader.data.auth

import com.google.gson.annotations.SerializedName
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.Headers
import retrofit2.http.POST

/**
 * GitHub OAuth **Device Flow** endpoints (base url https://github.com/).
 *
 * Device Flow is the right fit for a backend-less native app: it needs only a PUBLIC
 * client id (no client secret), and the user approves in whatever browser they are already
 * signed into. The resulting access token is used exactly like a PAT — handed to JGit's
 * UsernamePasswordCredentialsProvider.
 */
interface GitHubDeviceAuthService {

    @FormUrlEncoded
    @Headers("Accept: application/json")
    @POST("login/device/code")
    suspend fun requestDeviceCode(
        @Field("client_id") clientId: String,
        @Field("scope") scope: String
    ): DeviceCodeResponse

    @FormUrlEncoded
    @Headers("Accept: application/json")
    @POST("login/oauth/access_token")
    suspend fun requestAccessToken(
        @Field("client_id") clientId: String,
        @Field("device_code") deviceCode: String,
        @Field("grant_type") grantType: String = "urn:ietf:params:oauth:grant-type:device_code"
    ): AccessTokenResponse
}

data class DeviceCodeResponse(
    @SerializedName("device_code") val deviceCode: String = "",
    @SerializedName("user_code") val userCode: String = "",
    @SerializedName("verification_uri") val verificationUri: String = "",
    @SerializedName("expires_in") val expiresIn: Int = 900,
    @SerializedName("interval") val interval: Int = 5
)

data class AccessTokenResponse(
    @SerializedName("access_token") val accessToken: String? = null,
    @SerializedName("token_type") val tokenType: String? = null,
    @SerializedName("scope") val scope: String? = null,
    // Present instead of access_token while pending / on error:
    // authorization_pending | slow_down | expired_token | access_denied | unsupported_grant_type
    @SerializedName("error") val error: String? = null,
    @SerializedName("error_description") val errorDescription: String? = null,
    @SerializedName("interval") val interval: Int? = null
)
