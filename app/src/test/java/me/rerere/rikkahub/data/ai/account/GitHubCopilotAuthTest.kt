package me.rerere.rikkahub.data.ai.account

import me.rerere.ai.provider.OAuthCredential
import org.junit.Assert.assertEquals
import org.junit.Test

class GitHubCopilotAuthTest {
    @Test
    fun `parseCopilotTokenResponse extracts token, expiry seconds, api endpoint, and sku`() {
        val parsed = GitHubCopilotAuth.parseCopilotTokenResponse(
            """
            {
              "token": "tid=123;exp=1760000000",
              "expires_at": 1760000000,
              "sku": "copilot_for_business",
              "endpoints": {
                "api": "https://api.business.githubcopilot.com/"
              }
            }
            """.trimIndent()
        )

        assertEquals("tid=123;exp=1760000000", parsed.token)
        assertEquals(1760000000_000L, parsed.expiresAtMillis)
        assertEquals("https://api.business.githubcopilot.com", parsed.apiBaseUrl)
        assertEquals("copilot_for_business", parsed.sku)
    }

    @Test
    fun `toCredential preserves previous profile and apiBaseUrl on refresh`() {
        val previous = OAuthCredential(
            accessToken = "old-session",
            refreshToken = "ghu_long_lived",
            expiresAt = 1_000L,
            accountId = "octocat",
            apiBaseUrl = "https://api.individual.githubcopilot.com",
            email = "octocat@github.com",
            plan = "Copilot Pro",
        )
        val refreshedSession = GitHubCopilotAuth.CopilotSessionToken(
            token = "new-session",
            expiresAtMillis = 2_000_000L,
            apiBaseUrl = null,
            sku = null,
        )

        val updated = GitHubCopilotAuth.toCredential(
            githubOAuthToken = "ghu_long_lived",
            sessionToken = refreshedSession,
            userProfile = null,
            previous = previous,
        )

        assertEquals("new-session", updated.accessToken)
        assertEquals("ghu_long_lived", updated.refreshToken)
        assertEquals(2_000_000L, updated.expiresAt)
        assertEquals("octocat", updated.accountId)
        assertEquals("https://api.individual.githubcopilot.com", updated.apiBaseUrl)
        assertEquals("octocat@github.com", updated.email)
        assertEquals("Copilot Pro", updated.plan)
    }

    @Test
    fun `planDisplayName formats known plans`() {
        assertEquals("Copilot Pro+", GitHubCopilotAuth.planDisplayName("individual_pro"))
        assertEquals("Copilot Pro", GitHubCopilotAuth.planDisplayName("individual"))
        assertEquals("Copilot Business", GitHubCopilotAuth.planDisplayName("copilot_for_business"))
        assertEquals("Copilot Enterprise", GitHubCopilotAuth.planDisplayName("enterprise"))
        assertEquals("Copilot Free", GitHubCopilotAuth.planDisplayName("free_limited_copilot"))
    }
}
