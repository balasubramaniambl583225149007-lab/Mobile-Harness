package com.jarves.mh.ui

import com.jarves.mh.model.EditorTab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorDiagnosticsAndBridgeTest {

    @Test
    fun bridgeDynamicallyReturnsLatestActiveTabContentAndName() {
        var activeTab: EditorTab? = null
        val logs = mutableListOf<String>()

        val bridge = EditorWebBridge(
            getActiveTab = { activeTab },
            onContentChange = { _, _ -> },
            onSaveRequested = {},
            onSyncRequested = { _, _ -> },
            onReady = {},
            onLog = { logs.add(it) },
        )

        // When activeTab is null initially, bridge returns empty string
        assertEquals("", bridge.getActiveTabContent())
        assertEquals("", bridge.getActiveTabName())

        // When activeTab changes to File A
        activeTab = EditorTab(
            path = "src/main.py",
            name = "main.py",
            content = "print('hello')",
            diskModifiedTime = 1000L,
        )

        assertEquals("print('hello')", bridge.getActiveTabContent())
        assertEquals("main.py", bridge.getActiveTabName())

        // When activeTab switches to File B
        activeTab = EditorTab(
            path = "README.md",
            name = "README.md",
            content = "# Project",
            diskModifiedTime = 2000L,
        )

        assertEquals("# Project", bridge.getActiveTabContent())
        assertEquals("README.md", bridge.getActiveTabName())

        // Verify bridge logging occurred
        assertTrue(logs.any { it.contains("getActiveTabContent") })
        assertTrue(logs.any { it.contains("getActiveTabName") })
    }

    @Test
    fun bridgeCallbacksInvokeExpectedHandlers() {
        var savedContent = ""
        var syncedContent = ""
        var syncedModified = false
        var contentChangedFile = ""
        var contentChangedModified = false
        var editorReadyCalled = false

        val bridge = EditorWebBridge(
            getActiveTab = { null },
            onContentChange = { file, modified ->
                contentChangedFile = file
                contentChangedModified = modified
            },
            onSaveRequested = { savedContent = it },
            onSyncRequested = { content, modified ->
                syncedContent = content
                syncedModified = modified
            },
            onReady = { editorReadyCalled = true },
        )

        bridge.onEditorReady()
        assertTrue(editorReadyCalled)

        bridge.onSave("test-save-content")
        assertEquals("test-save-content", savedContent)

        bridge.onSyncContent("test-sync-content", true)
        assertEquals("test-sync-content", syncedContent)
        assertTrue(syncedModified)

        bridge.onContentChanged("test.txt", true)
        assertEquals("test.txt", contentChangedFile)
        assertTrue(contentChangedModified)
    }

    @Test
    fun diagnosticsFormattedStringContainsAllSections() {
        val diagnostics = EditorDiagnosticsInfo(
            indexHtmlExists = true,
            indexHtmlBytes = 1933L,
            bundleJsExists = true,
            bundleJsBytes = 770048L,
            bridgeAttached = true,
            pageFinished = true,
            editorReady = true,
            activeTabPath = "app/src/main.kt",
            activeTabName = "main.kt",
            activeTabContentLength = 450,
            isModified = true,
            isPartiallyLoaded = false,
            logs = listOf("[WebView] Page finished: file:///android_asset/editor/index.html", "[Bridge] onEditorReady() received from JS"),
        )

        val formatted = diagnostics.toFormattedString()

        assertTrue(formatted.contains("=== Editor Diagnostics ==="))
        assertTrue(formatted.contains("editor/index.html: 1933 bytes"))
        assertTrue(formatted.contains("editor/editor.bundle.js: 752 KB (770048 bytes)"))
        assertTrue(formatted.contains("AndroidBridge Attached: true"))
        assertTrue(formatted.contains("Page Finished: true"))
        assertTrue(formatted.contains("Editor Ready: true"))
        assertTrue(formatted.contains("Path: app/src/main.kt"))
        assertTrue(formatted.contains("Name: main.kt"))
        assertTrue(formatted.contains("Content Length: 450 chars"))
        assertTrue(formatted.contains("Modified: true"))
        assertTrue(formatted.contains("Partial Load: false"))
        assertTrue(formatted.contains("Recent Logs (2):"))
        assertTrue(formatted.contains("[Bridge] onEditorReady() received from JS"))
    }

    @Test
    fun diagnosticsHandlesMissingAssetsAndNullActiveTab() {
        val diagnostics = EditorDiagnosticsInfo(
            indexHtmlExists = false,
            indexHtmlBytes = 0L,
            bundleJsExists = false,
            bundleJsBytes = 0L,
            bridgeAttached = false,
            pageFinished = false,
            editorReady = false,
            activeTabPath = null,
            activeTabName = null,
            activeTabContentLength = 0,
            isModified = false,
            isPartiallyLoaded = false,
            logs = emptyList(),
        )

        val formatted = diagnostics.toFormattedString()

        assertTrue(formatted.contains("editor/index.html: NOT FOUND"))
        assertTrue(formatted.contains("editor/editor.bundle.js: NOT FOUND"))
        assertTrue(formatted.contains("Path: (None)"))
        assertTrue(formatted.contains("Name: (None)"))
        assertTrue(formatted.contains("Recent Logs (0):"))
        assertTrue(formatted.contains("(None)"))
    }
}
