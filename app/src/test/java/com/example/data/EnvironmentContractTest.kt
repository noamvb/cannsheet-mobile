package com.example.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EnvironmentContractTest {
    @Test fun exactEnvironmentMatchIsRequired() {
        assertTrue(environmentMatches("SANDBOX", "SANDBOX"))
        assertFalse(environmentMatches("SANDBOX", "PRODUCTION"))
        assertFalse(environmentMatches("PRODUCTION", null))
    }

    @Test fun syncPayloadIncludesCompiledEnvironment() {
        val payload = SyncPayload(
            requestId = "40000000-0000-4000-8000-000000000001",
            environment = "SANDBOX",
            purchases = emptyList(),
            consumptions = emptyList(),
        )
        assertEquals("SANDBOX", payload.environment)
    }
}
