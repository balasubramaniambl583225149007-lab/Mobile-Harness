package com.jarves.mh.runtime

import android.content.Context
import androidx.core.content.ContextCompat
import com.jarves.mh.model.ChatMessage
import com.jarves.mh.model.ChangeItem
import com.jarves.mh.model.ProjectKind
import com.jarves.mh.model.ProviderProfile
import com.jarves.mh.model.RuntimeEvent
import com.jarves.mh.model.ToolRequest
import com.jarves.mh.data.KeyDecryptionException
import com.jarves.mh.data.sanitizeApiKey
import java.io.File
import java.io.RandomAccessFile
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject

internal sealed interface OpencodeParsedEvent {
    data class SessionInit(val sessionId: String) : OpencodeParsedEvent
    data class TextDelta(val text: String) : OpencodeParsedEvent
    data class ToolStarted(val name: String, val detail: String) : OpencodeParsedEvent
    data class ToolCompleted(val name: String, val detail: String) : OpencodeParsedEvent
    data class Error(val message: String, val status: Int? = null, val errorType: String? = null) : OpencodeParsedEvent
    data class Finished(val reason: String?) : OpencodeParsedEvent
}

internal object OpencodeErrorMapper {
    fun format(raw: String, status: Int? = null, errorType: String? = null): String {
        val lower = raw.lowercase()
        val typeLower = errorType.orEmpty().lowercase()
        return when {
            status == 401 || status == 403 || typeLower == "provider.auth" ||
                "401" in lower || "unauthorized" in lower || "invalid api key" in lower || "bad key" in lower ||
                "authentication failed" in lower || "reconnect opencode console" in lower || "user not found" in lower ->
                "API key was rejected by the provider. Please check your saved key."
            status == 402 || typeLower == "provider.quota" ||
                "402" in lower || "credit" in lower || "payment" in lower || "balance" in lower ->
                "Provider credits exhausted. Please add credits or switch to a free model."
            status == 429 || typeLower == "provider.rate-limit" ||
                "429" in lower || "rate limit" in lower || "rate_limit" in lower || "quota" in lower ||
                "too many requests" in lower || "resource exhausted" in lower ->
                "Rate limit reached. Please wait a moment before trying again or switch to another model."
            typeLower == "provider.no-route" || "model unavailable" in lower ->
                "The selected model is unavailable for this provider: $raw"
            "network" in lower || "enotfound" in lower || "econnrefused" in lower || "connection refused" in lower ||
                "fetch failed" in lower || "etimedout" in lower || "dns" in lower ->
                "Network connection failed. Please check your internet connection and retry."
            "exec format error" in lower || "architecture" in lower ->
                "OpenCode binary is only supported on 64-bit ARM (aarch64) devices."
            "not installed" in lower ->
                "OpenCode is not installed or installation failed. Please install it from Settings → Coding agent."
            else -> raw
        }
    }
}

internal object OpencodeEventParser {
    fun parse(line: String): OpencodeParsedEvent? {
        val root = runCatching { JSONObject(line) }.getOrNull() ?: return null
        return when (root.optString("type")) {
            "step_start" -> {
                val sid = root.optString("sessionID")
                if (sid.isNotBlank()) OpencodeParsedEvent.SessionInit(sid) else null
            }
            "text" -> {
                val part = root.optJSONObject("part") ?: return null
                val text = part.optString("text")
                if (text.isNotEmpty()) OpencodeParsedEvent.TextDelta(text) else null
            }
            "tool_use" -> {
                val part = root.optJSONObject("part") ?: return null
                val toolName = part.optString("tool").ifBlank { "Tool" }
                val state = part.optJSONObject("state")
                val status = state?.optString("status")
                val input = state?.optJSONObject("input")
                val detail = input?.optString("path")?.takeIf { it.isNotBlank() }
                    ?: input?.optString("command")?.takeIf { it.isNotBlank() }
                    ?: state?.optString("title")?.takeIf { it.isNotBlank() }
                    ?: toolName
                if (status == "completed") {
                    OpencodeParsedEvent.ToolCompleted(toolName, detail)
                } else {
                    OpencodeParsedEvent.ToolStarted(toolName, detail)
                }
            }
            "step_finish" -> {
                val part = root.optJSONObject("part")
                OpencodeParsedEvent.Finished(part?.optString("reason"))
            }
            "error" -> {
                val err = root.optJSONObject("error")
                val status = err?.optInt("status", 0)?.takeIf { it > 0 }
                val errType = err?.optString("type")?.takeIf { it.isNotBlank() }
                val msg = err?.optString("message") ?: root.optString("message", "OpenCode execution failed")
                OpencodeParsedEvent.Error(msg, status, errType)
            }
            else -> null
        }
    }
}

