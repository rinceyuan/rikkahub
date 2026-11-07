package me.rerere.ai.provider.providers.githubcopilot

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.AccountCredentialProvider
import me.rerere.ai.provider.CustomHeader
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderQuota
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.TextGenerationResult
import me.rerere.ai.provider.UsageWindow
import me.rerere.ai.provider.providers.configureAccountAuth
import me.rerere.ai.provider.providers.openai.ChatCompletionsAPI
import me.rerere.ai.provider.providers.openai.ChatCompletionsEndpoint
import me.rerere.ai.provider.providers.openai.ResponseAPI
import me.rerere.ai.provider.providers.openai.ResponsesEndpoint
import me.rerere.ai.provider.providers.withAccountAuthenticator
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.util.fetchBody
import me.rerere.ai.util.json
import me.rerere.ai.util.mergeCustomHeaders
import me.rerere.common.http.jsonPrimitiveOrNull
import me.rerere.common.http.stringOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Instant

internal const val DEFAULT_COPILOT_API_BASE_URL = "https://api.individual.githubcopilot.com"
internal const val DEFAULT_GITHUB_COPILOT_USER_URL = "https://api.github.com/copilot_internal/user"

internal const val COPILOT_API_VERSION = "2025-10-01"
internal const val GITHUB_API_VERSION = "2025-04-01"
internal const val EDITOR_VERSION = "vscode/1.106.0"
internal const val EDITOR_PLUGIN_VERSION = "copilot-chat/0.26.7"
internal const val USER_AGENT = "GitHubCopilotChat/0.26.7"
internal const val INTEGRATION_ID = "vscode-chat"
internal const val OPENAI_INTENT = "conversation-panel"

internal const val ENDPOINT_CHAT_COMPLETIONS = "/chat/completions"
internal const val ENDPOINT_RESPONSES = "/responses"

