package com.jarves.mh.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FileImportHelperTest {

    @Test
    fun sanitizeFileNameStripsPathTraversal() {
        assertEquals("passwd", FileImportHelper.sanitizeFileName("../../etc/passwd"))
        assertEquals("cmd.exe", FileImportHelper.sanitizeFileName("..\\..\\windows\\system32\\cmd.exe"))
        assertEquals("secret.key", FileImportHelper.sanitizeFileName("/var/data/../../secret.key"))
    }

    @Test
    fun sanitizeFileNameReplacesIllegalCharacters() {
        assertEquals(
            "file_name_with_bad_chars__.txt",
            FileImportHelper.sanitizeFileName("file:name*with?bad<chars>|.txt"),
        )
        assertEquals("filename.txt", FileImportHelper.sanitizeFileName("file\u0000\u001Fname.txt"))
    }

    @Test
    fun sanitizeFileNameTrimsWhitespaceAndDots() {
        assertEquals("file.txt", FileImportHelper.sanitizeFileName("   ...file.txt...   "))
        assertEquals("normal.png", FileImportHelper.sanitizeFileName("  normal.png  "))
    }

    @Test
    fun sanitizeFileNameHandlesBlankOrEmpty() {
        val fallback = FileImportHelper.sanitizeFileName("   ....   ")
        assertTrue(fallback.startsWith("imported_file_"))
        assertFalse(fallback.contains("/"))
        assertFalse(fallback.contains("."))
    }

    @Test
    fun sanitizeFileNameProtectsReservedNames() {
        assertEquals("file_con.txt", FileImportHelper.sanitizeFileName("con.txt"))
        assertEquals("file_nul.dat", FileImportHelper.sanitizeFileName("nul.dat"))
    }

    @Test
    fun isCommonTextDetectsCodeAndTextFiles() {
        assertTrue(FileImportHelper.isCommonText("Main.kt", null))
        assertTrue(FileImportHelper.isCommonText("script.py", "application/octet-stream"))
        assertTrue(FileImportHelper.isCommonText("styles.css", null))
        assertTrue(FileImportHelper.isCommonText("data.json", "application/json"))
        assertTrue(FileImportHelper.isCommonText("README.md", "text/markdown"))
        assertTrue(FileImportHelper.isCommonText("build.gradle", null))
        assertTrue(FileImportHelper.isCommonText("query.sql", null))
        assertTrue(FileImportHelper.isCommonText("config.yaml", null))
        assertTrue(FileImportHelper.isCommonText("custom.xyz", "text/plain"))
    }

    @Test
    fun isCommonTextRejectsBinaryFiles() {
        assertFalse(FileImportHelper.isCommonText("photo.jpg", "image/jpeg"))
        assertFalse(FileImportHelper.isCommonText("photo.png", "image/png"))
        assertFalse(FileImportHelper.isCommonText("archive.zip", "application/zip"))
        assertFalse(FileImportHelper.isCommonText("document.pdf", "application/pdf"))
        assertFalse(FileImportHelper.isCommonText("app.apk", "application/vnd.android.package-archive"))
        assertFalse(FileImportHelper.isCommonText("program.exe", "application/octet-stream"))
        assertFalse(FileImportHelper.isCommonText("data.bin", null))
    }

    @Test
    fun truncateTextRespectsCharBudget() {
        val short = "Hello world"
        val (resultShort, truncatedShort) = FileImportHelper.truncateText(short, 20)
        assertEquals("Hello world", resultShort)
        assertFalse(truncatedShort)

        val long = "A".repeat(100)
        val (resultLong, truncatedLong) = FileImportHelper.truncateText(long, 40)
        assertEquals(40, resultLong.length)
        assertTrue(truncatedLong)
    }

    @Test
    fun sizeLimitsAreConfiguredSensibly() {
        assertEquals(1024L * 1024L * 1024L, FileImportHelper.MAX_IMPORT_BYTES)
        assertEquals(100L * 1024L * 1024L, FileImportHelper.LARGE_FILE_THRESHOLD_BYTES)
        assertEquals(25_000, FileImportHelper.MAX_ATTACHMENT_CHAT_CHARS)
    }
}
