package me.rerere.rikkahub.data.ai.account

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import me.rerere.ai.provider.OAuthCredential
import me.rerere.common.http.await
import me.rerere.oauth.OAuthHttpClient
import me.rerere.rikkahub.utils.JsonInstant
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.Locale

internal object GitHubCopilotAuth {
    // VS Code GitHub Copilot Chat 插件的官方 GitHub App Client ID（copilot-api 同款）
    const val CLIENT_ID = "Iv1.b507a08c87ecfe98"
    const val SCOPE = "read:user"

    const val DEVICE_CODE_ENDPOINT = "https://github.com/login/device/code"
    const val TOKEN_ENDPOINT = "https://github.com/login/oauth/access_token"
    const val COPILOT_TOKEN_ENDPOINT = "https://api.github.com/copilot_internal/v2/token"
    const val COPILOT_USER_ENDPOINT = "https://api.github.com/copilot_internal/user"
    const val GITHUB_USER_ENDPOINT = "https://api.github.com/user"

    private const val GITHUB_API_VERSION = "2025-04-01"
    private const val EDITOR_VERSION = "vscode/1.106.0"
    private const val EDITOR_PLUGIN_VERSION = "copilot-chat/0.26.7"
    private const val USER_AGENT = "GitHubCopilotChat/0.26.7"
    private const val DEFAULT_SESSION_TTL_MILLIS = 25 * 60 * 1000L

    data class CopilotSessionToken(
        val token: String,
        val expiresAtMillis: Long,
        val apiBaseUrl: String?,
        val sku: String?,
    )

    data class CopilotUserProfile(
        val login: String?,
        val email: String?,
        val copilotPlan: String?,
    )

    fun deviceAuthorizationRequest() = OAuthHttpClient.DeviceAuthorizationRequest(
        deviceAuthorizationEndpoint = DEVICE_CODE_ENDPOINT,
        clientId = CLIENT_ID,
        scope = SCOPE,
    )

    fun deviceTokenRequest(deviceCode: String) = OAuthHttpClient.DeviceTokenRequest(
        tokenEndpoint = TOKEN_ENDPOINT,
        clientId = CLIENT_ID,
        deviceCode = deviceCode,
    )

    /**
     * 使用长期 GitHub OAuth token 向 `copilot_internal/v2/token` 换取短期 Copilot 会话 token（约 30 分钟有效）。
     */
    suspend fun exchangeCopilotToken(
        client: OkHttpClient,
        githubOAuthToken: String,
        endpoint: String = COPILOT_TOKEN_ENDPOINT,
    ): CopilotSessionToken = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(endpoint)
            .applyGitHubHeaders(githubOAuthToken)
            .get()
            .build()

