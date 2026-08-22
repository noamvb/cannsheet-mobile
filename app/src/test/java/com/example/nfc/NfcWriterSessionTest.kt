package com.example.nfc

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * v1.6.1 let the platform dispatch a half-registered tag to the scan handler while the writer was
 * still working on it, which showed "This NFC tag is not registered" during registration and
 * paused the writer before it could finish. These pin the guard that withdraws the scan handler
 * while the writer owns the field.
 */
class NfcWriterSessionTest {
    @Before
    fun setUp() = NfcWriterSession.reset()

    @After
    fun tearDown() = NfcWriterSession.reset()

    @Test
    fun noSessionMeansTheScanHandlerIsFreeToRun() {
        assertFalse(NfcWriterSession.isActiveWithin(nowEpochMillis = 10_000L))
    }

    @Test
    fun aResumedWriterOwnsTheFieldRegardlessOfElapsedTime() {
        NfcWriterSession.onWriterResumed(nowEpochMillis = 1_000L)

        assertTrue(NfcWriterSession.isActiveWithin(nowEpochMillis = 1_000L))
        // A long write or a slow retap must not hand the tag back to the platform handler.
        assertTrue(NfcWriterSession.isActiveWithin(nowEpochMillis = 10_000_000L))
    }

    @Test
    fun aPausingWriterKeepsTheFieldForTheGracePeriodOnly() {
        NfcWriterSession.onWriterResumed(nowEpochMillis = 1_000L)
        NfcWriterSession.onWriterPaused(nowEpochMillis = 2_000L)

        // The dispatch that pauses the writer arrives immediately after the pause; that is the
        // exact race this guard exists to cover.
        assertTrue(NfcWriterSession.isActiveWithin(nowEpochMillis = 2_000L))
        assertTrue(
            NfcWriterSession.isActiveWithin(
                nowEpochMillis = 2_000L + NfcWriterSession.GRACE_MILLIS - 1L,
            ),
        )
        // Once the grace period lapses an ordinary quick-log tap must work again.
        assertFalse(
            NfcWriterSession.isActiveWithin(
                nowEpochMillis = 2_000L + NfcWriterSession.GRACE_MILLIS,
            ),
        )
    }

    @Test
    fun aClockThatMovesBackwardsDoesNotStrandTheScanHandler() {
        NfcWriterSession.onWriterPaused(nowEpochMillis = 10_000L)

        // A backwards clock adjustment must not make the guard look indefinitely active.
        assertFalse(NfcWriterSession.isActiveWithin(nowEpochMillis = 1_000L))
    }
}
