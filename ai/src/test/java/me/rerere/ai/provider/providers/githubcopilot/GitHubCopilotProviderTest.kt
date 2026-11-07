package me.rerere.ai.provider.providers.githubcopilot

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.AccountCredentialProvider
import me.rerere.ai.provider.CustomHeader
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.OAuthCredential
import me.rerere.ai.provider.ProviderQuota
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.providers.openai.ChatCompletionsStreamDecoder
import me.rerere.ai.provider.stream.SseEvent
import me.rerere.ai.provider.supportsBuiltInSearch
import me.rerere.ai.provider.supportsQuota
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.util.json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Instant

class GitHubCopilotProviderTest {
    private fun fakeClient(
        contentType: String = "application/json",
        responseBody: String,
        recordedRequests: MutableList<Request>,
    ): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor { chain ->
            val req = chain.request()
            recordedRequests += req
            Response.Builder()
                .request(req)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .header("Content-Type", contentType)
                .body(responseBody.toResponseBody(contentType.toMediaType()))
                .build()
        }
        .build()

    @Test
    fun `listModels filters chat models and records supported endpoints`() = runTest {
        val requests = mutableListOf<Request>()
        val client = fakeClient(
            recordedRequests = requests,
            responseBody = """
                {
                  "data": [
                    {
                      "id": "gpt-4.1",
                      "name": "GPT-4.1",
                      "vendor": "OpenAI",
                      "model_picker_enabled": true,
                      "is_chat_default": true,
                      "supported_endpoints": ["/chat/completions", "/responses"],
                      "capabilities": {
                        "type": "chat",
                        "limits": { "max_context_window_tokens": 128000 },
                        "supports": { "vision": true, "tool_calls": true }
                      }
                    },
                    {
                      "id": "gpt-5.2-codex",
                      "name": "GPT-5.2 Codex",
                      "vendor": "OpenAI",
                      "model_picker_enabled": true,
                      "supported_endpoints": ["/responses"],
                      "capabilities": {
                        "type": "chat",
                        "limits": { "max_context_window_tokens": 200000 },
                        "supports": { "vision": false, "tool_calls": true, "max_thinking_budget": 16000 }
                      },
                      "policy": { "state": "enabled" }
                    },
                    {
                      "id": "text-embedding-3-small",
                      "name": "Embedding",
                      "model_picker_enabled": true,
                      "capabilities": { "type": "embeddings" }
                    },
                    {
                      "id": "disabled-model",
                      "name": "Disabled",
                      "model_picker_enabled": true,
                      "capabilities": { "type": "chat" },
                      "policy": { "state": "disabled" }
                    }
                  ]
                }
            """.trimIndent(),
        )

        val credentials = FakeCredentialProvider(
            OAuthCredential(
                accessToken = "copilot-session-token",
                refreshToken = "ghu_oauth_token",
                apiBaseUrl = "https://api.business.githubcopilot.com/",
            )
        )
        val provider = GitHubCopilotProvider(
            client = client,
            credentials = credentials,
        )

        val models = provider.listModels(ProviderSetting.GitHubCopilot(credential = credentials.credential))

        val recorded = requests.single()
        assertEquals("GET", recorded.method)
        assertEquals("https://api.business.githubcopilot.com/models", recorded.url.toString())
        assertEquals("Bearer copilot-session-token", recorded.header("Authorization"))
        assertEquals("vscode-chat", recorded.header("Copilot-Integration-Id"))
        assertEquals("2025-10-01", recorded.header("X-GitHub-Api-Version"))

        assertEquals(2, models.size)
        assertEquals("gpt-4.1", models[0].modelId)
        assertEquals(listOf(Modality.TEXT, Modality.IMAGE), models[0].inputModalities)
        assertEquals(128000, models[0].contextLength)
        assertFalse(provider.shouldUseResponsesEndpoint("gpt-4.1"))

        assertEquals("gpt-5.2-codex", models[1].modelId)
        assertTrue(models[1].abilities.contains(ModelAbility.REASONING))
        assertTrue(provider.shouldUseResponsesEndpoint("gpt-5.2-codex"))
    }