class OpencodeRuntimeBridge(
    private val context: Context,
    private val secretProvider: (ProviderProfile) -> String?,
    private val sessionIdProvider: (String) -> String?,
    private val saveSessionId: (String, String) -> Unit,
) : RuntimeBridge {
    private val installer = RuntimeInstaller(context)
    private val checkpoints = WorkspaceCheckpoints(context.filesDir)
    private val eventBus = MutableSharedFlow<RuntimeEvent>(extraBufferCapacity = 64)
    override val events: Flow<RuntimeEvent> = eventBus
    private val finished = ConcurrentHashMap.newKeySet<String>()
    @Volatile private var activeProcess: Process? = null
    @Volatile private var activeSessionId: String? = null
    @Volatile private var userStopRequested = false
    @Volatile private var foregroundResultPosted = false

    fun configureProjectRoot(projectId: String, rootPath: String) = checkpoints.configureProjectRoot(projectId, rootPath)

    override suspend fun startSession(
        projectId: String,
        projectSlug: String,
        projectKind: ProjectKind,
        prompt: String,
        conversationHistory: List<ChatMessage>,
        provider: ProviderProfile,
    ): String = withContext(Dispatchers.IO + NonCancellable) {
        val sessionId = UUID.randomUUID().toString()
        activeSessionId = sessionId
        userStopRequested = false
        foregroundResultPosted = false
        finished.remove(sessionId)
        eventBus.emit(RuntimeEvent.SessionStarted(sessionId))

        val isArm64 = android.os.Build.SUPPORTED_ABIS.any { it.contains("arm64", ignoreCase = true) || it.contains("aarch64", ignoreCase = true) }
            || System.getProperty("os.arch").orEmpty().let { it.contains("aarch64", ignoreCase = true) || it.contains("arm64", ignoreCase = true) }
        if (!isArm64) {
            emitFailure(sessionId, "OpenCode binary is only supported on 64-bit ARM (aarch64) devices.")
            return@withContext sessionId
        }

        if (!installer.isAgentInstalled(com.jarves.mh.model.AgentKind.OPENCODE)) {
            emitFailure(sessionId, "OpenCode is not installed or installation failed. Please install it from Settings → Coding agent.")
            return@withContext sessionId
        }

        val secret = try {
            secretProvider(provider)?.let { sanitizeApiKey(it) }.orEmpty()
        } catch (e: KeyDecryptionException) {
            emitFailure(sessionId, "Saved key could not be read. Please re-enter it.")
            return@withContext sessionId
        }

        val (effectiveProviderKind, effectiveSecret) = if (provider.kind == com.jarves.mh.model.ProviderKind.OPENCODE_ZEN && secret.isBlank()) {
            val fallbackKey = try {
                secretProvider(ProviderProfile(com.jarves.mh.model.ProviderKind.LLM_ROUTER))?.let { sanitizeApiKey(it) }.orEmpty()
            } catch (e: KeyDecryptionException) {
                ""
            }
            if (fallbackKey.isNotBlank()) {
                com.jarves.mh.model.ProviderKind.LLM_ROUTER to fallbackKey
            } else {
                com.jarves.mh.model.ProviderKind.OPENCODE_ZEN to ""
            }
        } else {
            provider.kind to secret
        }

        if (effectiveProviderKind == com.jarves.mh.model.ProviderKind.LLM_ROUTER && effectiveSecret.isBlank()) {
            emitFailure(sessionId, "OpenRouter requires an API key. Please add your key in Settings → Coding agent.")
            return@withContext sessionId
        }

        runCatching {
            RuntimeTaskController.stopAction = {
                userStopRequested = true
                activeProcess?.destroy()
            }
            startForegroundRuntime(projectSlug)
            val installed = installer.installedRuntime()
            val workspace = checkpoints.ensureWorkspace(projectId)
            checkpoints.createCheckpoint(projectId, workspace)
            val before = checkpoints.snapshot(workspace)

            val existingSession = sessionIdProvider(projectId)
            val modelArg = when {
                effectiveProviderKind == com.jarves.mh.model.ProviderKind.LLM_ROUTER -> {
                    when {
                        provider.model.startsWith("openrouter/") -> provider.model
                        provider.model.isNotBlank() && !provider.model.startsWith("opencode/") -> "openrouter/${provider.model}"
                        else -> "openrouter/~anthropic/claude-sonnet-latest"
                    }
                }
                provider.kind == com.jarves.mh.model.ProviderKind.OPENCODE_ZEN -> {
                    when {
                        provider.model.isBlank() -> null
                        provider.model.startsWith("opencode/") -> provider.model
                        else -> "opencode/${provider.model}"
                    }
                }
                else -> provider.model.takeIf { it.isNotBlank() }
            }
            val command = buildList {
                add(RuntimeInstaller.OPENCODE_GUEST_PATH)
                add("run")
                addAll(listOf("--format", "json"))
                add("--auto")
                add("--standalone")
                if (!existingSession.isNullOrBlank()) {
                    addAll(listOf("--session", existingSession))
                }
                if (!modelArg.isNullOrBlank()) {
                    addAll(listOf("--model", modelArg))
                }
                add(prompt)
            }

            val environment = linkedMapOf(
                "TERM" to "xterm-256color",
            )
            if (effectiveProviderKind == com.jarves.mh.model.ProviderKind.OPENCODE_ZEN && effectiveSecret.isNotBlank()) {
                environment["OPENCODE_API_KEY"] = effectiveSecret
            } else if (effectiveProviderKind == com.jarves.mh.model.ProviderKind.LLM_ROUTER && effectiveSecret.isNotBlank()) {
                environment["OPENROUTER_API_KEY"] = effectiveSecret
            }

            // Ensure no other provider's env vars leak into opencode process
            listOf(
                "OPENAI_API_KEY",
                "OPENAI_BASE_URL",
                "ANTHROPIC_API_KEY",
                "ANTHROPIC_AUTH_TOKEN",
                "ANTHROPIC_BASE_URL",
                "DEEPSEEK_API_KEY",
                "DEEPSEEK_BASE_URL",
            ).forEach { environment.remove(it) }

            val process = installer.process(
                installed.proot,
                installed.rootfs,
                workspace,
                environment,
                command,
                guestWorkspacePath = "/workspace/$projectSlug",
                emulateHardLinks = false,
            )
            activeProcess = process
            if (userStopRequested) process.destroy()

            val native = process as? NativeSpawnProcess ?: error("Unsupported OpenCode process")
            var offset = 0L
            val pending = StringBuilder()

            suspend fun handleLine(line: String) {
                when (val event = OpencodeEventParser.parse(line)) {
                    is OpencodeParsedEvent.SessionInit -> saveSessionId(projectId, event.sessionId)
                    is OpencodeParsedEvent.TextDelta -> {
                        eventBus.emit(RuntimeEvent.AssistantDelta(sessionId, event.text))
                    }
                    is OpencodeParsedEvent.ToolStarted -> eventBus.emit(RuntimeEvent.ToolStarted(sessionId, event.name, event.detail))
                    is OpencodeParsedEvent.ToolCompleted -> eventBus.emit(RuntimeEvent.ToolCompleted(sessionId, event.name, event.detail))
                    is OpencodeParsedEvent.Error -> throw OpencodeSessionException(
                        OpencodeErrorMapper.format(event.message, event.status, event.errorType)
                    )
                    is OpencodeParsedEvent.Finished -> Unit
                    null -> Unit
                }
            }

            while (process.isAlive || native.outputFile.length() > offset) {
                val available = native.outputFile.length() - offset
                if (available <= 0) {
                    delay(50)
                    continue
                }
                val bytes = ByteArray(minOf(available, 16L * 1024).toInt())
                val count = RandomAccessFile(native.outputFile, "r").use { file ->
                    file.seek(offset)
                    file.read(bytes)
                }
                if (count <= 0) continue
                offset += count
                pending.append(bytes.decodeToString(0, count))
                var newline = pending.indexOf("\n")
                while (newline >= 0) {
                    val line = pending.substring(0, newline).trimEnd('\r')
                    pending.delete(0, newline + 1)
                    handleLine(line)
                    newline = pending.indexOf("\n")
                }
            }
            pending.toString().trim().takeIf(String::isNotEmpty)?.let { handleLine(it) }

            val exitCode = process.waitFor()
            if (userStopRequested) {
                cancelForegroundRuntime()
                emitCompleted(sessionId)
                return@withContext sessionId
            }
            if (exitCode != 0) {
                val tail = pending.toString().takeLast(500)
                val rawMsg = if (tail.isNotBlank()) tail else "OpenCode exited with code $exitCode"
                throw OpencodeSessionException(OpencodeErrorMapper.format(rawMsg))
            }

            val paths = checkpoints.changedFiles(workspace, before)
            checkpoints.saveChangedPaths(projectId, paths)
            if (paths.isNotEmpty()) {
                eventBus.emit(RuntimeEvent.FilesChanged(sessionId, checkpoints.buildChangeDetails(projectId, workspace, paths)))
            }
            finishForegroundRuntime(true, projectSlug, "Session completed")
            emitCompleted(sessionId)
        }.onFailure { ex ->
            if (userStopRequested) {
                cancelForegroundRuntime()
                emitCompleted(sessionId)
            } else {
                val mapped = OpencodeErrorMapper.format(ex.message ?: "OpenCode failed")
                finishForegroundRuntime(false, projectSlug, mapped)
                emitFailure(sessionId, mapped)
            }
        }
        sessionId
    }

    override suspend fun respondToApproval(request: ToolRequest, approved: Boolean) = Unit
    override suspend fun stopSession(sessionId: String) {
        if (activeSessionId == sessionId) stopActiveSession()
    }
    override suspend fun stopActiveSession() {
        userStopRequested = true
        activeProcess?.destroy()
    }

    override suspend fun undoLastChanges(projectId: String): Boolean = withContext(Dispatchers.IO) {
        val checkpoint = checkpoints.checkpointDir(projectId)
        val backup = File(checkpoint, "project")
        val paths = checkpoints.readChangedPaths(projectId)
        if (!backup.isDirectory || paths.isEmpty()) return@withContext false
        val workspace = checkpoints.ensureWorkspace(projectId)
        paths.forEach { restore(workspace, backup, it) }
        checkpoint.deleteRecursively()
        true
    }

    override suspend fun acceptLastChanges(projectId: String): Unit = withContext(Dispatchers.IO) {
        checkpoints.checkpointDir(projectId).deleteRecursively()
        Unit
    }

    override suspend fun loadPendingChanges(projectId: String): List<ChangeItem> = withContext(Dispatchers.IO) {
        val paths = checkpoints.readChangedPaths(projectId)
        if (paths.isEmpty()) emptyList() else checkpoints.buildChangeDetails(projectId, checkpoints.ensureWorkspace(projectId), paths)
    }

    override suspend fun undoFileChange(projectId: String, path: String): Boolean = withContext(Dispatchers.IO) {
        if (path !in checkpoints.readChangedPaths(projectId)) return@withContext false
        restore(checkpoints.ensureWorkspace(projectId), File(checkpoints.checkpointDir(projectId), "project"), path)
        checkpoints.removeChangedPath(projectId, path)
        true
    }

    override suspend fun acceptFileChange(projectId: String, path: String): Boolean = withContext(Dispatchers.IO) {
        if (path !in checkpoints.readChangedPaths(projectId)) return@withContext false
        val workspace = checkpoints.ensureWorkspace(projectId)
        val backup = File(checkpoints.checkpointDir(projectId), "project")
        val current = checkpoints.safeWorkspaceFile(workspace, path)
        val baseline = checkpoints.safeWorkspaceFile(backup, path)
        if (current.isFile) {
            baseline.parentFile?.mkdirs()
            current.copyTo(baseline, overwrite = true)
        } else baseline.delete()
        checkpoints.removeChangedPath(projectId, path)
        true
    }

    private fun restore(workspace: File, backup: File, path: String) {
        val target = checkpoints.safeWorkspaceFile(workspace, path)
        val original = checkpoints.safeWorkspaceFile(backup, path)
        if (original.isFile) {
            target.parentFile?.mkdirs()
            original.copyTo(target, overwrite = true)
        } else target.delete()
    }

    private suspend fun emitCompleted(sessionId: String) {
        if (finished.add(sessionId)) eventBus.emit(RuntimeEvent.SessionCompleted(sessionId))
    }

    private suspend fun emitFailure(sessionId: String, reason: String) {
        if (finished.add(sessionId)) eventBus.emit(RuntimeEvent.SessionFailed(sessionId, reason))
    }

    private fun startForegroundRuntime(projectName: String) {
        ContextCompat.startForegroundService(
            context,
            android.content.Intent(context, RuntimeExecutionService::class.java)
                .setAction(RuntimeExecutionService.ACTION_START)
                .putExtra(RuntimeExecutionService.EXTRA_PROJECT_NAME, projectName),
        )
    }

    private fun finishForegroundRuntime(completed: Boolean, projectName: String, detail: String) {
        if (foregroundResultPosted) return
        foregroundResultPosted = true
        runCatching {
            context.startService(
                android.content.Intent(context, RuntimeExecutionService::class.java)
                    .setAction(if (completed) RuntimeExecutionService.ACTION_COMPLETE else RuntimeExecutionService.ACTION_FAILED)
                    .putExtra(RuntimeExecutionService.EXTRA_PROJECT_NAME, projectName)
                    .putExtra(RuntimeExecutionService.EXTRA_DETAIL, detail),
            )
        }.onFailure { context.stopService(android.content.Intent(context, RuntimeExecutionService::class.java)) }
    }

    private fun cancelForegroundRuntime() {
        if (foregroundResultPosted) return
        foregroundResultPosted = true
        runCatching {
            context.startService(
                android.content.Intent(context, RuntimeExecutionService::class.java)
                    .setAction(RuntimeExecutionService.ACTION_CANCELLED),
            )
        }.onFailure { context.stopService(android.content.Intent(context, RuntimeExecutionService::class.java)) }
    }

    suspend fun discoverModels(): List<com.jarves.mh.network.DiscoveredModel> = withContext(Dispatchers.IO) {
        if (!installer.isAgentInstalled(com.jarves.mh.model.AgentKind.OPENCODE)) {
            return@withContext fallbackZenModels()
        }
        runCatching {
            val installed = installer.installedRuntime()
            val workspace = File(installed.rootfs, "root")
            val process = installer.process(
                installed.proot,
                installed.rootfs,
                workspace,
                linkedMapOf("TERM" to "xterm-256color"),
                listOf(RuntimeInstaller.OPENCODE_GUEST_PATH, "models"),
            )
            val output = (process as? NativeSpawnProcess)?.outputFile?.readText().orEmpty()
            process.waitFor()
            val lines = output.lines()
                .map { it.trim() }
                .filter { it.isNotBlank() && !it.startsWith("No authenticated") && !it.startsWith("┌") && !it.startsWith("│") && !it.startsWith("└") && !it.startsWith("■") }
            if (lines.isNotEmpty()) {
                lines.map { modelId ->
                    val cleanName = modelId.removePrefix("opencode/")
                        .replace("-", " ")
                        .split(" ")
                        .joinToString(" ") { word -> word.replaceFirstChar { it.uppercase() } }
                    com.jarves.mh.network.DiscoveredModel(
                        id = modelId,
                        displayName = cleanName,
                        isFree = modelId.endsWith("-free"),
                    )
                }
            } else {
                fallbackZenModels()
            }
        }.getOrElse { fallbackZenModels() }
    }

    companion object {
        fun fallbackZenModels(): List<com.jarves.mh.network.DiscoveredModel> = listOf(
            com.jarves.mh.network.DiscoveredModel("opencode/ling-3.0-flash-fin-free", "Ling 3.0 Flash Fin (Free)", isFree = true),
            com.jarves.mh.network.DiscoveredModel("opencode/nemotron-3.5-lightning-free", "Nemotron 3.5 Lightning (Free)", isFree = true),
            com.jarves.mh.network.DiscoveredModel("opencode/nemotron-3-ultra-free", "Nemotron 3 Ultra (Free)", isFree = true),
            com.jarves.mh.network.DiscoveredModel("opencode/mimo-v2.6-flash-free", "Mimo v2.6 Flash (Free)", isFree = true),
            com.jarves.mh.network.DiscoveredModel("opencode/longcat-2.5-preview-free", "Longcat 2.5 Preview (Free)", isFree = true),
            com.jarves.mh.network.DiscoveredModel("opencode/muse-spark-1.3-contributor-free", "Muse Spark 1.3 (Free)", isFree = true),
            com.jarves.mh.network.DiscoveredModel("opencode/space-bunny-free", "Space Bunny (Free)", isFree = true),
            com.jarves.mh.network.DiscoveredModel("opencode/big-pickle", "Big Pickle (Free)", isFree = true),
        )
    }

    private class OpencodeSessionException(message: String) : IllegalStateException(message)
}