class GitHubCopilotProvider(
    client: OkHttpClient,
    private val credentials: AccountCredentialProvider,
    private val baseUrl: String = DEFAULT_COPILOT_API_BASE_URL,
    private val userEndpointUrl: String = DEFAULT_GITHUB_COPILOT_USER_URL,
) : Provider<ProviderSetting.GitHubCopilot> {
    private val client = client.withAccountAuthenticator(credentials)
    private val chatCompletionsAPI = ChatCompletionsAPI(this.client)
    private val responseAPI = ResponseAPI(this.client)
    private val modelEndpoints = ConcurrentHashMap<String, List<String>>()

    override suspend fun listModels(providerSetting: ProviderSetting.GitHubCopilot): List<Model> {
        val initialBase = resolveCopilotApiBaseUrl(providerSetting.credential?.apiBaseUrl, baseUrl)
        val request = Request.Builder()
            .url("$initialBase/models")
            .apply {
                configureCopilotRequest(
                    providerSetting = providerSetting,
                    path = "/models",
                    customHeaders = emptyList(),
                    messages = null,
                )
            }
            .get()
            .build()

        val body = client.fetchBody(request)
        val root = json.parseToJsonElement(body).jsonObject
        return parseCopilotModels(root, modelEndpoints)
    }

    override suspend fun getQuota(providerSetting: ProviderSetting.GitHubCopilot): ProviderQuota {
        val credential = credentials.getCredential(providerSetting, rejectedAccessToken = null)
        // copilot_internal/user 需要使用长期 GitHub OAuth token（存放在 refreshToken）
        val githubToken = credential.refreshToken?.takeIf { it.isNotBlank() } ?: credential.accessToken
        val request = Request.Builder()
            .url(userEndpointUrl)
            .headers(providerSetting.mergeCustomHeaders(emptyList()))
            .header("Authorization", "token $githubToken")
            .header("Accept", "application/json")
            .header("Editor-Version", EDITOR_VERSION)
            .header("Editor-Plugin-Version", EDITOR_PLUGIN_VERSION)
            .header("User-Agent", USER_AGENT)
            .header("X-GitHub-Api-Version", GITHUB_API_VERSION)
            .get()
            .build()

        val body = client.fetchBody(request)
        return parseCopilotUsage(json.parseToJsonElement(body).jsonObject)
    }

    override suspend fun generateText(
        providerSetting: ProviderSetting.GitHubCopilot,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): TextGenerationResult = if (shouldUseResponsesEndpoint(params.model.modelId)) {
        responseAPI.generateText(
            endpoint = providerSetting.toResponsesEndpoint(messages, params),
            messages = messages,
            params = params,
        )
    } else {
        chatCompletionsAPI.generateText(
            endpoint = providerSetting.toChatCompletionsEndpoint(messages, params),
            messages = messages,
            params = params,
        )
    }

    override suspend fun streamText(
        providerSetting: ProviderSetting.GitHubCopilot,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): Flow<StreamChunk> = if (shouldUseResponsesEndpoint(params.model.modelId)) {
        responseAPI.streamText(
            endpoint = providerSetting.toResponsesEndpoint(messages, params),
            messages = messages,
            params = params,
        )
    } else {
        chatCompletionsAPI.streamText(
            endpoint = providerSetting.toChatCompletionsEndpoint(messages, params),
            messages = messages,
            params = params,
        )
    }

    internal fun cacheModelEndpoints(modelId: String, endpoints: List<String>) {
        modelEndpoints[modelId] = endpoints
    }

    internal fun shouldUseResponsesEndpoint(modelId: String): Boolean {
        val endpoints = modelEndpoints[modelId]
        if (!endpoints.isNullOrEmpty()) {
            return prefersResponsesEndpoint(endpoints)
        }
        return modelId.contains("codex", ignoreCase = true)
    }

    internal fun ProviderSetting.GitHubCopilot.toChatCompletionsEndpoint(
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): ChatCompletionsEndpoint {
        val resolvedBase = resolveCopilotApiBaseUrl(credential?.apiBaseUrl, baseUrl)
        return ChatCompletionsEndpoint(
            url = "$resolvedBase$ENDPOINT_CHAT_COMPLETIONS",
            baseUrl = resolvedBase,
            includeHistoryReasoning = true,
            configureRequest = {
                configureCopilotRequest(
                    providerSetting = this@toChatCompletionsEndpoint,
                    path = ENDPOINT_CHAT_COMPLETIONS,
                    customHeaders = params.customHeaders,
                    messages = messages,
                )
            },
        )
    }

    internal fun ProviderSetting.GitHubCopilot.toResponsesEndpoint(
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): ResponsesEndpoint {
        val resolvedBase = resolveCopilotApiBaseUrl(credential?.apiBaseUrl, baseUrl)
        return ResponsesEndpoint(
            url = "$resolvedBase$ENDPOINT_RESPONSES",
            configureRequest = {
                configureCopilotRequest(
                    providerSetting = this@toResponsesEndpoint,
                    path = ENDPOINT_RESPONSES,
                    customHeaders = params.customHeaders,
                    messages = messages,
                )
            },
        )
    }

    private suspend fun Request.Builder.configureCopilotRequest(
        providerSetting: ProviderSetting.GitHubCopilot,
        path: String,
        customHeaders: List<CustomHeader>,
        messages: List<UIMessage>?,
    ) {
        configureAccountAuth(
            credentials = credentials,
            setting = providerSetting,
            customHeaders = customHeaders,
        ) { credential ->
            url("${resolveCopilotApiBaseUrl(credential.apiBaseUrl, baseUrl)}$path")
            header("Copilot-Integration-Id", INTEGRATION_ID)
            header("Editor-Version", EDITOR_VERSION)
            header("Editor-Plugin-Version", EDITOR_PLUGIN_VERSION)
            header("User-Agent", USER_AGENT)
            header("Openai-Intent", OPENAI_INTENT)
            header("X-GitHub-Api-Version", COPILOT_API_VERSION)
            header("X-Request-Id", UUID.randomUUID().toString())
            if (messages != null) {
                header("X-Initiator", resolveInitiator(messages))
                if (containsVisionInput(messages)) {
                    header("Copilot-Vision-Request", "true")
                }
            }
        }
    }
}

