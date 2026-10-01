package com.jarves.mh.runtime

import com.jarves.mh.model.ProviderKind
import com.jarves.mh.model.ProviderProfile
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class OpenCodeRuntimeTest {
    @Test
    fun openRouterFreeModelUsesVersionedEndpointAndSavedKey() {
        val config = OpenCodeLaunchConfigBuilder.build(
            ProviderProfile(ProviderKind.LLM_ROUTER, model = "deepseek-r1:free"),
            "temporary-key",
        )
        val inlineConfig = JSONObject(config.environment.getValue("OPENCODE_CONFIG_CONTENT"))
        val provider = inlineConfig.getJSONObject("provider").getJSONObject("openrouter")

        assertEquals("openrouter/deepseek/deepseek-r1:free", config.model)
        assertEquals("https://openrouter.ai/api/v1", provider.getJSONObject("options").getString("baseURL"))
        assertEquals("temporary-key", provider.getJSONObject("options").getString("apiKey"))
        assertEquals("temporary-key", config.environment["OPENROUTER_API_KEY"])
    }

    @Test
    fun customOpenAiProviderAddsSchemeAndV1() {
        val config = OpenCodeLaunchConfigBuilder.build(
            ProviderProfile(
                ProviderKind.CUSTOM,
                baseUrl = " gateway.example ",
                model = "my-model",
                dshApi = "openai-completions",
            ),
            "temporary-key",
        )
        val inlineConfig = JSONObject(config.environment.getValue("OPENCODE_CONFIG_CONTENT"))
        val provider = inlineConfig.getJSONObject("provider").getJSONObject("custom")

        assertEquals("custom/my-model", config.model)
        assertEquals("https://gateway.example/v1", provider.getJSONObject("options").getString("baseURL"))
        assertEquals("temporary-key", config.environment["OPENAI_API_KEY"])
        assertEquals("https://gateway.example/v1", config.environment["OPENAI_API_BASE"])
    }

    @Test
    fun parsesTextAndProviderErrorsFromJsonEvents() {
        assertEquals("hello", parseOpenCodeOutputLine("""{"type":"text","part":{"text":"hello"}}""")?.text)
        assertNotNull(parseOpenCodeOutputLine("""{"type":"error","error":{"message":"ResourceExhausted"}}""")?.error)
    }
}