    @Test
    fun `chat completions endpoint configures initiator, vision, and overrides custom auth header`() = runTest {
        val credentials = FakeCredentialProvider(
            OAuthCredential(
                accessToken = "copilot-token",
                apiBaseUrl = "https://api.individual.githubcopilot.com",
            )
        )
        val provider = GitHubCopilotProvider(
            client = OkHttpClient(),
            credentials = credentials,
        )
        val setting = ProviderSetting.GitHubCopilot(
            customHeaders = listOf(CustomHeader("Authorization", "Bearer evil")),
        )
        val messages = listOf(
            UIMessage(
                role = MessageRole.USER,
                parts = listOf(
                    UIMessagePart.Text("Describe image"),
                    UIMessagePart.Image("data:image/png;base64,AAAA"),
                ),
            )
        )
        val params = TextGenerationParams(
            model = Model(
                modelId = "gpt-4.1",
                displayName = "GPT-4.1",
                inputModalities = listOf(Modality.TEXT, Modality.IMAGE),
            ),
        )

        val endpoint = with(provider) { setting.toChatCompletionsEndpoint(messages, params) }
        val request = Request.Builder()
            .url(endpoint.url)
            .apply { endpoint.configureRequest(this) }
            .build()

        assertEquals("https://api.individual.githubcopilot.com/chat/completions", request.url.toString())
        assertEquals("Bearer copilot-token", request.header("Authorization"))
        assertEquals("user", request.header("X-Initiator"))
        assertEquals("true", request.header("Copilot-Vision-Request"))
        assertEquals("conversation-panel", request.header("Openai-Intent"))

        // Also verify ChatCompletionsStreamDecoder parses Copilot's reasoning_text field
        val decoder = ChatCompletionsStreamDecoder()
        val chunks = decoder.accept(
            SseEvent(
                data = """{"id":"chatcmpl-1","model":"gpt-4.1","choices":[{"index":0,"delta":{"reasoning_text":"thinking step","content":"Hello"}}]}"""
            )
        ).chunks
        assertEquals(
            "thinking step",
            chunks.filterIsInstance<StreamChunk.ReasoningDelta>().joinToString("") { it.text },
        )
        assertEquals(
            "Hello",
            chunks.filterIsInstance<StreamChunk.TextDelta>().joinToString("") { it.text },
        )
    }

    @Test
    fun `responses endpoint routes codex model and marks agent initiator on assistant turn`() = runTest {
        val credentials = FakeCredentialProvider(
            OAuthCredential(
                accessToken = "copilot-token",
                apiBaseUrl = "https://api.enterprise.githubcopilot.com/",
            )
        )
        val provider = GitHubCopilotProvider(
            client = OkHttpClient(),
            credentials = credentials,
        )
        provider.cacheModelEndpoints("gpt-5.2-codex", listOf("/responses"))
        assertTrue(provider.shouldUseResponsesEndpoint("gpt-5.2-codex"))

        val setting = ProviderSetting.GitHubCopilot(credential = credentials.credential)
        val messages = listOf(
            UIMessage.user("Run tool"),
            UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(UIMessagePart.Text("Calling tool")),
            ),
        )
        val params = TextGenerationParams(
            model = Model(modelId = "gpt-5.2-codex", displayName = "GPT-5.2 Codex"),
        )

        val endpoint = with(provider) { setting.toResponsesEndpoint(messages, params) }
        val request = Request.Builder()
            .url(endpoint.url)
            .apply { endpoint.configureRequest(this) }
            .build()