/**
 * 校验并规范化 Copilot API 基址：仅接受官方 `*.githubcopilot.com` / `api.githubcopilot.com` 的 HTTPS 地址，
 * 避免凭据被篡改时把 Bearer token 发往不可信主机。
 */
internal fun resolveCopilotApiBaseUrl(
    rawUrl: String?,
    fallbackBaseUrl: String = DEFAULT_COPILOT_API_BASE_URL,
): String {
    val trimmed = rawUrl?.trim()?.trimEnd('/')
    if (!trimmed.isNullOrEmpty()) {
        val parsed = trimmed.toHttpUrlOrNull()
        val host = parsed?.host?.lowercase(Locale.ROOT)
        if (parsed != null && parsed.scheme == "https" &&
            (host == "api.githubcopilot.com" || (host != null && host.endsWith(".githubcopilot.com")))
        ) {
            return trimmed
        }
    }
    return fallbackBaseUrl.trimEnd('/')
}

internal fun prefersResponsesEndpoint(supportedEndpoints: List<String>): Boolean {
    if (supportedEndpoints.isEmpty()) return false
    val normalized = supportedEndpoints.map { it.trim().lowercase(Locale.ROOT) }.toSet()
    if (ENDPOINT_CHAT_COMPLETIONS in normalized) return false
    return ENDPOINT_RESPONSES in normalized
}

internal fun resolveInitiator(messages: List<UIMessage>): String {
    val last = messages.lastOrNull() ?: return "user"
    val isUserInitiated = last.role == MessageRole.USER &&
        !last.isSynthetic &&
        last.parts.none { it is UIMessagePart.Tool }
    return if (isUserInitiated) "user" else "agent"
}

internal fun containsVisionInput(messages: List<UIMessage>): Boolean =
    messages.any { message ->
        message.parts.any { part ->
            part is UIMessagePart.Image ||
                (part is UIMessagePart.Tool && part.output.any { it is UIMessagePart.Image })
        }
    }

