package com.jarves.mh.runtime

import com.jarves.mh.model.AgentKind
import com.jarves.mh.model.ProviderKind
import com.jarves.mh.model.providersForAgent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpencodeRuntimeTest {

    @Test
    fun openCodeProvidersIncludeZenAndOpenRouter() {
        val providers = providersForAgent(AgentKind.OPENCODE)
        assertTrue(ProviderKind.OPENCODE_ZEN in providers)
        assertTrue(ProviderKind.LLM_ROUTER in providers)
        assertFalse(ProviderKind.ANTHROPIC in providers)
        assertFalse(ProviderKind.DEEPSEEK in providers)
        assertEquals(2, providers.size)
    }

    @Test
    fun fallbackZenModelsProvideFreeTierCatalog() {
        val models = OpencodeRuntimeBridge.fallbackZenModels()
        assertTrue(models.isNotEmpty())
        assertTrue(models.all { it.isFree })
        assertTrue(models.any { it.id == "opencode/ling-3.0-flash-fin-free" })
        assertTrue(models.any { it.id == "opencode/nemotron-3.5-lightning-free" })
    }

    @Test
    fun errorMapperTranslatesKnownFailures() {
        assertEquals(
            "API key was rejected by the provider. Please check your saved key.",
            OpencodeErrorMapper.format("HTTP 401: Unauthorized API key"),
        )
        assertEquals(
            "Rate limit reached. Please wait a moment before trying again or switch to another model.",
            OpencodeErrorMapper.format("Error: 429 rate_limit_exceeded for free tier"),
        )
        assertEquals(
            "Network connection failed. Please check your internet connection and retry.",
            OpencodeErrorMapper.format("Fetch failed: ENOTFOUND api.openrouter.ai"),
        )
        assertEquals(
            "OpenCode binary is only supported on 64-bit ARM (aarch64) devices.",
            OpencodeErrorMapper.format("exec format error (architecture mismatch)"),
        )
        assertEquals(
            "OpenCode is not installed or installation failed. Please install it from Settings → Coding agent.",
            OpencodeErrorMapper.format("opencode is not installed"),
        )
        assertEquals(
            "Custom failure message",
            OpencodeErrorMapper.format("Custom failure message"),
        )
    }

    @Test
    fun eventParserHandlesCoreEvents() {
        val stepStart = OpencodeEventParser.parse("""{"type":"step_start","sessionID":"ses_123"}""")
        assertTrue(stepStart is OpencodeParsedEvent.SessionInit)
        assertEquals("ses_123", (stepStart as OpencodeParsedEvent.SessionInit).sessionId)

        val text = OpencodeEventParser.parse("""{"type":"text","part":{"text":"Hello world"}}""")
        assertTrue(text is OpencodeParsedEvent.TextDelta)
        assertEquals("Hello world", (text as OpencodeParsedEvent.TextDelta).text)

        val tool = OpencodeEventParser.parse("""{"type":"tool_use","part":{"tool":"bash","state":{"status":"completed","input":{"command":"ls"}}}}""")
        assertTrue(tool is OpencodeParsedEvent.ToolCompleted)
        assertEquals("bash", (tool as OpencodeParsedEvent.ToolCompleted).name)
        assertEquals("ls", tool.detail)

        val err = OpencodeEventParser.parse("""{"type":"error","error":{"message":"Something broke"}}""")
        assertTrue(err is OpencodeParsedEvent.Error)
        assertEquals("Something broke", (err as OpencodeParsedEvent.Error).message)
    }
}
