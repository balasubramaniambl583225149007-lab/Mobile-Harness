package com.jarves.mh.runtime

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject

internal object OpenCodeZenClient {
    const val USER_AGENT = "opencode/1.18.20"

    fun applyHeaders(connection: HttpURLConnection, sessionId: String = "session-${UUID.randomUUID()}") {
        connection.setRequestProperty("User-Agent", USER_AGENT)
        connection.setRequestProperty("x-session-id", sessionId)
        connection.setRequestProperty("x-opencode-client", "opencode")
        connection.setRequestProperty("x-opencode-version", "1.18.20")
    }
}

/** Loopback proxy that adds OpenCode client metadata to Zen Responses API calls. */
internal class OpenCodeZenGateway(
    private val apiKey: String,
) : AutoCloseable {
    private val running = AtomicBoolean(true)
    private val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
    val baseUrl: String = "http://127.0.0.1:${server.localPort}/v1"

    fun start(): OpenCodeZenGateway = apply {
        Thread({ acceptLoop() }, "mh-opencode-zen-routing").apply { isDaemon = true; start() }
    }

    private fun acceptLoop() {
        while (running.get()) {
            runCatching { server.accept() }.getOrNull()?.let { socket ->
                Thread({ socket.use(::handle) }, "mh-opencode-zen-request").apply { isDaemon = true; start() }
            }
        }
    }

    private fun handle(socket: Socket) {
        val input = BufferedInputStream(socket.getInputStream())
        val requestLine = readLine(input) ?: return
        val parts = requestLine.split(' ')
        val method = parts.getOrNull(0).orEmpty()
        val requestTarget = parts.getOrNull(1).orEmpty()
        val path = requestTarget.substringBefore('?')
        val query = requestTarget.substringAfter('?', "").takeIf(String::isNotEmpty)?.let { "?$it" }.orEmpty()
        val headers = mutableMapOf<String, String>()
        while (true) {
            val line = readLine(input) ?: return
            if (line.isEmpty()) break
            val split = line.indexOf(':')
            if (split > 0) headers[line.substring(0, split).lowercase()] = line.substring(split + 1).trim()
        }
        val length = headers["content-length"]?.toIntOrNull() ?: 0
        val output = BufferedOutputStream(socket.getOutputStream())
        if (length !in 0..MAX_REQUEST_BYTES) {
            writeJson(output, 413, "Request is too large")
            return
        }
        val body = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val count = input.read(body, offset, length - offset)
            if (count < 0) break
            offset += count
        }

        runCatching {
            require(method == "POST" || method == "GET") { "Unsupported Zen method" }
            require(path.startsWith("/v1/") || path == "/v1" || path.startsWith("/")) { "Invalid Zen path" }
            val apiPath = if (path.startsWith("/v1/")) path.removePrefix("/v1") else path
            val endpoint = "https://opencode.ai/zen/v1$apiPath$query"
            val connection = URL(endpoint).openConnection() as HttpURLConnection
            try {
                connection.requestMethod = method
                connection.connectTimeout = 20_000
                connection.readTimeout = 180_000
                connection.setRequestProperty("Accept", headers["accept"] ?: "application/json")
                headers["content-type"]?.let { connection.setRequestProperty("Content-Type", it) }
                connection.setRequestProperty("Authorization", "Bearer $apiKey")
                OpenCodeZenClient.applyHeaders(connection)
                if (method == "POST") {
                    connection.doOutput = true
                    connection.outputStream.use { it.write(body) }
                }
                val code = connection.responseCode
                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                val responseType = connection.contentType ?: "application/json"
                output.write("HTTP/1.1 $code ${if (code in 200..299) "OK" else "Error"}\r\n".toByteArray())
                output.write("Content-Type: $responseType\r\nTransfer-Encoding: chunked\r\nConnection: close\r\n\r\n".toByteArray())
                output.flush()
                stream?.use { upstream ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val count = upstream.read(buffer)
                        if (count < 0) break
                        if (count == 0) continue
                        output.write("${count.toString(16)}\r\n".toByteArray())
                        output.write(buffer, 0, count)
                        output.write("\r\n".toByteArray())
                        output.flush()
                    }
                }
                output.write("0\r\n\r\n".toByteArray())
                output.flush()
            } finally {
                connection.disconnect()
            }
        }.onFailure { error -> writeJson(output, 502, error.message ?: "OpenCode Zen request failed") }
    }

    private fun writeJson(output: BufferedOutputStream, code: Int, message: String) {
        val body = JSONObject()
            .put("error", JSONObject().put("message", message))
            .toString()
            .toByteArray()
        output.write("HTTP/1.1 $code Error\r\nContent-Type: application/json\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
        output.write(body)
        output.flush()
    }

    private fun readLine(input: BufferedInputStream): String? {
        val bytes = ArrayList<Byte>()
        while (bytes.size <= MAX_HEADER_LINE_BYTES) {
            val value = input.read()
            if (value < 0) return if (bytes.isEmpty()) null else bytes.toByteArray().decodeToString()
            if (value == '\n'.code) return bytes.toByteArray().decodeToString().trimEnd('\r')
            bytes += value.toByte()
        }
        return null
    }

    override fun close() {
        running.set(false)
        runCatching { server.close() }
    }

    private companion object {
        const val MAX_REQUEST_BYTES = 16 * 1024 * 1024
        const val MAX_HEADER_LINE_BYTES = 16 * 1024
    }
}
