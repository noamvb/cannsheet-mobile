package com.example.nfc

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NfcPresentationGateTest {
    @Test
    fun continuousPresentationIsSuppressedUntilRemoval() {
        val gate = NfcPresentationGate()
        val tag = NfcQuickLogContract.newTagData(1).tagId
        assertTrue(gate.begin(tag, nowEpochMillis = 1_000L))
        assertFalse(gate.begin(tag, nowEpochMillis = 1_001L))
        gate.removed(tag)
        assertTrue(gate.begin(tag, nowEpochMillis = 1_002L))
    }

    @Test
    fun boundedFallbackAllowsARePresentationWhenRemovalCallbackNeverArrives() {
        val gate = NfcPresentationGate()
        val tag = NfcQuickLogContract.newTagData(1).tagId
        assertTrue(gate.begin(tag, nowEpochMillis = 10_000L))
        assertFalse(gate.begin(tag, nowEpochMillis = 17_999L))
        assertTrue(gate.begin(tag, nowEpochMillis = 18_000L))
    }
}
