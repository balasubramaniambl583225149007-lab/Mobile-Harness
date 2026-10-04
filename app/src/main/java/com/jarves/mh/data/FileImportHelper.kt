package com.jarves.mh.data

import java.util.UUID

object FileImportHelper {
    const val MAX_IMPORT_BYTES = 1024L * 1024L * 1024L // 1 GB default cap
    const val LARGE_FILE_THRESHOLD_BYTES = 100L * 1024L * 1024L // 100 MB warning threshold
    const val MAX_ATTACHMENT_CHAT_CHARS = 25_000

    val COMMON_TEXT_EXTENSIONS = setOf(
        "txt", "md", "markdown", "mdown", "mkdn",
        "json", "jsonl", "csv", "tsv", "xml", "yaml", "yml", "log",
        "kt", "kts", "java", "py", "pyw", "js", "mjs", "cjs", "ts", "tsx", "jsx",
        "html", "htm", "css", "scss", "sass", "less",
        "c", "cc", "cpp", "cxx", "h", "hh", "hpp", "hxx",
        "rs", "go", "rb", "php", "swift", "dart", "r", "lua", "pl", "pm",
        "sh", "bash", "zsh", "fish", "bat", "cmd", "ps1",
        "gradle", "properties", "toml", "ini", "conf", "config", "cfg",
        "sql", "graphql", "proto", "diff", "patch", "env", "dockerfile",
        "makefile", "cmake",
    )

    fun isCommonText(displayName: String, mimeType: String?): Boolean {
        val extension = displayName.substringAfterLast('.', "").lowercase()
        val mime = mimeType?.lowercase().orEmpty()
        return mime.startsWith("text/") ||
            mime == "application/json" ||
            mime == "application/xml" ||
            mime == "application/javascript" ||
            mime == "application/x-javascript" ||
            mime == "application/x-sh" ||
            mime == "application/x-csh" ||
            mime.endsWith("+json") ||
            mime.endsWith("+xml") ||
            extension in COMMON_TEXT_EXTENSIONS
    }

    /**
     * Sanitizes a file name to prevent directory traversal and remove illegal path characters.
     * Replaces unsafe characters with '_' and strips leading/trailing dots and slashes.
     */
    fun sanitizeFileName(rawName: String): String {
        var clean = rawName.substringAfterLast('/').substringAfterLast('\\')
        clean = clean.replace(Regex("[\u0000-\u001F\u007F]"), "")
        clean = clean.replace(Regex("[\\\\/:*?\"<>|]"), "_")
        clean = clean.trim().trim('.')
        val reservedNames = setOf("con", "prn", "aux", "nul", "com1", "com2", "com3", "com4", "lpt1", "lpt2")
        val baseName = clean.substringBeforeLast('.')
        if (baseName.lowercase() in reservedNames) {
            clean = "file_$clean"
        }
        return clean.ifBlank { "imported_file_${UUID.randomUUID().toString().take(8)}" }.take(120)
    }

    /**
     * Truncates text if it exceeds maxChars, returning the text and whether it was truncated.
     */
    fun truncateText(content: String, maxChars: Int = MAX_ATTACHMENT_CHAT_CHARS): Pair<String, Boolean> {
        return if (content.length > maxChars) {
            content.take(maxChars) to true
        } else {
            content to false
        }
    }

    /**
     * Formats byte size into human-readable representation.
     */
    fun formatFileSize(bytes: Long): String = when {
        bytes < 1_024 -> "$bytes B"
        bytes < 1_048_576 -> "%.1f KB".format(bytes / 1_024.0)
        bytes < 1_073_741_824 -> "%.1f MB".format(bytes / 1_048_576.0)
        else -> "%.1f GB".format(bytes / 1_073_741_824.0)
    }
}
