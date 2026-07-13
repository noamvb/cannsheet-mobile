package com.example.ui

import java.io.IOException
import java.net.SocketTimeoutException
import org.junit.Assert.assertEquals
import org.junit.Test

class SyncFailureStatusTest {
    @Test
    fun timeoutExplainsThatTheEntryIsStillPending() {
        assertEquals(
            "Server confirmation is taking longer than expected. Your entry is still pending and will retry safely.",
            syncFailureStatus(SocketTimeoutException("timeout")),
        )
    }

    @Test
    fun otherNetworkErrorsKeepTheirDetails() {
        assertEquals(
            "Sync error: network unavailable",
            syncFailureStatus(IOException("network unavailable")),
        )
    }
}
