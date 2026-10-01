package com.jarves.mh.runtime

import com.jarves.mh.model.ChatMessage
import com.jarves.mh.model.ChangeItem
import com.jarves.mh.model.ProviderProfile
import com.jarves.mh.model.ProjectKind
import com.jarves.mh.model.RuntimeEvent
import com.jarves.mh.model.ToolRequest
import kotlinx.coroutines.flow.Flow

data class RuntimeLaunchConfig(
    val executable: String,
    val arguments: List<String>,
    val environment: Map<String, String>,
)

interface RuntimeBridge {
    val events: Flow<RuntimeEvent>
    suspend fun startSession(projectId: String, projectSlug: String, projectKind: ProjectKind, prompt: String, conversationHistory: List<ChatMessage>, provider: ProviderProfile): String    suspend fun respondToApproval(request: ToolRequest, approved: Boolean)
    suspend fun stopSession(sessionId: String)
    suspend fun stopActiveSession()
    suspend fun undoLastChanges(projectId: String): Boolean
    suspend fun acceptLastChanges(projectId: String)
    suspend fun loadPendingChanges(projectId: String): List<ChangeItem>
    suspend fun undoFileChange(projectId: String, path: String): Boolean
    suspend fun acceptFileChange(projectId: String, path: String): Boolean
}

/** Keeps recent context while preventing an old chat from becoming an unbounded prompt allocation. */
internal fun List<ChatMessage>.recentWithinCharacterBudget(maxCharacters: Int): List<ChatMessage> {
    if (maxCharacters <= 0 || isEmpty()) return emptyList()
    var remaining = maxCharacters
    val selected = ArrayDeque<ChatMessage>()
    for (message in asReversed()) {
        if (remaining <= 0) break
        val text = if (message.text.length <= remaining) message.text else message.text.takeLast(remaining)
        selected.addFirst(message.copy(text = text))
        remaining -= text.length
        if (text.length < message.text.length) break
    }
    return selected.toList()
}

object RuntimeLaunchConfigBuilder {
    fun build(profile: ProviderProfile, authToken: String? = null, localGatewayUrl: String? = null): RuntimeLaunchConfig {
        val environment = linkedMapOf("DISABLE_AUTOUPDATER" to "1")
        when (profile.kind.protocol) {
            com.jarves.mh.model.ProviderProtocol.CLAUDE_LOGIN -> {
                require(!authToken.isNullOrBlank()) { "Enter a Claude subscription token first" }
                environment["CLAUDE_CODE_OAUTH_TOKEN"] = authToken
                // Claude Code gives API-key variables precedence over OAuth. Explicitly
                // clear them so a previous API provider can never shadow this token.
                environment["ANTHROPIC_API_KEY"] = ""
                environment["ANTHROPIC_AUTH_TOKEN"] = ""
            }
            com.jarves.mh.model.ProviderProtocol.ANTHROPIC -> {
                environment["ANTHROPIC_BASE_URL"] = com.jarves.mh.model.normalizeBaseUrl(profile.baseUrl)
                environment["ANTHROPIC_MODEL"] = profile.model
            }
            com.jarves.mh.model.ProviderProtocol.ANTHROPIC_GATEWAY -> {
                val cleanUrl = com.jarves.mh.model.normalizeBaseUrl(profile.baseUrl)
                val target = if (cleanUrl.endsWith("/v1")) cleanUrl.removeSuffix("/v1") else cleanUrl
                environment["ANTHROPIC_BASE_URL"] = target
                environment["ANTHROPIC_MODEL"] = profile.model
            }
            com.jarves.mh.model.ProviderProtocol.OPENROUTER -> {
                val targetBaseUrl = (localGatewayUrl ?: profile.resolvedBaseUrl).trim().trimEnd('/')
                val cleanBaseUrl = when {
                    localGatewayUrl != null -> targetBaseUrl
                    targetBaseUrl.endsWith("/api/v1") -> targetBaseUrl.removeSuffix("/v1")
                    targetBaseUrl.endsWith("/v1") -> targetBaseUrl.removeSuffix("/v1") + "/api"
                    targetBaseUrl == "https://openrouter.ai" || targetBaseUrl == "http://openrouter.ai" -> "$targetBaseUrl/api"
                    else -> targetBaseUrl
                }
                environment["ANTHROPIC_BASE_URL"] = cleanBaseUrl
                environment["ANTHROPIC_MODEL"] = profile.model
            }
            com.jarves.mh.model.ProviderProtocol.OPENAI_RESPONSES,
            com.jarves.mh.model.ProviderProtocol.OPENAI_CHAT,
            -> {
                require(!localGatewayUrl.isNullOrBlank()) { "A local format gateway is required for this provider" }
                environment["ANTHROPIC_BASE_URL"] = localGatewayUrl.trimEnd('/')
                environment["ANTHROPIC_MODEL"] = "claude-sonnet-4-6"
            }
        }
        val runtimeModel = environment["ANTHROPIC_MODEL"] ?: profile.model
        if (profile.kind.protocol != com.jarves.mh.model.ProviderProtocol.CLAUDE_LOGIN) {
            environment["ANTHROPIC_DEFAULT_OPUS_MODEL"] = runtimeModel
            environment["ANTHROPIC_DEFAULT_SONNET_MODEL"] = runtimeModel
            environment["ANTHROPIC_DEFAULT_HAIKU_MODEL"] = runtimeModel
            environment["ANTHROPIC_SMALL_MODEL"] = runtimeModel
            environment["ANTHROPIC_FAST_MODEL"] = runtimeModel
            environment["CLAUDE_CODE_SUBAGENT_MODEL"] = runtimeModel
            environment["CLAUDE_CODE_ENABLE_GATEWAY_MODEL_DISCOVERY"] = "1"
            environment["CLAUDE_CODE_DISABLE_TOKEN_COUNTING"] = "1"
            environment["DISABLE_TELEMETRY"] = "1"
            if (profile.kind == com.jarves.mh.model.ProviderKind.LLM_ROUTER || profile.kind.protocol == com.jarves.mh.model.ProviderProtocol.OPENROUTER) {
                environment["ANTHROPIC_AUTH_TOKEN"] = authToken.orEmpty()
                environment["ANTHROPIC_API_KEY"] = ""
                if (!authToken.isNullOrBlank()) {
                    environment["OPENROUTER_API_KEY"] = authToken
                }
            } else if (!authToken.isNullOrBlank()) {
                environment["ANTHROPIC_AUTH_TOKEN"] = authToken
                environment["ANTHROPIC_API_KEY"] = authToken
            }
            if (profile.kind == com.jarves.mh.model.ProviderKind.CUSTOM) {
                val cleanUrl = com.jarves.mh.model.normalizeBaseUrl(profile.baseUrl)
                val openAiBase = if (cleanUrl.endsWith("/v1")) cleanUrl else "$cleanUrl/v1"
                environment["OPENAI_API_BASE"] = openAiBase
                if (!authToken.isNullOrBlank()) {
                    environment["OPENAI_API_KEY"] = authToken
                }
            }
        }
        return RuntimeLaunchConfig(
            executable = "/usr/local/bin/claude",
            arguments = listOf("-p", "--input-format", "stream-json", "--output-format", "stream-json", "--verbose"),
            environment = environment,
        )
    }
}
