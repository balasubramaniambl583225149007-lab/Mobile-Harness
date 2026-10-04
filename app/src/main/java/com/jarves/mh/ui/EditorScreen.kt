package com.jarves.mh.ui

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.NoteAdd
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.DataObject
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.SaveAs
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.jarves.mh.model.EditorTab
import com.jarves.mh.model.WorkspaceEntry
import com.jarves.mh.ui.theme.PocketGreen
import com.jarves.mh.ui.theme.PocketOrange
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val VSCODE_BG = Color(0xFF1E1E1E)
private val VSCODE_SIDEBAR = Color(0xFF252526)
private val VSCODE_TAB_ACTIVE = Color(0xFF1E1E1E)
private val VSCODE_TAB_INACTIVE = Color(0xFF2D2D2D)
private val VSCODE_BORDER = Color(0xFF333333)
private val VSCODE_TEXT = Color(0xFFCCCCCC)
private val VSCODE_TEXT_MUTED = Color(0xFF888888)
private val VSCODE_ACCENT = Color(0xFF007ACC)
private val VSCODE_KEY_BG = Color(0xFF2A2D2E)

internal class EditorWebBridge(
    private val getActiveTab: () -> EditorTab?,
    private val onContentChange: (filename: String, isModified: Boolean) -> Unit,
    private val onSaveRequested: (content: String) -> Unit,
    private val onSyncRequested: (content: String, isModified: Boolean) -> Unit,
    private val onReady: () -> Unit,
    private val onLog: ((String) -> Unit)? = null,
) {
    @JavascriptInterface
    fun getActiveTabContent(): String {
        val tab = getActiveTab()
        val content = tab?.content ?: ""
        onLog?.invoke("getActiveTabContent() returning ${content.length} chars for '${tab?.name}'")
        return content
    }

    @JavascriptInterface
    fun getActiveTabName(): String {
        val name = getActiveTab()?.name ?: ""
        onLog?.invoke("getActiveTabName() returning '$name'")
        return name
    }

    @JavascriptInterface
    fun onContentChanged(filename: String, isModified: Boolean) {
        onLog?.invoke("onContentChanged($filename, modified=$isModified)")
        onContentChange(filename, isModified)
    }

    @JavascriptInterface
    fun onSave(content: String) {
        onLog?.invoke("onSave(chars=${content.length})")
        onSaveRequested(content)
    }

    @JavascriptInterface
    fun onSyncContent(content: String, isModified: Boolean) {
        onLog?.invoke("onSyncContent(chars=${content.length}, modified=$isModified)")
        onSyncRequested(content, isModified)
    }

    @JavascriptInterface
    fun onEditorReady() {
        onLog?.invoke("onEditorReady() received from JS")
        onReady()
    }

    @JavascriptInterface
    fun onCursorActivity(line: Int, col: Int) {}

    @JavascriptInterface
    fun onFontSizeChanged(size: Float) {}
}

data class EditorDiagnosticsInfo(
    val indexHtmlExists: Boolean,
    val indexHtmlBytes: Long,
    val bundleJsExists: Boolean,
    val bundleJsBytes: Long,
    val bridgeAttached: Boolean,
    val pageFinished: Boolean,
    val editorReady: Boolean,
    val activeTabPath: String?,
    val activeTabName: String?,
    val activeTabContentLength: Int,
    val isModified: Boolean,
    val isPartiallyLoaded: Boolean,
    val logs: List<String>,
) {
    fun toFormattedString(): String {
        return buildString {
            appendLine("=== Editor Diagnostics ===")
            appendLine("Assets:")
            appendLine("  editor/index.html: ${if (indexHtmlExists) "$indexHtmlBytes bytes" else "NOT FOUND"}")
            appendLine("  editor/editor.bundle.js: ${if (bundleJsExists) "${bundleJsBytes / 1024} KB ($bundleJsBytes bytes)" else "NOT FOUND"}")
            appendLine("Status:")
            appendLine("  AndroidBridge Attached: $bridgeAttached")
            appendLine("  Page Finished: $pageFinished")
            appendLine("  Editor Ready: $editorReady")
            appendLine("Active Tab:")
            appendLine("  Path: ${activeTabPath ?: "(None)"}")
            appendLine("  Name: ${activeTabName ?: "(None)"}")
            appendLine("  Content Length: $activeTabContentLength chars")
            appendLine("  Modified: $isModified")
            appendLine("  Partial Load: $isPartiallyLoaded")
            appendLine("Recent Logs (${logs.size}):")
            if (logs.isEmpty()) {
                appendLine("  (None)")
            } else {
                logs.takeLast(30).forEach { appendLine("  $it") }
            }
        }
    }
}

internal fun checkEditorDiagnostics(
    context: Context,
    openTabs: List<EditorTab>,
    activeTabPath: String?,
    editorReady: Boolean,
    pageFinished: Boolean,
    logs: List<String>,
): EditorDiagnosticsInfo {
    var indexExists = false
    var indexBytes = 0L
    var bundleExists = false
    var bundleBytes = 0L

    try {
        context.assets.open("editor/index.html").use { input ->
            indexBytes = input.readBytes().size.toLong()
            indexExists = true
        }
    } catch (_: Exception) {}

    try {
        context.assets.open("editor/editor.bundle.js").use { input ->
            bundleBytes = input.readBytes().size.toLong()
            bundleExists = true
        }
    } catch (_: Exception) {}

    val activeTab = openTabs.find { it.path == activeTabPath }

    return EditorDiagnosticsInfo(
        indexHtmlExists = indexExists,
        indexHtmlBytes = indexBytes,
        bundleJsExists = bundleExists,
        bundleJsBytes = bundleBytes,
        bridgeAttached = true,
        pageFinished = pageFinished,
        editorReady = editorReady,
        activeTabPath = activeTab?.path,
        activeTabName = activeTab?.name,
        activeTabContentLength = activeTab?.content?.length ?: 0,
        isModified = activeTab?.isModified == true,
        isPartiallyLoaded = activeTab?.isPartiallyLoaded == true,
        logs = logs,
    )
}

