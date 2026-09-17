package com.example.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.InterruptedIOException
import java.io.IOException
import java.net.SocketTimeoutException

class AnalyticsUiErrorTest {

    @Test
    fun callTimeoutMapsToTimeout() {
        val error = analyticsUiError(InterruptedIOException("timeout"))
        assertEquals("TIMEOUT", error.code)
        assertTrue(error.retryable)
    }

    @Test
    fun socketTimeoutMapsToTimeout() {
        val error = analyticsUiError(SocketTimeoutException("read timed out"))
        assertEquals("TIMEOUT", error.code)
        assertTrue(error.retryable)
    }

    @Test
    fun plainIoExceptionMapsToOffline() {
        val error = analyticsUiError(IOException("x"))
        assertEquals("OFFLINE", error.code)
    }
}
