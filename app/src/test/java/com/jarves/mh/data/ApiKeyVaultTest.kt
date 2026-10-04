package com.jarves.mh.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ApiKeyVaultTest {
    @Test
    fun sanitizeApiKeyTrimsWhitespaceAndNewlines() {
        assertEquals("sk-test-123", sanitizeApiKey("  sk-test-123  "))
        assertEquals("sk-test-123", sanitizeApiKey("\n\nsk-test-123\r\n"))
        assertEquals("sk-test-123", sanitizeApiKey(" \r\n\t sk-test-123 \t\r\n "))
    }

    @Test
    fun sanitizeApiKeyRemovesSurroundingDoubleAndSingleQuotes() {
        assertEquals("sk-test-123", sanitizeApiKey("\"sk-test-123\""))
        assertEquals("sk-test-123", sanitizeApiKey("'sk-test-123'"))
        assertEquals("sk-test-123", sanitizeApiKey(" \"sk-test-123\" \n"))
        assertEquals("sk-test-123", sanitizeApiKey(" 'sk-test-123' \r\n"))
        assertEquals("sk-test-123", sanitizeApiKey("\"'sk-test-123'\""))
    }

    @Test
    fun sanitizeApiKeyReturnsEmptyForBlankOrOnlyQuotes() {
        assertEquals("", sanitizeApiKey(""))
        assertEquals("", sanitizeApiKey("   "))
        assertEquals("", sanitizeApiKey("\n\r\t"))
        assertEquals("", sanitizeApiKey("\"\""))
        assertEquals("", sanitizeApiKey("''"))
        assertEquals("", sanitizeApiKey(" \"\" "))
        assertEquals("", sanitizeApiKey(null))
    }

    @Test
    fun sanitizeApiKeyPreservesInternalCharacters() {
        assertEquals("sk-ant-api03-abc-def_123", sanitizeApiKey(" sk-ant-api03-abc-def_123\n"))
        assertEquals("Bearer sk-test", sanitizeApiKey(" \"Bearer sk-test\" "))
    }
}
