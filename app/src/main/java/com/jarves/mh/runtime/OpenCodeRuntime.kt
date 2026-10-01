package com.jarves.mh.runtime

import android.content.Context
import androidx.core.content.ContextCompat
import com.jarves.mh.model.AgentKind
import com.jarves.mh.model.ChatMessage
import com.jarves.mh.model.ChangeItem
import com.jarves.mh.model.ProjectKind
import com.jarves.mh.model.ProviderKind
import com.jarves.mh.model.ProviderProfile
import com.jarves.mh.model.RuntimeEvent
import com.jarves.mh.model.ToolRequest
import com.jarves.mh.model.normalizeProviderBaseUrl
import com.jarves.mh.model.openRouterBaseUrl
import com.jarves.mh.model.openRouterModelSlug
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

internal data class OpenCodeLaunchConfig(val model: String, val environment: Map<String, String>)

internal object OpenCodeLaunchConfigBuilder {
    fun build(profile: ProviderProfile, apiKey: String): OpenCodeLaunchConfig {
        require(apiKey.isNotBlank()) { "An API key is required for OpenCode." }
        val selectedModel = profile.model.ifBlank { profile.kind.defaultModel }
        val (providerId, baseUrl, openAiCompatible, officialProvider) = when (profile.kind) {
            ProviderKind.LLM_ROUTER -> Quad("openrouter", openRouterBaseUrl(profile.resolvedBaseUrl), true, true)
            ProviderKind.OPENCODE_ZEN -> Quad("opencode", profile.resolvedBaseUrl, true, true)
            ProviderKind.NVIDIA_NIM -> Quad("nvidia", profile.resolvedBaseUrl, true, false)
            ProviderKind.CUSTOM -> {
                val openAi = profile.dshApi == "openai-completions" || profile.dshApi == "openai-responses"
                Quad("custom", normalizeProviderBaseUrl(profile.resolvedBaseUrl, openAi), openAi, false)
            }
            ProviderKind.DEEPSEEK -> Quad("deepseek", profile.resolvedBaseUrl, false, false)
            ProviderKind.KIMI -> Quad("kimi", profile.resolvedBaseUrl, false, false)
            ProviderKind.ANTHROPIC -> Quad("anthropic", profile.resolvedBaseUrl, false, false)
            ProviderKind.CLAUDE -> error("Claude subscription login is not supported by OpenCode.")
        }
        val modelId = if (profile.kind == ProviderKind.LLM_ROUTER) openRouterModelSlug(selectedModel) else selectedModel
        val options = JSONObject().put("apiKey", apiKey).put("baseURL", baseUrl)
        val modelConfig = JSONObject()
            .put("name", modelId)
            .put("limit", JSONObject().put("context", 200_000).put("output", 16_384))
        val provider = JSONObject()
            .put("name", profile.kind.title)
            .put("options", options)
            .put("models", JSONObject().put(modelId, modelConfig))
        if (!officialProvider) {
            provider.put("npm", if (openAiCompatible) "@ai-sdk/openai-compatible" else "@ai-sdk/anthropic")
        }
        val config = JSONObject()
            .put("\$schema", "https://opencode.ai/config.json")
            .put("model", "$providerId/$modelId")
            .put("permission", JSONObject().put("*", "allow"))
            .put("provider", JSONObject().put(providerId, provider))
        val environment = linkedMapOf(
            "OPENCODE_CONFIG_CONTENT" to config.toString(),
            "OPENCODE_AUTO_UPDATE" to "false",
            "DISABLE_AUTOUPDATER" to "1",
        )
        if (profile.kind == ProviderKind.LLM_ROUTER) {
            environment["OPENROUTER_API_KEY"] = apiKey
            environment["OPENAI_API_KEY"] = apiKey
            environment["OPENAI_API_BASE"] = baseUrl
        } else if (openAiCompatible) {
            environment["OPENAI_API_KEY"] = apiKey
            environment["OPENAI_API_BASE"] = baseUrl
        }
        return OpenCodeLaunchConfig("$providerId/$modelId", environment)
    }