internal fun parseCopilotModels(
    root: JsonObject,
    endpointSink: MutableMap<String, List<String>>? = null,
): List<Model> {
    val dataArray = root["data"] as? JsonArray ?: return emptyList()
    val seenIds = LinkedHashSet<String>()
    val entries = mutableListOf<Pair<Boolean, Model>>()

    for (element in dataArray) {
        val obj = element as? JsonObject ?: continue
        val id = obj.stringOrNull("id")?.trim().orEmpty()
        if (id.isEmpty() || !seenIds.add(id)) continue

        val capabilities = obj["capabilities"] as? JsonObject
        val type = capabilities?.stringOrNull("type")?.trim()?.lowercase(Locale.ROOT)
        if (type != null && type != "chat") continue

        val pickerEnabled = obj["model_picker_enabled"]?.jsonPrimitiveOrNull?.booleanOrNull ?: true
        if (!pickerEnabled) continue

        val policyState = (obj["policy"] as? JsonObject)
            ?.stringOrNull("state")?.trim()?.lowercase(Locale.ROOT)
        if (policyState != null && policyState != "enabled") continue

        val supportedEndpoints = (obj["supported_endpoints"] as? JsonArray)
            ?.mapNotNull { it.jsonPrimitiveOrNull?.contentOrNull?.trim() }
            ?.filter { it.isNotEmpty() }
            .orEmpty()
        if (supportedEndpoints.isNotEmpty()) {
            endpointSink?.put(id, supportedEndpoints)
        }

        val name = obj.stringOrNull("name")?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: id
        val vendor = obj.stringOrNull("vendor")?.trim()
        val displayName = if (!vendor.isNullOrEmpty() && !name.startsWith(vendor, ignoreCase = true)) {
            "$name ($vendor)"
        } else {
            name
        }

        val supports = capabilities?.get("supports") as? JsonObject
        val vision = supports?.get("vision")?.jsonPrimitiveOrNull?.booleanOrNull ?: false
        val toolCalls = supports?.get("tool_calls")?.jsonPrimitiveOrNull?.booleanOrNull ?: true
        val thinking = (supports?.get("max_thinking_budget")?.jsonPrimitiveOrNull?.intOrNull ?: 0) > 0 ||
            (supports?.get("thinking")?.jsonPrimitiveOrNull?.booleanOrNull == true)

        val limits = capabilities?.get("limits") as? JsonObject
        val contextWindow = limits?.get("max_context_window_tokens")?.jsonPrimitiveOrNull?.intOrNull ?: 0

        val isChatDefault = obj["is_chat_default"]?.jsonPrimitiveOrNull?.booleanOrNull ?: false

        val model = Model(
            modelId = id,
            displayName = displayName,
            type = ModelType.CHAT,
            inputModalities = if (vision) listOf(Modality.TEXT, Modality.IMAGE) else listOf(Modality.TEXT),
            outputModalities = listOf(Modality.TEXT),
            abilities = buildList {
                if (toolCalls) add(ModelAbility.TOOL)
                if (thinking) add(ModelAbility.REASONING)
            },
            contextLength = contextWindow,
        )
        entries += isChatDefault to model
    }

    return entries
        .sortedWith(compareByDescending<Pair<Boolean, Model>> { it.first })
        .map { it.second }
}

internal fun parseCopilotUsage(body: JsonObject): ProviderQuota.UsageLimits {
    val resetsAt = parseResetInstant(
        body.stringOrNull("quota_reset_date_utc") ?: body.stringOrNull("quota_reset_date")
    )
    val snapshots = body["quota_snapshots"] as? JsonObject
    val windows = mutableListOf<UsageWindow>()
    var limitReached = false

    val primary = (snapshots?.get("premium_interactions") as? JsonObject)
        ?: (snapshots?.get("chat") as? JsonObject)

    if (primary != null) {
        val unlimited = primary["unlimited"]?.jsonPrimitiveOrNull?.booleanOrNull ?: false
        if (unlimited) {
            windows += UsageWindow(
                usedPercent = 0.0,
                windowMinutes = null,
                resetsAt = resetsAt,
            )
        } else {
            val remainingPercent = primary["percent_remaining"]?.jsonPrimitiveOrNull?.doubleOrNull
                ?: run {
                    val entitlement = primary["entitlement"]?.jsonPrimitiveOrNull?.doubleOrNull ?: 0.0
                    val remaining = primary["remaining"]?.jsonPrimitiveOrNull?.doubleOrNull ?: 0.0
                    if (entitlement > 0.0) (remaining / entitlement) * 100.0 else 100.0
                }
            val usedPercent = (100.0 - remainingPercent).coerceIn(0.0, 100.0)
            val overagePermitted = primary["overage_permitted"]?.jsonPrimitiveOrNull?.booleanOrNull ?: false
            val remaining = primary["remaining"]?.jsonPrimitiveOrNull?.doubleOrNull
            if (!overagePermitted && ((remaining != null && remaining <= 0.0) || usedPercent >= 100.0)) {
                limitReached = true
            }
            windows += UsageWindow(
                usedPercent = usedPercent,
                windowMinutes = null,
                resetsAt = resetsAt,
            )
        }
    }

    return ProviderQuota.UsageLimits(
        windows = windows,
        limitReached = limitReached,
    )
}

private fun parseResetInstant(raw: String?): Instant? {
    val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return runCatching { Instant.parse(value) }.getOrElse {
        runCatching { Instant.parse("${value}T00:00:00Z") }.getOrNull()
    }
}