        client.newCall(request).await().use { response ->
            val body = response.body.string()
            if (!response.isSuccessful) {
                if (response.code == 401 || response.code == 403 || response.code == 404) {
                    error("GitHub account does not have an active GitHub Copilot subscription (HTTP ${response.code})")
                }
                error("Failed to exchange GitHub Copilot token: HTTP ${response.code} ${body.take(200)}")
            }
            parseCopilotTokenResponse(body)
        }
    }

    internal fun parseCopilotTokenResponse(
        body: String,
        nowMillis: Long = System.currentTimeMillis(),
    ): CopilotSessionToken {
        val obj = JsonInstant.parseToJsonElement(body).jsonObject
        val token = obj["token"]?.jsonPrimitive?.contentOrNull?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: error("GitHub Copilot token is missing in response")
        val rawExpiresAt = obj["expires_at"]?.jsonPrimitive?.longOrNull ?: 0L
        val expiresAtMillis = when {
            rawExpiresAt > 100_000_000_000L -> rawExpiresAt
            rawExpiresAt > 0L -> rawExpiresAt * 1000L
            else -> nowMillis + DEFAULT_SESSION_TTL_MILLIS
        }
        val apiBaseUrl = obj["endpoints"]?.jsonObject
            ?.get("api")?.jsonPrimitive?.contentOrNull?.trim()?.trimEnd('/')
            ?.takeIf { it.isNotEmpty() }
        val sku = obj["sku"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
        return CopilotSessionToken(
            token = token,
            expiresAtMillis = expiresAtMillis,
            apiBaseUrl = apiBaseUrl,
            sku = sku,
        )
    }

    /**
     * 查询 GitHub 账号与 Copilot 订阅类型，仅用于界面展示，失败不阻断登录或刷新。
     */
    suspend fun fetchUserProfile(
        client: OkHttpClient,
        githubOAuthToken: String,
        userEndpoint: String = GITHUB_USER_ENDPOINT,
        copilotUserEndpoint: String = COPILOT_USER_ENDPOINT,
    ): CopilotUserProfile = withContext(Dispatchers.IO) {
        val (login, email) = runCatching {
            val req = Request.Builder()
                .url(userEndpoint)
                .applyGitHubHeaders(githubOAuthToken)
                .get()
                .build()
            client.newCall(req).await().use { resp ->
                if (!resp.isSuccessful) return@use null to null
                val json = JsonInstant.parseToJsonElement(resp.body.string()).jsonObject
                val l = json["login"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
                val e = json["email"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
                l to e
            }
        }.getOrDefault(null to null)

        val copilotPlan = runCatching {
            val req = Request.Builder()
                .url(copilotUserEndpoint)
                .applyGitHubHeaders(githubOAuthToken)
                .get()
                .build()
            client.newCall(req).await().use { resp ->
                if (!resp.isSuccessful) return@use null
                val json = JsonInstant.parseToJsonElement(resp.body.string()).jsonObject
                json["copilot_plan"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
                    ?: json["access_type_sku"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
            }
        }.getOrNull()

        CopilotUserProfile(
            login = login,
            email = email,
            copilotPlan = copilotPlan,
        )
    }

    fun toCredential(
        githubOAuthToken: String,
        sessionToken: CopilotSessionToken,
        userProfile: CopilotUserProfile? = null,
        previous: OAuthCredential? = null,
    ): OAuthCredential {
        val displayIdentity = userProfile?.email?.takeIf { it.isNotBlank() }
            ?: userProfile?.login?.takeIf { it.isNotBlank() }
            ?: previous?.email
        val accountId = userProfile?.login?.takeIf { it.isNotBlank() }
            ?: previous?.accountId
            ?: displayIdentity
        val rawPlan = userProfile?.copilotPlan?.takeIf { it.isNotBlank() }
            ?: sessionToken.sku?.takeIf { it.isNotBlank() }
        val plan = planDisplayName(rawPlan) ?: previous?.plan
        return OAuthCredential(
            accessToken = sessionToken.token,
            refreshToken = githubOAuthToken,
            expiresAt = sessionToken.expiresAtMillis,
            accountId = accountId,
            apiBaseUrl = sessionToken.apiBaseUrl ?: previous?.apiBaseUrl,
            email = displayIdentity,
            plan = plan,
        )
    }

    internal fun planDisplayName(rawPlan: String?): String? {
        val normalized = rawPlan?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() } ?: return null
        return when {
            normalized == "individual_pro" || normalized.contains("pro_plus") -> "Copilot Pro+"
            normalized == "individual" || normalized.contains("pro") -> "Copilot Pro"
            normalized.contains("enterprise") -> "Copilot Enterprise"
            normalized.contains("business") -> "Copilot Business"
            normalized.contains("free") -> "Copilot Free"
            else -> rawPlan.trim().replace('_', ' ')
                .replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }
        }
    }

    private fun Request.Builder.applyGitHubHeaders(githubOAuthToken: String): Request.Builder =
        header("Authorization", "token $githubOAuthToken")
            .header("Accept", "application/json")
            .header("Editor-Version", EDITOR_VERSION)
            .header("Editor-Plugin-Version", EDITOR_PLUGIN_VERSION)
            .header("User-Agent", USER_AGENT)
            .header("X-GitHub-Api-Version", GITHUB_API_VERSION)
}