private sealed interface EditorCrudAction {
    data class NewFile(val parentDir: String) : EditorCrudAction
    data class NewFolder(val parentDir: String) : EditorCrudAction
    data class Rename(val path: String, val currentName: String) : EditorCrudAction
    data class Delete(val path: String, val name: String, val isDirectory: Boolean) : EditorCrudAction
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(
    files: List<WorkspaceEntry>,
    openTabs: List<EditorTab>,
    activeTabPath: String?,
    diskConflictPath: String?,
    onOpenFile: (String) -> Unit,
    onSelectTab: (String) -> Unit,
    onCloseTab: (String) -> Unit,
    onUpdateTabContent: (path: String, content: String, isModified: Boolean) -> Unit,
    onSaveTab: (path: String, content: String) -> Unit,
    onSaveAll: (activePath: String?, activeContent: String?) -> Unit,
    onReloadFromDisk: (String) -> Unit,
    onKeepEditorChanges: (String) -> Unit,
    onLoadFullFile: (String) -> Unit,
    onCreateFile: (parentDir: String, name: String) -> Unit,
    onCreateFolder: (parentDir: String, name: String) -> Unit,
    onRenameEntry: (path: String, newName: String) -> Unit,
    onDeleteEntry: (path: String) -> Unit,
    onRefreshFiles: () -> Unit,
    onBackToChat: () -> Unit,
    onImportFiles: ((parentDir: String) -> Unit)? = null,
    fileImporting: Boolean = false,
    fileImportProgress: Float? = null,
    fileImportMessage: String? = null,
    onCancelFileImport: (() -> Unit)? = null,
) {
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val activeTab = openTabs.find { it.path == activeTabPath }
    val currentTabs by rememberUpdatedState(openTabs)
    val currentActiveTabPath by rememberUpdatedState(activeTabPath)
    val currentActiveTab by rememberUpdatedState<EditorTab?>(activeTab)
    val currentOnUpdateTabContent by rememberUpdatedState(onUpdateTabContent)
    val currentOnSaveTab by rememberUpdatedState(onSaveTab)

    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var editorReady by remember { mutableStateOf(false) }
    var pageFinished by remember { mutableStateOf(false) }
    var showFallbackEditor by remember { mutableStateOf(false) }
    var showDiagnosticsDialog by remember { mutableStateOf(false) }
    val consoleLogs = remember { mutableStateListOf<String>() }

    fun addLog(msg: String) {
        if (consoleLogs.size > 100) {
            consoleLogs.removeAt(0)
        }
        consoleLogs.add(msg)
    }

    // Dialog states
    var activeCrudAction by remember { mutableStateOf<EditorCrudAction?>(null) }
    var tabToCloseUnsaved by remember { mutableStateOf<EditorTab?>(null) }
    var crudInputText by remember { mutableStateOf("") }

    // Folder collapse map: key is path, value is true if collapsed
    val collapsedDirs = remember { mutableStateMapOf<String, Boolean>() }
    var fileSearchQuery by remember { mutableStateOf("") }

    // Bridge for WebView
    val bridge = remember {
        EditorWebBridge(
            getActiveTab = { currentActiveTab },
            onContentChange = { filename, isModified ->
                currentActiveTabPath?.let { path ->
                    currentActiveTab?.let { tab ->
                        currentOnUpdateTabContent(path, tab.content, isModified)
                    }
                }
            },
            onSaveRequested = { content ->
                currentActiveTabPath?.let { path ->
                    currentOnSaveTab(path, content)
                }
            },
            onSyncRequested = { content, isModified ->
                currentActiveTabPath?.let { path ->
                    currentOnUpdateTabContent(path, content, isModified)
                }
            },
            onReady = {
                editorReady = true
                webViewRef?.post {
                    webViewRef?.evaluateJavascript("window.loadTabFromAndroid()", null)
                }
            },
            onLog = { addLog("[Bridge] $it") },
        )
    }

    // When active tab changes and editor is ready, tell JS to reload content
    LaunchedEffect(activeTabPath, editorReady) {
        if (editorReady && activeTabPath != null) {
            webViewRef?.evaluateJavascript("window.loadTabFromAndroid()", null)
        }
    }

    // 3-second fallback safety net timer
    LaunchedEffect(activeTabPath, editorReady) {
        if (activeTabPath != null && !editorReady) {
            delay(3000L)
            if (!editorReady) {
                showFallbackEditor = true
                addLog("[Safety Net] 3-second timeout reached without onEditorReady; fallback editor active")
            }
        } else if (editorReady) {
            showFallbackEditor = false
        }
    }

    BackHandler(enabled = drawerState.isOpen) {
        scope.launch { drawerState.close() }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(
                drawerContainerColor = VSCODE_SIDEBAR,
                drawerContentColor = VSCODE_TEXT,
                modifier = Modifier.width(300.dp).fillMaxHeight(),
            ) {
                EditorFileTreeSidebar(
                    files = files,
                    searchQuery = fileSearchQuery,
                    onSearchQueryChange = { fileSearchQuery = it },
                    collapsedDirs = collapsedDirs,
                    onToggleDir = { path ->
                        collapsedDirs[path] = !(collapsedDirs[path] ?: false)
                    },
                    onSelectFile = { path ->
                        onOpenFile(path)
                        scope.launch { drawerState.close() }
                    },
                    onNewFile = { parentDir ->
                        crudInputText = ""
                        activeCrudAction = EditorCrudAction.NewFile(parentDir)
                    },
                    onNewFolder = { parentDir ->
                        crudInputText = ""
                        activeCrudAction = EditorCrudAction.NewFolder(parentDir)
                    },
                    onImportFiles = { parentDir ->
                        onImportFiles?.invoke(parentDir)
                    },
                    fileImporting = fileImporting,
                    fileImportProgress = fileImportProgress,
                    fileImportMessage = fileImportMessage,
                    onCancelFileImport = onCancelFileImport,
                    onRename = { path, name ->
                        crudInputText = name
                        activeCrudAction = EditorCrudAction.Rename(path, name)
                    },
                    onDelete = { path, name, isDir ->
                        activeCrudAction = EditorCrudAction.Delete(path, name, isDir)
                    },
                    onRefresh = onRefreshFiles,
                )
            }
        },
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    activeTab?.name ?: "Editor",
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    color = Color.White,
                                )
                                if (activeTab?.isModified == true) {
                                    Text(" •", color = PocketOrange, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                                }
                            }
                            if (activeTab != null) {
                                Text(
                                    activeTab.path,
                                    fontSize = 11.sp,
                                    color = VSCODE_TEXT_MUTED,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Default.Menu, contentDescription = "Open file tree", tint = Color.White)
                        }
                    },
                    actions = {
                        IconButton(
                            onClick = { webViewRef?.evaluateJavascript("window.editorBridge.undo()", null) },
                            enabled = activeTab != null && !showFallbackEditor,
                        ) {
                            Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "Undo", tint = if (activeTab != null && !showFallbackEditor) Color.White else VSCODE_TEXT_MUTED)
                        }
                        IconButton(
                            onClick = { webViewRef?.evaluateJavascript("window.editorBridge.redo()", null) },
                            enabled = activeTab != null && !showFallbackEditor,
                        ) {
                            Icon(Icons.AutoMirrored.Filled.Redo, contentDescription = "Redo", tint = if (activeTab != null && !showFallbackEditor) Color.White else VSCODE_TEXT_MUTED)
                        }
                        IconButton(
                            onClick = { webViewRef?.evaluateJavascript("window.editorBridge.openSearch()", null) },
                            enabled = activeTab != null && !showFallbackEditor,
                        ) {
                            Icon(Icons.Default.Search, contentDescription = "Search", tint = if (activeTab != null && !showFallbackEditor) Color.White else VSCODE_TEXT_MUTED)
                        }
                        IconButton(
                            onClick = {
                                if (showFallbackEditor) {
                                    activeTab?.let { onSaveTab(it.path, it.content) }
                                } else {
                                    webViewRef?.evaluateJavascript("window.requestSave()", null)
                                }
                            },
                            enabled = activeTab != null,
                        ) {
                            Icon(Icons.Default.Save, contentDescription = "Save", tint = if (activeTab?.isModified == true) PocketOrange else Color.White)
                        }
                        IconButton(
                            onClick = {
                                if (showFallbackEditor) {
                                    onSaveAll(activeTab?.path, activeTab?.content)
                                } else if (webViewRef != null && activeTab != null) {
                                    webViewRef?.evaluateJavascript("window.editorBridge ? window.editorBridge.getContent() : null") { result ->
                                        val latestContent = if (!result.isNullOrBlank() && result != "null") {
                                            runCatching { org.json.JSONTokener(result).nextValue() as? String }.getOrNull() ?: activeTab.content
                                        } else {
                                            activeTab.content
                                        }
                                        onSaveAll(activeTab.path, latestContent)
                                    }
                                } else {
                                    onSaveAll(activeTab?.path, activeTab?.content)
                                }
                            },
                            enabled = openTabs.isNotEmpty(),
                        ) {
                            Icon(Icons.Default.SaveAs, contentDescription = "Save All", tint = Color.White)
                        }
                        IconButton(
                            onClick = { showDiagnosticsDialog = true },
                        ) {
                            Icon(Icons.Default.Info, contentDescription = "Diagnostics", tint = Color.White)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = VSCODE_SIDEBAR,
                        titleContentColor = Color.White,
                    ),
                )
            },
            containerColor = VSCODE_BG,
        ) { paddingValues ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
            ) {
                // Open Files Tabs Row
                if (openTabs.isNotEmpty()) {
                    EditorTabsRow(
                        tabs = openTabs,
                        activeTabPath = activeTabPath,
                        onSelectTab = { path ->
                            webViewRef?.evaluateJavascript("window.syncContentToAndroid()", null)
                            onSelectTab(path)
                        },
                        onCloseTab = { tab ->
                            if (tab.isModified) {
                                tabToCloseUnsaved = tab
                            } else {
                                onCloseTab(tab.path)
                            }
                        },
                    )
                }

                // External Modification / Disk Conflict Banner
                if (activeTab != null && diskConflictPath == activeTab.path) {
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF3B2A1A)),
                        shape = RoundedCornerShape(6.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Default.Warning, contentDescription = null, tint = PocketOrange, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "File changed on disk. Reload or keep your changes?",
                                color = Color(0xFFFFDDCC),
                                fontSize = 12.sp,
                                modifier = Modifier.weight(1f),
                            )
                            Spacer(Modifier.width(4.dp))
                            TextButton(onClick = { onReloadFromDisk(activeTab.path) }) {
                                Text("Reload", color = PocketOrange, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            }
                            TextButton(onClick = { onKeepEditorChanges(activeTab.path) }) {
                                Text("Keep", color = Color.White, fontSize = 12.sp)
                            }
                        }
                    }
                }

                // Partial File Load Banner (>1 MB)
                if (activeTab?.isPartiallyLoaded == true) {
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF1A2B3B)),
                        shape = RoundedCornerShape(6.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Default.Description, contentDescription = null, tint = VSCODE_ACCENT, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "First 1 MB loaded (${activeTab.totalSizeBytes / 1024} KB total).",
                                color = Color(0xFFCCE5FF),
                                fontSize = 12.sp,
                                modifier = Modifier.weight(1f),
                            )
                            Spacer(Modifier.width(4.dp))
                            TextButton(onClick = { onLoadFullFile(activeTab.path) }) {
                                Text("Load Full File", color = Color(0xFF80BFFF), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            }
                        }
                    }
                }

                // Fallback Safety Net Banner (when editor failed to load after 3s)
                if (showFallbackEditor && activeTab != null) {
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF4A2A18)),
                        shape = RoundedCornerShape(6.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Default.Warning, contentDescription = null, tint = PocketOrange, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "Editor failed to load. Tap to retry",
                                color = Color(0xFFFFD1B3),
                                fontSize = 12.sp,
                                modifier = Modifier.weight(1f),
                            )
                            Spacer(Modifier.width(4.dp))
                            TextButton(
                                onClick = {
                                    showFallbackEditor = false
                                    editorReady = false
                                    pageFinished = false
                                    webViewRef?.reload()
                                },
                            ) {
                                Text("Retry", color = PocketOrange, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            }
                            TextButton(onClick = { showDiagnosticsDialog = true }) {
                                Text("Diagnostics", color = VSCODE_TEXT, fontSize = 12.sp)
                            }
                        }
                    }
                }

                // Main Editor Area or Empty State
                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    if (openTabs.isEmpty()) {
                        Column(
                            modifier = Modifier.fillMaxSize().padding(32.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Icon(Icons.Default.Code, contentDescription = null, tint = VSCODE_TEXT_MUTED, modifier = Modifier.size(64.dp))
                            Spacer(Modifier.height(16.dp))
                            Text("No files open", color = VSCODE_TEXT, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                            Spacer(Modifier.height(8.dp))
                            Text("Open a file from the explorer sidebar to edit", color = VSCODE_TEXT_MUTED, fontSize = 13.sp)
                            Spacer(Modifier.height(16.dp))
                            Button(
                                onClick = { scope.launch { drawerState.open() } },
                                colors = ButtonDefaults.buttonColors(containerColor = VSCODE_ACCENT),
                            ) {
                                Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("Open Explorer")
                            }
                        }
                    } else if (showFallbackEditor && activeTab != null) {
                        NativeFallbackEditor(
                            content = activeTab.content,
                            onContentChange = { newText ->
                                onUpdateTabContent(activeTab.path, newText, true)
                            },
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        CodeMirrorWebView(
                            bridge = bridge,
                            onWebViewCreated = { webViewRef = it },
                            onConsoleLog = { addLog(it) },
                            onPageFinishedState = { pageFinished = it },
                        )
                    }
                }

                // Extra Keys Row above keyboard
                if (openTabs.isNotEmpty()) {
                    EditorExtraKeysRow(
                        onInsertText = { text ->
                            if (showFallbackEditor) {
                                activeTab?.let { tab ->
                                    onUpdateTabContent(tab.path, tab.content + text, true)
                                }
                            } else {
                                webViewRef?.evaluateJavascript("window.editorBridge.insertText(${org.json.JSONObject.quote(text)})", null)
                            }
                        },
                        onIndent = {
                            if (showFallbackEditor) {
                                activeTab?.let { tab ->
                                    onUpdateTabContent(tab.path, tab.content + "    ", true)
                                }
                            } else {
                                webViewRef?.evaluateJavascript("window.editorBridge.indent()", null)
                            }
                        },
                        onMoveCursor = { direction ->
                            if (!showFallbackEditor) {
                                webViewRef?.evaluateJavascript("window.editorBridge.moveCursor('$direction')", null)
                            }
                        },
                    )
                }
            }
        }
    }

    // CRUD Dialogs
    activeCrudAction?.let { action ->
        when (action) {
            is EditorCrudAction.NewFile -> {
                AlertDialog(
                    onDismissRequest = { activeCrudAction = null },
                    title = { Text("New File") },
                    text = {
                        Column {
                            Text(
                                "Location: ${action.parentDir.ifEmpty { "project root" }}",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(8.dp))
                            OutlinedTextField(
                                value = crudInputText,
                                onValueChange = { crudInputText = it },
                                label = { Text("File name (e.g. main.py)") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    },
                    confirmButton = {
                        Button(
                            onClick = {
                                onCreateFile(action.parentDir, crudInputText)
                                activeCrudAction = null
                            },
                            enabled = crudInputText.isNotBlank(),
                        ) {
                            Text("Create")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { activeCrudAction = null }) {
                            Text("Cancel")
                        }
                    },
                )
            }
            is EditorCrudAction.NewFolder -> {
                AlertDialog(
                    onDismissRequest = { activeCrudAction = null },
                    title = { Text("New Folder") },
                    text = {
                        Column {
                            Text(
                                "Location: ${action.parentDir.ifEmpty { "project root" }}",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(8.dp))
                            OutlinedTextField(
                                value = crudInputText,
                                onValueChange = { crudInputText = it },
                                label = { Text("Folder name") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    },
                    confirmButton = {
                        Button(
                            onClick = {
                                onCreateFolder(action.parentDir, crudInputText)
                                activeCrudAction = null
                            },
                            enabled = crudInputText.isNotBlank(),
                        ) {
                            Text("Create")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { activeCrudAction = null }) {
                            Text("Cancel")
                        }
                    },
                )
            }
            is EditorCrudAction.Rename -> {
                AlertDialog(
                    onDismissRequest = { activeCrudAction = null },
                    title = { Text("Rename") },
                    text = {
                        Column {
                            Text("Current: ${action.currentName}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(8.dp))
                            OutlinedTextField(
                                value = crudInputText,
                                onValueChange = { crudInputText = it },
                                label = { Text("New name") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    },
                    confirmButton = {
                        Button(
                            onClick = {
                                onRenameEntry(action.path, crudInputText)
                                activeCrudAction = null
                            },
                            enabled = crudInputText.isNotBlank() && crudInputText != action.currentName,
                        ) {
                            Text("Rename")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { activeCrudAction = null }) {
                            Text("Cancel")
                        }
                    },
                )
            }
            is EditorCrudAction.Delete -> {
                AlertDialog(
                    onDismissRequest = { activeCrudAction = null },
                    title = { Text("Delete ${if (action.isDirectory) "Folder" else "File"}") },
                    text = {
                        Text("Are you sure you want to delete '${action.name}'? This cannot be undone.")
                    },
                    confirmButton = {
                        Button(
                            onClick = {
                                onDeleteEntry(action.path)
                                activeCrudAction = null
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                        ) {
                            Text("Delete")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { activeCrudAction = null }) {
                            Text("Cancel")
                        }
                    },
                )
            }
        }
    }

    // Unsaved Tab Close Confirmation Dialog
    tabToCloseUnsaved?.let { tab ->
        AlertDialog(
            onDismissRequest = { tabToCloseUnsaved = null },
            title = { Text("Unsaved Changes") },
            text = {
                Text("Do you want to save changes to '${tab.name}' before closing?")
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (tab.path == activeTabPath && webViewRef != null) {
                            webViewRef?.evaluateJavascript("window.editorBridge ? window.editorBridge.getContent() : null") { result ->
                                val latestContent = if (!result.isNullOrBlank() && result != "null") {
                                    runCatching { org.json.JSONTokener(result).nextValue() as? String }.getOrNull() ?: tab.content
                                } else {
                                    tab.content
                                }
                                onSaveTab(tab.path, latestContent)
                                onCloseTab(tab.path)
                                tabToCloseUnsaved = null
                            }
                        } else {
                            onSaveTab(tab.path, tab.content)
                            onCloseTab(tab.path)
                            tabToCloseUnsaved = null
                        }
                    },
                ) {
                    Text("Save & Close")
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        onCloseTab(tab.path)
                        tabToCloseUnsaved = null
                    }) {
                        Text("Discard", color = MaterialTheme.colorScheme.error)
                    }
                    TextButton(onClick = { tabToCloseUnsaved = null }) {
                        Text("Cancel")
                    }
                }
            },
        )
    }

    if (showDiagnosticsDialog) {
        val context = LocalContext.current
        val diagnosticsInfo = remember(context, openTabs, activeTabPath, editorReady, pageFinished, consoleLogs.size) {
            checkEditorDiagnostics(
                context = context,
                openTabs = openTabs,
                activeTabPath = activeTabPath,
                editorReady = editorReady,
                pageFinished = pageFinished,
                logs = consoleLogs.toList(),
            )
        }

        EditorDiagnosticsDialog(
            diagnostics = diagnosticsInfo,
            onDismiss = { showDiagnosticsDialog = false },
            onForceReload = {
                showDiagnosticsDialog = false
                showFallbackEditor = false
                editorReady = false
                pageFinished = false
                webViewRef?.reload()
            },
        )
    }
}

@Composable
private fun EditorTabsRow(
    tabs: List<EditorTab>,
    activeTabPath: String?,
    onSelectTab: (String) -> Unit,
    onCloseTab: (EditorTab) -> Unit,
) {
    Surface(
        color = VSCODE_SIDEBAR,
        modifier = Modifier.fillMaxWidth().height(36.dp),
    ) {
        LazyRow(
            modifier = Modifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            items(tabs, key = { it.path }) { tab ->
                val isActive = tab.path == activeTabPath
                Row(
                    modifier = Modifier
                        .fillMaxHeight()
                        .background(if (isActive) VSCODE_TAB_ACTIVE else VSCODE_TAB_INACTIVE)
                        .border(width = 0.5.dp, color = VSCODE_BORDER)
                        .clickable { onSelectTab(tab.path) }
                        .padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        tab.name,
                        fontSize = 12.sp,
                        color = if (isActive) Color.White else VSCODE_TEXT_MUTED,
                        fontWeight = if (isActive) FontWeight.Medium else FontWeight.Normal,
                    )
                    if (tab.isModified) {
                        Text(
                            " •",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = PocketOrange,
                        )
                    }
                    Spacer(Modifier.width(6.dp))
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "Close tab",
                        tint = if (isActive) Color.White else VSCODE_TEXT_MUTED,
                        modifier = Modifier
                            .size(14.dp)
                            .clickable { onCloseTab(tab) },
                    )
                }
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun CodeMirrorWebView(
    bridge: EditorWebBridge,
    onWebViewCreated: (WebView) -> Unit,
    onConsoleLog: (String) -> Unit,
    onPageFinishedState: (Boolean) -> Unit,
) {
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            val assetLoader = WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(ctx))
                .build()
            WebView(ctx).apply {
                setBackgroundColor(0xFF1E1E1E.toInt())
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    databaseEnabled = true
                    builtInZoomControls = false
                    displayZoomControls = false
                    useWideViewPort = false
                    loadWithOverviewMode = true
                    setSupportZoom(false)
                    allowFileAccess = false
                    allowFileAccessFromFileURLs = false
                    allowUniversalAccessFromFileURLs = false
                }
                webChromeClient = object : WebChromeClient() {
                    override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                        val msg = consoleMessage?.message().orEmpty()
                        val level = consoleMessage?.messageLevel()
                        val line = consoleMessage?.lineNumber()
                        val source = consoleMessage?.sourceId()?.substringAfterLast('/') ?: "unknown"
                        val logText = "[$level] $msg ($source:$line)"
                        Log.d("EditorJS", logText)
                        onConsoleLog(logText)
                        return true
                    }
                }
                webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                        val uri = request?.url ?: return null
                        return assetLoader.shouldInterceptRequest(uri)
                    }

                    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                        val url = request?.url?.toString().orEmpty()
                        return !url.startsWith("https://appassets.androidplatform.net/assets/editor/")
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        Log.d("EditorScreen", "onPageFinished: $url")
                        onConsoleLog("[WebView] Page finished: $url")
                        onPageFinishedState(true)
                        view?.post {
                            view.evaluateJavascript("if (typeof window.loadTabFromAndroid === 'function') { window.loadTabFromAndroid(); }", null)
                        }
                    }

                    override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                        super.onReceivedError(view, request, error)
                        val errDesc = error?.description?.toString() ?: "Unknown error"
                        val errCode = error?.errorCode ?: 0
                        val logText = "[WebView Error $errCode] $errDesc for ${request?.url}"
                        Log.e("EditorScreen", logText)
                        onConsoleLog(logText)
                    }
                }
                addJavascriptInterface(bridge, "AndroidBridge")
                loadUrl("https://appassets.androidplatform.net/assets/editor/index.html")
                onWebViewCreated(this)
            }
        },
    )
}

