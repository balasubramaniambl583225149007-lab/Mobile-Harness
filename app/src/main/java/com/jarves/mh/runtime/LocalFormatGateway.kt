package com.jarves.mh.runtime

import com.jarves.mh.model.ProviderProfile
import com.jarves.mh.model.ProviderKind
import com.jarves.mh.model.normalizeProviderBaseUrl
import android.util.Log
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONArray
import org.json.JSONObject

/** Small loopback-only Anthropic-to-OpenAI compatibility bridge for Claude Code. */
internal class LocalFormatGateway(
    private val profile: ProviderProfile,
    private val apiKey: String,
) : AutoCloseable {
    private val running = AtomicBoolean(true)
    private val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
    val url: String = "http://127.0.0.1:${server.localPort}"

    fun start(): LocalFormatGateway = apply {
        Thread({ acceptLoop() }, "mh-format-gateway").apply { isDaemon = true; start() }
    }

    private fun acceptLoop() {
        while (running.get()) {
            runCatching { server.accept() }.getOrNull()?.let { socket ->
                Thread({ socket.use(::handle) }, "mh-format-request").apply { isDaemon = true; start() }
            }
        }
    }

    private fun handle(socket: Socket) {
        val input = BufferedInputStream(socket.getInputStream())
        val requestLine = readLine(input) ?: return
        val headers = mutableMapOf<String, String>()
        while (true) {
            val line = readLine(input) ?: return
            if (line.isEmpty()) break
            val split = line.indexOf(':')
            if (split > 0) headers[line.substring(0, split).lowercase()] = line.substring(split + 1).trim()
        }
        val length = headers["content-length"]?.toIntOrNull() ?: 0
        val bodyBytes = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val count = input.read(bodyBytes, offset, length - offset)
            if (count < 0) break
            offset += count
        }
        val path = requestLine.split(' ').getOrNull(1).orEmpty().substringBefore('?')
        val output = BufferedOutputStream(socket.getOutputStream())
        if (path.endsWith("/count_tokens")) {
            val approximate = bodyBytes.decodeToString().length / 4 + 1
            writeJson(output, 200, JSONObject().put("input_tokens", approximate).toString())
            return
        }
        if (!path.endsWith("/messages")) {
            writeJson(output, 404, errorJson("not_found", "Unsupported gateway endpoint"))
            return
        }
        runCatching {
            val anthropic = JSONObject(bodyBytes.decodeToString())
            val useResponsesApi = profile.kind == ProviderKind.OPENCODE_ZEN || profile.dshApi == "openai-responses"
            val upstreamBody = if (useResponsesApi) toOpenAiResponses(anthropic) else toOpenAi(anthropic)
            val upstream = callProvider(upstreamBody, useResponsesApi)
            if (upstream.first !in 200..299) {
                Log.w("FormatGateway", "Provider returned HTTP ${upstream.first}: ${providerError(upstream.second)}")
                writeJson(output, upstream.first, errorJson("api_error", providerError(upstream.second)))
            } else {
                val translated = if (useResponsesApi) {
                    fromOpenAiResponses(JSONObject(upstream.second), anthropic.optString("model", profile.model))
                } else {
                    fromOpenAi(JSONObject(upstream.second), anthropic.optString("model", profile.model))
                }
                if (anthropic.optBoolean("stream", false)) writeStream(output, translated) else writeJson(output, 200, translated.toString())
            }
        }.onFailure { error ->
            writeJson(output, 502, errorJson("api_error", error.message ?: "Provider request failed"))
        }
    }

    private fun toOpenAi(source: JSONObject): JSONObject {
        val target = JSONObject()
            .put("model", normalizeModel(profile.model))
            .put("stream", false)
            .put("max_tokens", source.optInt("max_tokens", 4096))
        if (source.has("temperature")) target.put("temperature", source.get("temperature"))
        val messages = JSONArray()
        source.opt("system")?.let { system ->
            val text = when (system) {
                is JSONArray -> contentText(system)
                else -> system.toString()
            }
            if (text.isNotBlank()) messages.put(JSONObject().put("role", "system").put("content", text))
        }
        val sourceMessages = source.optJSONArray("messages") ?: JSONArray()
        for (index in 0 until sourceMessages.length()) {
            val message = sourceMessages.getJSONObject(index)
            val role = message.optString("role")
            val content = message.opt("content")
            if (content !is JSONArray) {
                messages.put(JSONObject().put("role", role).put("content", content ?: ""))
                continue
            }
            val text = contentText(content)
            val toolCalls = JSONArray()
            val toolResults = mutableListOf<JSONObject>()
            for (partIndex in 0 until content.length()) {
                val part = content.optJSONObject(partIndex) ?: continue
                when (part.optString("type")) {
                    "tool_use" -> toolCalls.put(
                        JSONObject().put("id", part.optString("id"))
                            .put("type", "function")
                            .put("function", JSONObject().put("name", part.optString("name")).put("arguments", part.optJSONObject("input")?.toString() ?: "{}")),
                    )
                    "tool_result" -> toolResults += JSONObject()
                        .put("role", "tool")
                        .put("tool_call_id", part.optString("tool_use_id"))
                        .put("content", valueText(part.opt("content")))
                }
            }
            if (text.isNotBlank() || toolCalls.length() > 0) {
                val converted = JSONObject().put("role", role).put("content", text.ifBlank { JSONObject.NULL })
                if (toolCalls.length() > 0) converted.put("tool_calls", toolCalls)
                messages.put(converted)
            }
            toolResults.forEach(messages::put)
        }
        target.put("messages", messages)
        source.optJSONArray("tools")?.let { tools ->
            val converted = JSONArray()
            for (index in 0 until tools.length()) {
                val tool = tools.getJSONObject(index)
                converted.put(JSONObject().put("type", "function").put("function", JSONObject()
                    .put("name", tool.optString("name"))
                    .put("description", tool.optString("description"))
                    .put("parameters", tool.optJSONObject("input_schema") ?: JSONObject().put("type", "object"))))
            }
            target.put("tools", converted).put("tool_choice", "auto")
        }
        return target
    }

    private fun toOpenAiResponses(source: JSONObject): JSONObject {
        val input = JSONArray()
        source.opt("system")?.let { system ->
            val text = if (system is JSONArray) contentText(system) else system.toString()
            if (text.isNotBlank()) input.put(JSONObject().put("role", "system").put("content", text))
        }
        val messages = source.optJSONArray("messages") ?: JSONArray()
        for (index in 0 until messages.length()) {
            val message = messages.getJSONObject(index)
            val role = message.optString("role")
            val parts = message.optJSONArray("content")
            if (parts == null) {
                input.put(JSONObject().put("role", role).put("content", message.opt("content") ?: ""))
                continue
            }
            val text = contentText(parts)
            if (text.isNotBlank()) input.put(JSONObject().put("role", role).put("content", text))
            for (partIndex in 0 until parts.length()) {
                val part = parts.optJSONObject(partIndex) ?: continue
                when (part.optString("type")) {
                    "tool_use" -> input.put(
                        JSONObject().put("type", "function_call")
                            .put("call_id", part.optString("id"))
                            .put("name", part.optString("name"))
                            .put("arguments", part.optJSONObject("input")?.toString() ?: "{}"),
                    )
                    "tool_result" -> input.put(
                        JSONObject().put("type", "function_call_output")
                            .put("call_id", part.optString("tool_use_id"))
                            .put("output", valueText(part.opt("content"))),
                    )
                }
            }
        }
        val target = JSONObject()
            .put("model", normalizeModel(profile.model))
            .put("input", input)
            .put("max_output_tokens", source.optInt("max_tokens", 4096))
            .put("stream", false)
        source.optJSONArray("tools")?.let { tools ->
            val converted = JSONArray()
            for (index in 0 until tools.length()) {
                val tool = tools.getJSONObject(index)
                converted.put(
                    JSONObject().put("type", "function")
                        .put("name", tool.optString("name"))
                        .put("description", tool.optString("description"))
                        .put("parameters", tool.optJSONObject("input_schema") ?: JSONObject().put("type", "object")),
                )
            }
            target.put("tools", converted)
        }
        return target
    }

    private fun fromOpenAi(source: JSONObject, model: String): JSONObject {
        val message = source.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message") ?: JSONObject()
        val content = JSONArray()
        val text = message.optString("content")
        if (text.isNotBlank()) content.put(JSONObject().put("type", "text").put("text", text))
        val calls = message.optJSONArray("tool_calls") ?: JSONArray()
        for (index in 0 until calls.length()) {
            val call = calls.getJSONObject(index)
            val function = call.optJSONObject("function") ?: JSONObject()
            val arguments = runCatching { JSONObject(function.optString("arguments", "{}")) }.getOrDefault(JSONObject())
            content.put(JSONObject().put("type", "tool_use")
                .put("id", call.optString("id").ifBlank { "tool_${UUID.randomUUID()}" })
                .put("name", function.optString("name"))
                .put("input", arguments))
        }
        val usage = source.optJSONObject("usage") ?: JSONObject()
        return JSONObject().put("id", source.optString("id").ifBlank { "msg_${UUID.randomUUID()}" })
            .put("type", "message").put("role", "assistant").put("model", model)
            .put("content", content).put("stop_reason", if (calls.length() > 0) "tool_use" else "end_turn")
            .put("stop_sequence", JSONObject.NULL)
            .put("usage", JSONObject().put("input_tokens", usage.optInt("prompt_tokens")).put("output_tokens", usage.optInt("completion_tokens")))
    }

    private fun fromOpenAiResponses(source: JSONObject, model: String): JSONObject {
        val content = JSONArray()
        val output = source.optJSONArray("output") ?: JSONArray()
        var hasToolCalls = false
        for (index in 0 until output.length()) {
            val item = output.optJSONObject(index) ?: continue
            when (item.optString("type")) {
                "message" -> {
                    val parts = item.optJSONArray("content") ?: JSONArray()
                    for (partIndex in 0 until parts.length()) {
                        val part = parts.optJSONObject(partIndex) ?: continue
                        if (part.optString("type") in setOf("output_text", "text")) {
                            val text = part.optString("text")
                            if (text.isNotBlank()) content.put(JSONObject().put("type", "text").put("text", text))
                        }
                    }
                }
                "function_call" -> {
                    hasToolCalls = true
                    val arguments = runCatching { JSONObject(item.optString("arguments", "{}")) }.getOrDefault(JSONObject())
                    content.put(
                        JSONObject().put("type", "tool_use")
                            .put("id", item.optString("call_id").ifBlank { "tool_${UUID.randomUUID()}" })
                            .put("name", item.optString("name"))
                            .put("input", arguments),
                    )
                }
            }
        }
        val usage = source.optJSONObject("usage") ?: JSONObject()
        return JSONObject().put("id", source.optString("id").ifBlank { "msg_${UUID.randomUUID()}" })
            .put("type", "message").put("role", "assistant").put("model", model)
            .put("content", content).put("stop_reason", if (hasToolCalls) "tool_use" else "end_turn")
            .put("stop_sequence", JSONObject.NULL)
            .put("usage", JSONObject().put("input_tokens", usage.optInt("input_tokens")).put("output_tokens", usage.optInt("output_tokens")))
    }

    private fun callProvider(body: JSONObject, useResponsesApi: Boolean): Pair<Int, String> {
        val baseUrl = if (profile.kind == ProviderKind.CUSTOM && profile.dshApi.startsWith("openai-")) {
            normalizeProviderBaseUrl(profile.resolvedBaseUrl, openAiCompatible = true)
        } else {
            profile.resolvedBaseUrl.trimEnd('/')
        }
        val endpoint = baseUrl + if (useResponsesApi) "/responses" else "/chat/completions"
        val connection = URL(endpoint).openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 20_000
            connection.readTimeout = 180_000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Authorization", "Bearer $apiKey")
            if (connection.url.host.equals("opencode.ai", ignoreCase = true)) {
                OpenCodeZenClient.applyHeaders(connection)
            }
            connection.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            code to stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        } finally {
            connection.disconnect()
        }
    }

    private fun writeStream(output: BufferedOutputStream, message: JSONObject) {
        val headers = "HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nCache-Control: no-cache\r\nConnection: close\r\n\r\n"
        output.write(headers.toByteArray())
        fun event(name: String, data: JSONObject) { output.write("event: $name\ndata: $data\n\n".toByteArray()) }
        val content = message.getJSONArray("content")
        event("message_start", JSONObject().put("type", "message_start").put("message", JSONObject(message.toString()).put("content", JSONArray()).put("stop_reason", JSONObject.NULL)))
        for (index in 0 until content.length()) {
            val block = content.getJSONObject(index)
            val type = block.getString("type")
            val start = if (type == "text") JSONObject().put("type", "text").put("text", "") else JSONObject().put("type", "tool_use").put("id", block.getString("id")).put("name", block.getString("name")).put("input", JSONObject())
            event("content_block_start", JSONObject().put("type", "content_block_start").put("index", index).put("content_block", start))
            val delta = if (type == "text") JSONObject().put("type", "text_delta").put("text", block.getString("text")) else JSONObject().put("type", "input_json_delta").put("partial_json", block.getJSONObject("input").toString())
            event("content_block_delta", JSONObject().put("type", "content_block_delta").put("index", index).put("delta", delta))
            event("content_block_stop", JSONObject().put("type", "content_block_stop").put("index", index))
        }
        event("message_delta", JSONObject().put("type", "message_delta").put("delta", JSONObject().put("stop_reason", message.getString("stop_reason")).put("stop_sequence", JSONObject.NULL)).put("usage", message.getJSONObject("usage")))
        event("message_stop", JSONObject().put("type", "message_stop"))
        output.flush()
    }

    private fun writeJson(output: BufferedOutputStream, code: Int, body: String) {
        val bytes = body.toByteArray()
        val reason = if (code in 200..299) "OK" else "Error"
        output.write("HTTP/1.1 $code $reason\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
        output.write(bytes)
        output.flush()
    }

    private fun readLine(input: BufferedInputStream): String? {
        val bytes = ArrayList<Byte>()
        while (true) {
            val value = input.read()
            if (value < 0) return if (bytes.isEmpty()) null else bytes.toByteArray().decodeToString()
            if (value == '\n'.code) return bytes.toByteArray().decodeToString().trimEnd('\r')
            bytes += value.toByte()
        }
    }

    private fun contentText(array: JSONArray): String = buildString {
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            if (item.optString("type") == "text") append(item.optString("text"))
        }
    }

    private fun valueText(value: Any?): String = when (value) {
        is String -> value
        is JSONArray -> contentText(value).ifBlank { value.toString() }
        null, JSONObject.NULL -> ""
        else -> value.toString()
    }

    private fun providerError(body: String): String = runCatching {
        JSONObject(body).optJSONObject("error")?.optString("message").orEmpty().ifBlank { body.take(500) }
    }.getOrDefault(body.take(500))

    private fun normalizeModel(model: String): String = model
        .removePrefix("models/")
        .removePrefix("anthropic/")
        .substringBefore('[')
        .trim()

    private fun errorJson(type: String, message: String) = JSONObject().put("type", "error").put("error", JSONObject().put("type", type).put("message", message)).toString()

    override fun close() {
        running.set(false)
        runCatching { server.close() }
    }
}
