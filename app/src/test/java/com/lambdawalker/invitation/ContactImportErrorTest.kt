package com.lambdawalker.invitation

import org.junit.Assert.*
import org.junit.Test

class ContactImportErrorTest {
    @Test fun missingEndpointExplainsDeploymentInsteadOfRetrying() {
        val message = contactImportError(404)
        assertTrue(message.contains("HTTP 404"))
        assertTrue(message.contains("deploy", ignoreCase = true))
        assertTrue(message.contains("backend"))
        assertFalse(message.contains("Retry safely"))
    }
    @Test fun otherFailuresKeepStatusAndSafeRetryGuidance() {
        for (status in listOf(400, 403, 413, 429, 500, 503)) {
            val message = contactImportError(status)
            assertTrue(message.contains("HTTP $status"))
        }
        assertTrue(contactImportError(503).contains("existing numbers"))
    }
}