@Composable
private fun NativeFallbackEditor(
    content: String,
    onContentChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var textFieldValue by remember(content) {
        mutableStateOf(TextFieldValue(text = content))
    }

    LaunchedEffect(content) {
        if (textFieldValue.text != content) {
            textFieldValue = textFieldValue.copy(text = content)
        }
    }

    val lineCount = remember(textFieldValue.text) {
        textFieldValue.text.count { it == '\n' } + 1
    }

    val scrollState = rememberScrollState()

    Row(
        modifier = modifier
            .fillMaxSize()
            .background(VSCODE_BG)
            .verticalScroll(scrollState),
    ) {
        Column(
            modifier = Modifier
                .width(44.dp)
                .background(Color(0xFF252526))
                .padding(vertical = 8.dp, horizontal = 6.dp),
            horizontalAlignment = Alignment.End,
        ) {
            for (i in 1..lineCount) {
                Text(
                    text = "$i",
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    color = Color(0xFF858585),
                    lineHeight = 18.sp,
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        BasicTextField(
            value = textFieldValue,
            onValueChange = { next ->
                textFieldValue = next
                if (next.text != content) {
                    onContentChange(next.text)
                }
            },
            textStyle = TextStyle(
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                color = Color(0xFFD4D4D4),
                lineHeight = 18.sp,
            ),
            cursorBrush = SolidColor(PocketOrange),
            modifier = Modifier
                .weight(1f)
                .padding(vertical = 8.dp, horizontal = 4.dp),
        )
    }
}

@Composable
private fun EditorDiagnosticsDialog(
    diagnostics: EditorDiagnosticsInfo,
    onDismiss: () -> Unit,
    onForceReload: () -> Unit,
) {
    val clipboardManager = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Info, contentDescription = null, tint = VSCODE_ACCENT)
                Spacer(Modifier.width(8.dp))
                Text("Editor Diagnostics", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                // Section: Assets
                Text("ASSETS", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = VSCODE_ACCENT)
                Spacer(Modifier.height(4.dp))
                DiagnosticsRow(
                    label = "editor/index.html",
                    value = if (diagnostics.indexHtmlExists) "${diagnostics.indexHtmlBytes} B" else "NOT FOUND",
                    isOk = diagnostics.indexHtmlExists,
                )
                DiagnosticsRow(
                    label = "editor/editor.bundle.js",
                    value = if (diagnostics.bundleJsExists) "${diagnostics.bundleJsBytes / 1024} KB (${diagnostics.bundleJsBytes} B)" else "NOT FOUND",
                    isOk = diagnostics.bundleJsExists,
                )

                Spacer(Modifier.height(12.dp))
                // Section: Status
                Text("BRIDGE & STATUS", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = VSCODE_ACCENT)
                Spacer(Modifier.height(4.dp))
                DiagnosticsRow("AndroidBridge Attached", "${diagnostics.bridgeAttached}", diagnostics.bridgeAttached)
                DiagnosticsRow("Page Finished", "${diagnostics.pageFinished}", diagnostics.pageFinished)
                DiagnosticsRow("Editor Ready", "${diagnostics.editorReady}", diagnostics.editorReady)

                Spacer(Modifier.height(12.dp))
                // Section: Active Tab
                Text("ACTIVE TAB", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = VSCODE_ACCENT)
                Spacer(Modifier.height(4.dp))
                DiagnosticsRow("Name", diagnostics.activeTabName ?: "(None)", diagnostics.activeTabName != null)
                DiagnosticsRow("Path", diagnostics.activeTabPath ?: "(None)", true)
                DiagnosticsRow("Content Length", "${diagnostics.activeTabContentLength} chars", true)
                DiagnosticsRow("Modified", "${diagnostics.isModified}", true)
                DiagnosticsRow("Partial Load", "${diagnostics.isPartiallyLoaded}", true)

                Spacer(Modifier.height(12.dp))
                // Section: Console Logs
                Text("RECENT LOGS (${diagnostics.logs.size})", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = VSCODE_ACCENT)
                Spacer(Modifier.height(4.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 160.dp)
                        .background(Color(0xFF141414), RoundedCornerShape(4.dp))
                        .padding(8.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    if (diagnostics.logs.isEmpty()) {
                        Text("No logs yet", fontSize = 11.sp, color = VSCODE_TEXT_MUTED)
                    } else {
                        Column {
                            diagnostics.logs.takeLast(30).forEach { logLine ->
                                Text(
                                    text = logLine,
                                    fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = if (logLine.contains("ERROR", ignoreCase = true) || logLine.contains("Error")) {
                                        Color(0xFFFF6B6B)
                                    } else {
                                        Color(0xFFB0B0B0)
                                    },
                                    lineHeight = 14.sp,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(
                    onClick = {
                        clipboardManager.setText(AnnotatedString(diagnostics.toFormattedString()))
                        copied = true
                    }
                ) {
                    Text(if (copied) "Copied!" else "Copy Diagnostics")
                }
                Button(onClick = onForceReload) {
                    Text("Force Reload")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        },
    )
}

@Composable
private fun DiagnosticsRow(label: String, value: String, isOk: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, fontSize = 12.sp, color = VSCODE_TEXT)
        Text(
            value,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = if (isOk) PocketGreen else PocketOrange,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun EditorExtraKeysRow(
    onInsertText: (String) -> Unit,
    onIndent: () -> Unit,
    onMoveCursor: (String) -> Unit,
) {
    Surface(
        color = Color(0xFF181818),
        modifier = Modifier.fillMaxWidth().height(42.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            KeyChip("Tab") { onIndent() }
            KeyChip("{") { onInsertText("{") }
            KeyChip("}") { onInsertText("}") }
            KeyChip("(") { onInsertText("(") }
            KeyChip(")") { onInsertText(")") }
            KeyChip("[") { onInsertText("[") }
            KeyChip("]") { onInsertText("]") }
            KeyChip(";") { onInsertText(";") }
            KeyChip("\"") { onInsertText("\"") }
            KeyChip("'") { onInsertText("'") }
            KeyChip(":") { onInsertText(":") }
            KeyChip("=") { onInsertText("=") }
            KeyChip("<") { onInsertText("<") }
            KeyChip(">") { onInsertText(">") }
            KeyChip("/") { onInsertText("/") }
            KeyChip("\\") { onInsertText("\\") }
            KeyChipIcon(Icons.AutoMirrored.Filled.KeyboardArrowLeft) { onMoveCursor("left") }
            KeyChipIcon(Icons.AutoMirrored.Filled.KeyboardArrowRight) { onMoveCursor("right") }
            KeyChipIcon(Icons.Default.KeyboardArrowUp) { onMoveCursor("up") }
            KeyChipIcon(Icons.Default.KeyboardArrowDown) { onMoveCursor("down") }
        }
    }
}

@Composable
private fun KeyChip(text: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .height(32.dp)
            .background(VSCODE_KEY_BG, RoundedCornerShape(4.dp))
            .border(0.5.dp, Color(0xFF3C3C3C), RoundedCornerShape(4.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = Color(0xFFE0E0E0),
            fontSize = 13.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun KeyChipIcon(icon: ImageVector, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .height(32.dp)
            .width(36.dp)
            .background(VSCODE_KEY_BG, RoundedCornerShape(4.dp))
            .border(0.5.dp, Color(0xFF3C3C3C), RoundedCornerShape(4.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = Color(0xFFE0E0E0), modifier = Modifier.size(16.dp))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EditorFileTreeSidebar(
    files: List<WorkspaceEntry>,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    collapsedDirs: Map<String, Boolean>,
    onToggleDir: (String) -> Unit,
    onSelectFile: (String) -> Unit,
    onNewFile: (parentDir: String) -> Unit,
    onNewFolder: (parentDir: String) -> Unit,
    onImportFiles: (parentDir: String) -> Unit = {},
    fileImporting: Boolean = false,
    fileImportProgress: Float? = null,
    fileImportMessage: String? = null,
    onCancelFileImport: (() -> Unit)? = null,
    onRename: (path: String, name: String) -> Unit,
    onDelete: (path: String, name: String, isDir: Boolean) -> Unit,
    onRefresh: () -> Unit,
) {
    var contextMenuEntry by remember { mutableStateOf<WorkspaceEntry?>(null) }

    Column(modifier = Modifier.fillMaxSize().padding(vertical = 8.dp)) {
        // Sidebar header
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("EXPLORER", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = VSCODE_TEXT_MUTED, letterSpacing = 1.sp)
            Row {
                IconButton(onClick = { onNewFile("") }, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.AutoMirrored.Filled.NoteAdd, contentDescription = "New File in Root", tint = VSCODE_TEXT, modifier = Modifier.size(16.dp))
                }
                IconButton(onClick = { onNewFolder("") }, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Default.CreateNewFolder, contentDescription = "New Folder in Root", tint = VSCODE_TEXT, modifier = Modifier.size(16.dp))
                }
                IconButton(onClick = { onImportFiles("") }, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Default.FileUpload, contentDescription = "Import Files in Root", tint = VSCODE_TEXT, modifier = Modifier.size(16.dp))
                }
                IconButton(onClick = onRefresh, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Default.Refresh, contentDescription = "Refresh", tint = VSCODE_TEXT, modifier = Modifier.size(16.dp))
                }
            }
        }

        if (fileImporting) {
            Surface(
                color = VSCODE_BG,
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(1.dp, VSCODE_BORDER),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            ) {
                Column(modifier = Modifier.padding(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            fileImportMessage ?: "Importing files…",
                            fontSize = 11.sp,
                            color = VSCODE_TEXT,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        if (onCancelFileImport != null) {
                            Text(
                                "Cancel",
                                fontSize = 11.sp,
                                color = PocketOrange,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.clickable { onCancelFileImport() }.padding(4.dp),
                            )
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    if (fileImportProgress != null) {
                        LinearProgressIndicator(
                            progress = { fileImportProgress },
                            modifier = Modifier.fillMaxWidth().height(4.dp),
                            color = PocketOrange,
                        )
                    } else {
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth().height(4.dp),
                            color = PocketOrange,
                        )
                    }
                }
            }
        }

        // Search in files
        OutlinedTextField(
            value = searchQuery,
            onValueChange = onSearchQueryChange,
            placeholder = { Text("Filter files…", fontSize = 12.sp, color = VSCODE_TEXT_MUTED) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = VSCODE_BG,
                unfocusedContainerColor = VSCODE_BG,
                focusedBorderColor = VSCODE_ACCENT,
                unfocusedBorderColor = VSCODE_BORDER,
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White,
            ),
        )

        Spacer(Modifier.height(4.dp))

        // Filter and determine visibility based on collapsed parent directories
        val visibleFiles = remember(files, searchQuery, collapsedDirs) {
            if (searchQuery.isNotBlank()) {
                files.filter { it.name.contains(searchQuery, ignoreCase = true) || it.path.contains(searchQuery, ignoreCase = true) }
            } else {
                files.filter { entry ->
                    // Check if any ancestor folder of entry is collapsed
                    val parts = entry.path.split('/')
                    if (parts.size <= 1) true
                    else {
                        var ancestor = ""
                        var visible = true
                        for (i in 0 until parts.size - 1) {
                            ancestor = if (ancestor.isEmpty()) parts[i] else "$ancestor/${parts[i]}"
                            if (collapsedDirs[ancestor] == true) {
                                visible = false
                                break
                            }
                        }
                        visible
                    }
                }
            }
        }

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(visibleFiles, key = { it.path }) { entry ->
                val isCollapsed = collapsedDirs[entry.path] == true
                val indent = (entry.depth * 14).dp

                Box {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .combinedClickable(
                                onClick = {
                                    if (entry.isDirectory) {
                                        onToggleDir(entry.path)
                                    } else {
                                        onSelectFile(entry.path)
                                    }
                                },
                                onLongClick = {
                                    contextMenuEntry = entry
                                },
                            )
                            .padding(start = 12.dp + indent, end = 12.dp, top = 5.dp, bottom = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (entry.isDirectory) {
                            Icon(
                                if (isCollapsed) Icons.Default.ChevronRight else Icons.Default.ExpandMore,
                                contentDescription = null,
                                tint = VSCODE_TEXT_MUTED,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(Modifier.width(4.dp))
                            Icon(
                                if (isCollapsed) Icons.Default.Folder else Icons.Default.FolderOpen,
                                contentDescription = null,
                                tint = Color(0xFFDCA842),
                                modifier = Modifier.size(16.dp),
                            )
                        } else {
                            Spacer(Modifier.width(16.dp))
                            Icon(
                                fileIconForExtension(entry.name),
                                contentDescription = null,
                                tint = fileIconColorForExtension(entry.name),
                                modifier = Modifier.size(16.dp),
                            )
                        }
                        Spacer(Modifier.width(6.dp))
                        Text(
                            entry.name,
                            fontSize = 13.sp,
                            color = if (entry.isDirectory) Color.White else VSCODE_TEXT,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }

                    // Context Menu for Long-press
                    if (contextMenuEntry == entry) {
                        DropdownMenu(
                            expanded = true,
                            onDismissRequest = { contextMenuEntry = null },
                        ) {
                            if (entry.isDirectory) {
                                DropdownMenuItem(
                                    text = { Text("New File") },
                                    leadingIcon = { Icon(Icons.AutoMirrored.Filled.NoteAdd, contentDescription = null) },
                                    onClick = {
                                        contextMenuEntry = null
                                        onNewFile(entry.path)
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("New Folder") },
                                    leadingIcon = { Icon(Icons.Default.CreateNewFolder, contentDescription = null) },
                                    onClick = {
                                        contextMenuEntry = null
                                        onNewFolder(entry.path)
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Import Files Here") },
                                    leadingIcon = { Icon(Icons.Default.FileUpload, contentDescription = null) },
                                    onClick = {
                                        contextMenuEntry = null
                                        onImportFiles(entry.path)
                                    },
                                )
                            }
                            DropdownMenuItem(
                                text = { Text("Rename") },
                                leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                                onClick = {
                                    contextMenuEntry = null
                                    onRename(entry.path, entry.name)
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                                leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                                onClick = {
                                    contextMenuEntry = null
                                    onDelete(entry.path, entry.name, entry.isDirectory)
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun fileIconForExtension(filename: String): ImageVector {
    val ext = filename.substringAfterLast('.', "").lowercase()
    return when (ext) {
        "kt", "kts", "java", "c", "cpp", "h", "hpp", "py", "js", "ts", "jsx", "tsx", "html", "css" -> Icons.Default.Code
        "json" -> Icons.Default.DataObject
        "md", "txt", "log", "xml", "yaml", "yml" -> Icons.Default.Description
        else -> Icons.AutoMirrored.Filled.InsertDriveFile
    }
}

private fun fileIconColorForExtension(filename: String): Color {
    val ext = filename.substringAfterLast('.', "").lowercase()
    return when (ext) {
        "kt", "kts" -> Color(0xFF7F52FF)
        "py" -> Color(0xFFFFD43B)
        "java" -> Color(0xFFED8B00)
        "c", "cpp", "h", "hpp" -> Color(0xFF5C8DBC)
        "json" -> Color(0xFF4EC9B0)
        "md" -> Color(0xFF569CD6)
        "html", "htm" -> Color(0xFFE34F26)
        "css" -> Color(0xFF264DE4)
        "js", "ts", "jsx", "tsx" -> Color(0xFFF7DF1E)
        else -> Color(0xFF9AA0A6)
    }
}
