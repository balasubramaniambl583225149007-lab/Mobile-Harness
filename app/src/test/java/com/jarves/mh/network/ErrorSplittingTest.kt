package com.jarves.mh.network

import com.jarves.mh.runtime.ProviderRuntimeErrorDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ErrorSplittingTest {
    @Test
    fun claudeDetectorSplitsErrorTypesAccurately() {
        // 401 / 403 Rejected Key
        assertEquals(
            "The provider rejected the saved API key.",
            ProviderRuntimeErrorDetector.detect("HTTP 401 Unauthorized: Invalid API key"),
        )
        assertEquals(
            "The provider rejected the saved API key.",
            ProviderRuntimeErrorDetector.detect("""{"type":"error","error":{"message":"authentication_failed"}}"""),
        )
        assertEquals(
            "The provider rejected the saved API key.",
            ProviderRuntimeErrorDetector.detect("HTTP 403 Forbidden: access denied"),
        )

        // 402 No Credits
        assertEquals(
            "Insufficient credits. Use a :free model or add credits to your account.",
            ProviderRuntimeErrorDetector.detect("HTTP 402: Insufficient credits on account"),
        )
        assertEquals(
            "Insufficient credits. Use a :free model or add credits to your account.",
            ProviderRuntimeErrorDetector.detect("""{"error":"out of credits"}"""),
        )

        // 429 Rate Limited
        assertEquals(
            "The provider rate limit was reached. Please wait a moment before trying again.",
            ProviderRuntimeErrorDetector.detect("HTTP 429 Too Many Requests: rate limit exceeded"),
        )
        assertEquals(
            "The provider rate limit was reached. Please wait a moment before trying again.",
            ProviderRuntimeErrorDetector.detect("""{"error":"rate limit reached"}"""),
        )

        // Decryption Failure
        assertEquals(
            "Saved key could not be read. Please re-enter it.",
            ProviderRuntimeErrorDetector.detect("Saved key could not be read. Please re-enter it."),
        )

        // Network Error
        assertEquals(
            "Network connection problem. Please check your internet connection.",
            ProviderRuntimeErrorDetector.detect("Error: getaddrinfo ENOTFOUND api.anthropic.com"),
        )
        assertEquals(
            "Network connection problem. Please check your internet connection.",
            ProviderRuntimeErrorDetector.detect("TypeError: fetch failed due to ECONNREFUSED"),
        )
    }
}
