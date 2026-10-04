package com.jarves.mh.model

import com.jarves.mh.network.ProviderApiClient
import org.junit.Assert.assertEquals
import org.junit.Test

class AgentProtocolMappingTest {
    private val client = ProviderApiClient()

    @Test
    fun deepSeekHarnessUsesOpenAiProtocolAndUrlForDeepSeek() {
        val profile = ProviderProfile(ProviderKind.DEEPSEEK)
        val protocol = providerProtocolForAgent(profile, AgentKind.DEEPSEEK_HARNESS)
        val baseUrl = providerBaseUrlForAgent(profile, AgentKind.DEEPSEEK_HARNESS)
        val endpoint = client.messagesEndpoint(baseUrl, protocol)

        assertEquals(ProviderProtocol.OPENAI_CHAT, protocol)
        assertEquals("https://api.deepseek.com", baseUrl)
        assertEquals("https://api.deepseek.com/chat/completions", endpoint)
    }

    @Test
    fun deepSeekHarnessUsesOpenAiProtocolAndUrlForOpenRouter() {
        val profile = ProviderProfile(ProviderKind.LLM_ROUTER)
        val protocol = providerProtocolForAgent(profile, AgentKind.DEEPSEEK_HARNESS)
        val baseUrl = providerBaseUrlForAgent(profile, AgentKind.DEEPSEEK_HARNESS)
        val endpoint = client.messagesEndpoint(baseUrl, protocol)

        assertEquals(ProviderProtocol.OPENAI_CHAT, protocol)
        assertEquals("https://openrouter.ai/api/v1", baseUrl)
        assertEquals("https://openrouter.ai/api/v1/chat/completions", endpoint)
    }

    @Test
    fun claudeCodeUsesOpenRouterProtocolAndAnthropicMessagesEndpoint() {
        val profile = ProviderProfile(ProviderKind.LLM_ROUTER)
        val protocol = providerProtocolForAgent(profile, AgentKind.CLAUDE_CODE)
        val baseUrl = providerBaseUrlForAgent(profile, AgentKind.CLAUDE_CODE)
        val endpoint = client.messagesEndpoint(baseUrl, protocol)

        assertEquals(ProviderProtocol.OPENROUTER, protocol)
        assertEquals("https://openrouter.ai/api", baseUrl)
        assertEquals("https://openrouter.ai/api/v1/messages", endpoint)
    }

    @Test
    fun claudeCodeUsesAnthropicGatewayForDeepSeek() {
        val profile = ProviderProfile(ProviderKind.DEEPSEEK)
        val protocol = providerProtocolForAgent(profile, AgentKind.CLAUDE_CODE)
        val baseUrl = providerBaseUrlForAgent(profile, AgentKind.CLAUDE_CODE)
        val endpoint = client.messagesEndpoint(baseUrl, protocol)

        assertEquals(ProviderProtocol.ANTHROPIC_GATEWAY, protocol)
        assertEquals("https://api.deepseek.com/anthropic", baseUrl)
        assertEquals("https://api.deepseek.com/anthropic/v1/messages", endpoint)
    }

    @Test
    fun deepSeekHarnessUsesNativeAnthropicProtocolForAnthropic() {
        val profile = ProviderProfile(ProviderKind.ANTHROPIC)
        val protocol = providerProtocolForAgent(profile, AgentKind.DEEPSEEK_HARNESS)
        val baseUrl = providerBaseUrlForAgent(profile, AgentKind.DEEPSEEK_HARNESS)
        val endpoint = client.messagesEndpoint(baseUrl, protocol)

        assertEquals(ProviderProtocol.ANTHROPIC, protocol)
        assertEquals("https://api.anthropic.com", baseUrl)
        assertEquals("https://api.anthropic.com/v1/messages", endpoint)
    }

    @Test
    fun openCodeUsesOpenAiChatForOpenRouter() {
        val profile = ProviderProfile(ProviderKind.LLM_ROUTER)
        val protocol = providerProtocolForAgent(profile, AgentKind.OPENCODE)
        val baseUrl = providerBaseUrlForAgent(profile, AgentKind.OPENCODE)
        val endpoint = client.messagesEndpoint(baseUrl, protocol)

        assertEquals(ProviderProtocol.OPENAI_CHAT, protocol)
        assertEquals("https://openrouter.ai/api/v1", baseUrl)
        assertEquals("https://openrouter.ai/api/v1/chat/completions", endpoint)
    }

    @Test
    fun deepSeekHarnessCustomProtocolHonorsDshApi() {
        val openAiCustom = ProviderProfile(
            ProviderKind.CUSTOM,
            baseUrl = "https://custom.ai",
            dshApi = "openai-completions",
        )
        assertEquals(ProviderProtocol.OPENAI_CHAT, providerProtocolForAgent(openAiCustom, AgentKind.DEEPSEEK_HARNESS))
        assertEquals("https://custom.ai/v1", providerBaseUrlForAgent(openAiCustom, AgentKind.DEEPSEEK_HARNESS))

        val anthropicCustom = ProviderProfile(
            ProviderKind.CUSTOM,
            baseUrl = "https://custom.ai/v1",
            dshApi = "anthropic-messages",
        )
        assertEquals(ProviderProtocol.ANTHROPIC_GATEWAY, providerProtocolForAgent(anthropicCustom, AgentKind.DEEPSEEK_HARNESS))
        assertEquals("https://custom.ai", providerBaseUrlForAgent(anthropicCustom, AgentKind.DEEPSEEK_HARNESS))
    }
}