        assertEquals("https://api.enterprise.githubcopilot.com/responses", request.url.toString())
        assertEquals("agent", request.header("X-Initiator"))
        assertEquals(null, request.header("Copilot-Vision-Request"))
    }

    @Test
    fun `getQuota uses github oauth refreshToken and parses premium_interactions`() = runTest {
        val requests = mutableListOf<Request>()
        val client = fakeClient(
            recordedRequests = requests,
            responseBody = """
                {
                  "copilot_plan": "individual_pro",
                  "quota_reset_date": "2026-05-01",
                  "quota_snapshots": {
                    "premium_interactions": {
                      "entitlement": 300,
                      "remaining": 225,
                      "percent_remaining": 75.0,
                      "unlimited": false
                    }
                  }
                }
            """.trimIndent(),
        )

        val credentials = FakeCredentialProvider(
            OAuthCredential(
                accessToken = "short-copilot-session-token",
                refreshToken = "ghu_long_lived_oauth_token",
            )
        )
        val provider = GitHubCopilotProvider(
            client = client,
            credentials = credentials,
        )

        val quota = provider.getQuota(ProviderSetting.GitHubCopilot()) as ProviderQuota.UsageLimits

        val recorded = requests.single()
        assertEquals("/copilot_internal/user", recorded.url.encodedPath)
        assertEquals("token ghu_long_lived_oauth_token", recorded.header("Authorization"))
        assertEquals("2025-04-01", recorded.header("X-GitHub-Api-Version"))

        assertEquals(1, quota.windows.size)
        assertEquals(25.0, quota.windows[0].usedPercent, 0.001)
        assertEquals(Instant.parse("2026-05-01T00:00:00Z"), quota.windows[0].resetsAt)
        assertFalse(quota.limitReached)
    }

    @Test
    fun `resolveCopilotApiBaseUrl accepts githubcopilot hosts and rejects untrusted hosts`() {
        assertEquals(
            "https://api.enterprise.githubcopilot.com",
            resolveCopilotApiBaseUrl("https://api.enterprise.githubcopilot.com/"),
        )
        assertEquals(
            DEFAULT_COPILOT_API_BASE_URL,
            resolveCopilotApiBaseUrl("https://evil.example.com"),
        )
        assertEquals(
            DEFAULT_COPILOT_API_BASE_URL,
            resolveCopilotApiBaseUrl("http://api.individual.githubcopilot.com"),
        )
    }

    @Test
    fun `github copilot provider setting disables built-in search and serializes cleanly`() {
        val setting = ProviderSetting.GitHubCopilot(
            credential = OAuthCredential(
                accessToken = "tid=1",
                refreshToken = "ghu_1",
                accountId = "octocat",
                apiBaseUrl = "https://api.individual.githubcopilot.com",
            )
        )
        assertFalse(setting.supportsBuiltInSearch)
        assertTrue(setting.supportsQuota)

        val encoded = json.encodeToString(ProviderSetting.serializer(), setting)
        val decoded = json.decodeFromString(ProviderSetting.serializer(), encoded)
        assertTrue(encoded.contains("\"type\":\"github_copilot\""))
        assertEquals(setting, decoded)
    }

    @Test
    fun `parseCopilotUsage marks limitReached when remaining is zero without overage`() {
        val body = json.parseToJsonElement(
            """
            {
              "quota_reset_date_utc": "2026-05-01T00:00:00Z",
              "quota_snapshots": {
                "premium_interactions": {
                  "entitlement": 50,
                  "remaining": 0,
                  "percent_remaining": 0.0,
                  "unlimited": false,
                  "overage_permitted": false
                }
              }
            }
            """.trimIndent()
        ).jsonObject

        val usage = parseCopilotUsage(body)
        assertTrue(usage.limitReached)
        assertEquals(100.0, usage.windows.single().usedPercent, 0.001)
    }

    private fun sseResponseCompleted(): String =
        "event: response.completed\n" +
            "data: {\"type\":\"response.completed\",\"response\":{\"id\":\"resp_1\",\"model\":\"gpt-5.2-codex\",\"status\":\"completed\",\"output\":[]}}\n\n"

    private class FakeCredentialProvider(
        val credential: OAuthCredential,
    ) : AccountCredentialProvider {
        override suspend fun getCredential(
            setting: ProviderSetting.Account,
            rejectedAccessToken: String?,
        ): OAuthCredential = credential
    }
}