    private data class Quad(val providerId: String, val baseUrl: String, val openAiCompatible: Boolean, val officialProvider: Boolean)
}

internal data class OpenCodeOutputEvent(val text: String? = null, val error: String? = null, val toolName: String? = null)

internal fun parseOpenCodeOutputLine(line: String): OpenCodeOutputEvent? {
    val root = runCatching { JSONObject(line) }.getOrNull() ?: return null
    val type = root.optString("type")
    val part = root.optJSONObject("part")
    return when (type) {
        "text", "text-delta" -> OpenCodeOutputEvent(text = part?.optString("text")?.takeIf(String::isNotBlank)
            ?: root.optString("text").takeIf(String::isNotBlank))
        "tool-invocation", "tool-start" -> OpenCodeOutputEvent(toolName = part?.optString("tool")?.ifBlank { null } ?: "Tool")
        "error" -> {
            val error = root.opt("error")
            OpenCodeOutputEvent(error = when (error) {
                is JSONObject -> error.optString("message").ifBlank { error.toString() }
                null, JSONObject.NULL -> root.optString("message")
                else -> error.toString()
            }.ifBlank { "OpenCode reported an error." })
        }
        else -> null
    }
}

class OpenCodeRuntime(
    private val context: Context,
    private val secretFor: (ProviderProfile) -> String?,
) : RuntimeBridge {
    private val installer = RuntimeInstaller(context)
    private val checkpoints = WorkspaceCheckpoints(context.filesDir)
    private val eventBus = MutableSharedFlow<RuntimeEvent>(extraBufferCapacity = 64)
    override val events: Flow<RuntimeEvent> = eventBus
    private val finished = ConcurrentHashMap.newKeySet<String>()
    @Volatile private var activeProcess: Process? = null
    @Volatile private var activeSessionId: String? = null
    @Volatile private var activeProjectSlug: String? = null
    @Volatile private var userStopRequested = false
    @Volatile private var foregroundResultPosted = false

    override suspend fun startSession(
        projectId: String,
        projectSlug: String,
        projectKind: ProjectKind,
        prompt: String,
        conversationHistory: List<ChatMessage>,
        provider: ProviderProfile,
    ): String = withContext(Dispatchers.IO + NonCancellable) {
        val sessionId = UUID.randomUUID().toString()
        finished.remove(sessionId)
        activeSessionId = sessionId
        activeProjectSlug = projectSlug
        userStopRequested = false
        foregroundResultPosted = false
        eventBus.emit(RuntimeEvent.SessionStarted(sessionId))
        val secret = secretFor(provider).orEmpty()
        if (secret.isBlank()) {
            emitFailure(sessionId, "No API key is saved for ${provider.kind.title}.")
            return@withContext sessionId
        }
        runCatching {
            startForeground(projectSlug)
            val installed = installer.installedRuntime()
            check(installer.isAgentInstalled(AgentKind.OPENCODE)) {
                "OpenCode CLI is not installed. Open Settings → Coding agent to install it."
            }
            val workspace = checkpoints.ensureWorkspace(projectId)
            checkpoints.createCheckpoint(projectId, workspace)
            val before = checkpoints.snapshot(workspace)
            val config = OpenCodeLaunchConfigBuilder.build(provider, secret)
            RuntimeTaskController.stopAction = {
                userStopRequested = true
                activeProcess?.let { running ->
                    Thread {
                        running.destroy()
                        Thread.sleep(500)
                        if (running.isAlive) running.destroyForcibly()
                    }.start()
                }
            }
            val guestWorkspacePath = "/workspace/$projectSlug"
            val contextPrompt = buildContextPrompt(prompt, conversationHistory)
            val command = listOf(
                RuntimeInstaller.OPENCODE_GUEST_PATH,
                "run",
                "--format",
                "json",
                "--model",
                config.model,
                contextPrompt,
            )
            val outputFile = File(context.cacheDir, "opencode-$sessionId.log")
            val process = installer.process(
                installed.proot,
                installed.rootfs,
                workspace,
                config.environment,
                command,
                guestWorkspacePath = guestWorkspacePath,
                outputFile = outputFile,
            )
            activeProcess = process
            var offset = 0L
            val pending = StringBuilder()
            var failure: String? = null
            while (process.isAlive || outputFile.length() > offset) {
                val available = outputFile.length() - offset
                if (available <= 0) {
                    delay(50)
                    continue
                }
                val bytes = ByteArray(minOf(available, 16L * 1024).toInt())
                val count = RandomAccessFile(outputFile, "r").use { file ->
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
                    failure = consumeOutput(sessionId, line) ?: failure
                    if (failure != null) process.destroyForcibly()
                    newline = pending.indexOf("\n")
                }
            }
            pending.toString().trim().takeIf(String::isNotBlank)?.let {
                failure = consumeOutput(sessionId, it) ?: failure
                if (failure != null) process.destroyForcibly()
            }
            val exit = process.waitFor()
            activeProcess = null
            outputFile.delete()
            val changed = checkpoints.changedFiles(workspace, before)
            if (changed.isNotEmpty()) {
                checkpoints.saveChangedPaths(projectId, changed)
                eventBus.emit(RuntimeEvent.FilesChanged(
                    sessionId,
                    checkpoints.buildChangeDetails(projectId, workspace, checkpoints.readChangedPaths(projectId)),
                ))
            }
            if (userStopRequested) {
                emitFailure(sessionId, "Stopped by user")
                cancelForeground()
            } else if (exit == 0 && failure == null) {
                emitCompleted(sessionId)
                finishForeground(completed = true, projectSlug, "OpenCode finished the task in $projectSlug.")
            } else {
                throw IllegalStateException(failure ?: "OpenCode stopped with exit code $exit")
            }
        }.onFailure { error ->
            val message = ProviderRuntimeErrorDetector.detect(error.message.orEmpty())
                ?: error.message?.take(500)
                ?: "OpenCode could not start."
            emitFailure(sessionId, message)
            if (userStopRequested) cancelForeground() else finishForeground(completed = false, projectSlug, message)
        }
        activeProcess = null
        activeSessionId = null
        RuntimeTaskController.stopAction = null
        sessionId
    }

    private fun consumeOutput(sessionId: String, line: String): String? {
        ProviderRuntimeErrorDetector.detect(line)?.let { return it }
        val event = parseOpenCodeOutputLine(line) ?: return null
        event.text?.let { eventBus.tryEmit(RuntimeEvent.AssistantDelta(sessionId, it)) }
        event.toolName?.let { eventBus.tryEmit(RuntimeEvent.ToolStarted(sessionId, it, it)) }
        return event.error?.let { ProviderRuntimeErrorDetector.detect(it) ?: it }
    }

    private fun buildContextPrompt(prompt: String, history: List<ChatMessage>): String = buildString {
        history.filterNot { it.text.startsWith("Failed to") || it.text.startsWith("Error:") }
            .recentWithinCharacterBudget(40_000)
            .forEach { message ->
                append(if (message.fromUser) "User: " else "Assistant: ")
                appendLine(message.text)
            }
        appendLine("User: $prompt")
    }

    private suspend fun emitFailure(sessionId: String, message: String) {
        if (finished.add(sessionId)) eventBus.emit(RuntimeEvent.SessionFailed(sessionId, message))
    }

    private suspend fun emitCompleted(sessionId: String) {
        if (finished.add(sessionId)) eventBus.emit(RuntimeEvent.SessionCompleted(sessionId))
    }

    override suspend fun respondToApproval(request: ToolRequest, approved: Boolean) = Unit

    override suspend fun stopSession(sessionId: String) = withContext(Dispatchers.IO) {
        if (activeSessionId == sessionId) {
            userStopRequested = true
            activeProcess?.destroy()
            delay(500)
            if (activeProcess?.isAlive == true) activeProcess?.destroyForcibly()
        }
    }

    override suspend fun stopActiveSession() {
        activeSessionId?.let { stopSession(it) }
    }

    override suspend fun undoLastChanges(projectId: String): Boolean = withContext(Dispatchers.IO) {
        val checkpoint = checkpoints.checkpointDir(projectId)
        val backup = File(checkpoint, "project")
        if (!backup.isDirectory || !File(checkpoint, "changes.json").isFile) return@withContext false
        val workspace = checkpoints.ensureWorkspace(projectId)
        val paths = checkpoints.readChangedPaths(projectId).filterNot(checkpoints::isInternalRuntimePath)
        paths.forEach { path ->
            val target = checkpoints.safeWorkspaceFile(workspace, path)
            val original = checkpoints.safeWorkspaceFile(backup, path)
            if (original.isFile) {
                target.parentFile?.mkdirs()
                original.copyTo(target, overwrite = true)
            } else if (target.isFile) target.delete()
        }
        checkpoint.deleteRecursively()
        paths.isNotEmpty()
    }

    override suspend fun acceptLastChanges(projectId: String) {
        withContext(Dispatchers.IO) { checkpoints.checkpointDir(projectId).deleteRecursively() }
    }

    override suspend fun loadPendingChanges(projectId: String): List<ChangeItem> = withContext(Dispatchers.IO) {
        val workspace = checkpoints.ensureWorkspace(projectId)
        val paths = checkpoints.readChangedPaths(projectId).filterNot(checkpoints::isInternalRuntimePath)
        if (paths.isEmpty()) emptyList() else checkpoints.buildChangeDetails(projectId, workspace, paths)
    }

    override suspend fun undoFileChange(projectId: String, path: String): Boolean = withContext(Dispatchers.IO) {
        if (checkpoints.isInternalRuntimePath(path) || path !in checkpoints.readChangedPaths(projectId)) return@withContext false
        val workspace = checkpoints.ensureWorkspace(projectId)
        val backup = File(checkpoints.checkpointDir(projectId), "project")
        val target = checkpoints.safeWorkspaceFile(workspace, path)
        val original = checkpoints.safeWorkspaceFile(backup, path)
        if (original.isFile) {
            target.parentFile?.mkdirs()
            original.copyTo(target, overwrite = true)
        } else if (target.isFile) target.delete()
        checkpoints.removeChangedPath(projectId, path)
        true
    }

    override suspend fun acceptFileChange(projectId: String, path: String): Boolean = withContext(Dispatchers.IO) {
        if (checkpoints.isInternalRuntimePath(path) || path !in checkpoints.readChangedPaths(projectId)) return@withContext false
        val workspace = checkpoints.ensureWorkspace(projectId)
        val backup = File(checkpoints.checkpointDir(projectId), "project")
        val current = checkpoints.safeWorkspaceFile(workspace, path)
        val baseline = checkpoints.safeWorkspaceFile(backup, path)
        if (current.isFile) {
            baseline.parentFile?.mkdirs()
            current.copyTo(baseline, overwrite = true)
        } else if (baseline.isFile) baseline.delete()
        checkpoints.removeChangedPath(projectId, path)
        true
    }

    private fun startForeground(projectName: String) {
        ContextCompat.startForegroundService(
            context,
            android.content.Intent(context, RuntimeExecutionService::class.java)
                .setAction(RuntimeExecutionService.ACTION_START)
                .putExtra(RuntimeExecutionService.EXTRA_PROJECT_NAME, projectName),
        )
    }

    private fun finishForeground(completed: Boolean, projectName: String, detail: String) {
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

    private fun cancelForeground() {
        if (foregroundResultPosted) return
        foregroundResultPosted = true
        runCatching {
            context.startService(
                android.content.Intent(context, RuntimeExecutionService::class.java)
                    .setAction(RuntimeExecutionService.ACTION_CANCELLED),
            )
        }.onFailure { context.stopService(android.content.Intent(context, RuntimeExecutionService::class.java)) }
    }

}
