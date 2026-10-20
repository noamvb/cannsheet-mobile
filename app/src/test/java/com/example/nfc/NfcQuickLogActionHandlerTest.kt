package com.example.nfc

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.widget.DeferredPenInputKind
import com.example.widget.PenWidgetCommitPayload
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class NfcQuickLogActionHandlerTest {
    private class FakeOps : NfcQuickLogOperations {
        val undos = mutableListOf<String>()
        val submissions = mutableListOf<String>()
        var undoResult = true
        var submitResult = false
        var payload: PenWidgetCommitPayload? = null
        override suspend fun undo(commitId: String): Boolean {
            undos += commitId
            return undoResult
        }
        override suspend fun submitNow(commitId: String): Boolean {
            submissions += commitId
            return submitResult
        }
        override suspend fun pending(): PenWidgetCommitPayload? = payload
    }

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val platform = NfcCapturingPlatform()
    private val ops = FakeOps()
    private val handler = NfcQuickLogActionHandler(ops, NfcQuickLogNotifier(context, platform))
    private fun action(name: String) = Intent(context, NfcQuickLogActionReceiver::class.java)
        .setAction(name).putExtra("commitId", "c1").putExtra("uses", 3).putExtra("cartName", "Test Cart A")

    @Test
    fun successfulUndoPresentsUndone() = runBlocking {
        handler.handle(action(ACTION_NFC_UNDO), false)
        assertEquals(listOf("c1"), ops.undos)
        assertEquals("3 uses not logged", platform.posts.single().second.text())
    }

    @Test
    fun undoAfterCommitPresentsSavedWithoutSubmitting() = runBlocking {
        ops.undoResult = false
        handler.handle(action(ACTION_NFC_UNDO), false)
        assertEquals("3 uses saved for Test Cart A", platform.posts.single().second.text())
        assertEquals(listOf("c1"), ops.undos)
        assertTrue(ops.submissions.isEmpty())
    }

    private fun pendingPayload(uses: Double) = PenWidgetCommitPayload(
        version = 3, commitId = "c1", eventId = "event", submittedAtEpochMillis = 0L,
        commitAtEpochMillis = 5_000L, productId = "product", productUuid = null,
        inputKind = DeferredPenInputKind.DIRECT_USES, seconds = null,
        secondsPerUse = null, restoreDraftSeconds = null, uses = uses,
        date = "2026-10-20", time = "12:00",
    )

    @Test
    fun submitRacingTheCommitTimerPresentsSaved() = runBlocking {
        // submitNow finds no claim because the timer already committed; nothing is pending.
        handler.handle(action(ACTION_NFC_SUBMIT), false)
        assertEquals(listOf("c1"), ops.submissions)
        assertEquals(listOf("Saving 3 uses…", "3 uses saved for Test Cart A"), platform.posts.map { it.second.text() })
    }

    @Test
    fun failedSubmitPresentsSavingThenRetryWithSamePayload() = runBlocking {
        ops.payload = pendingPayload(3.0)
        handler.handle(action(ACTION_NFC_SUBMIT), false)
        assertEquals(listOf("c1"), ops.submissions)
        assertEquals(listOf("Saving 3 uses…", "The tap could not be saved. Keep it and retry."), platform.posts.map { it.second.text() })
        val retry = platform.posts.last().second.actions.single()
        assertEquals("Retry", retry.title.toString())
        val intent = org.robolectric.Shadows.shadowOf(retry.actionIntent).savedIntent
        assertEquals("c1", intent.getStringExtra("commitId"))
        assertEquals(3, intent.getIntExtra("uses", 0))
    }

    @Test
    fun retryRejectsFractionalDirectUsesBeforeSubmitting() = runBlocking {
        // The same fake records a real submission first, proving the absence probe works.
        handler.handle(action(ACTION_NFC_SUBMIT), false)
        assertEquals(listOf("c1"), ops.submissions)
        ops.submissions.clear()
        platform.posts.clear()
        ops.payload = pendingPayload(2.5)
        handler.handle(Intent(context, NfcQuickLogActionReceiver::class.java).setAction(ACTION_NFC_RETRY), false)
        assertTrue(ops.submissions.isEmpty())
        assertEquals("NFC quick-log could not be completed.", platform.posts.single().second.text())
    }
}
