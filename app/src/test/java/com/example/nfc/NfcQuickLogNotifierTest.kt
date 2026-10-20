package com.example.nfc

import android.app.Notification
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.MainActivity
import com.example.domain.EXTRA_OPEN_CART_PICKER
import com.example.domain.EXTRA_START_ROUTE
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

internal class NfcCapturingPlatform : NfcQuickLogNotificationPlatform {
    var enabled = true
    val posts = mutableListOf<Pair<Int, Notification>>()
    override fun canPost(): Boolean = enabled
    override fun ensureChannel() {}
    override fun post(notificationId: Int, notification: Notification) {
        posts += notificationId to notification
    }
}

internal fun Notification.text(): String = extras.getCharSequence(Notification.EXTRA_TEXT).toString()

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class NfcQuickLogNotifierTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val platform = NfcCapturingPlatform()
    private val notifier = NfcQuickLogNotifier(context, platform)
    private val awaiting = NfcQuickLogResultState.AwaitingUndo("c1", 3, "Test Cart A", 0L)

    @Test
    fun awaitingUndoHasPrivateCopyActionsAndSafetyTimeout() {
        assertTrue(notifier.present(awaiting, false))
        val (id, notification) = platform.posts.single()
        assertEquals(1002, id)
        assertEquals("3 uses queued for Test Cart A", notification.text())
        assertEquals(listOf("Undo", "Submit now"), notification.actions.map { it.title.toString() })
        assertEquals(Notification.VISIBILITY_PRIVATE, notification.visibility)
        assertEquals("3 uses queued", notification.publicVersion.text())
        assertFalse(notification.publicVersion.text().contains("Test Cart A"))
        assertEquals(60_000L, notification.timeoutAfter)
    }

    @Test
    fun lockedCopyHidesCartInBothVersions() {
        notifier.present(awaiting, false)
        assertTrue(platform.posts.last().second.text().contains("Test Cart A"))
        notifier.present(awaiting, true)
        val notification = platform.posts.last().second
        assertFalse(notification.text().contains("Test Cart A"))
        assertFalse(notification.publicVersion.text().contains("Test Cart A"))
    }

    @Test
    fun undoIntentIsExplicitImmutableAndCarriesOnlyActionData() {
        notifier.present(awaiting, false)
        val pendingIntent = platform.posts.single().second.actions.first().actionIntent
        val intent = shadowOf(pendingIntent).savedIntent
        assertEquals(NfcQuickLogActionReceiver::class.java.name, intent.component?.className)
        assertTrue(pendingIntent.isImmutable)
        assertEquals("c1", intent.getStringExtra("commitId"))
        assertTrue(setOf("commitId", "uses", "cartName").containsAll(intent.extras!!.keySet()))
        assertEquals("Test Cart A", intent.getStringExtra("cartName"))
        notifier.present(awaiting, true)
        val lockedIntent = shadowOf(platform.posts.last().second.actions.first().actionIntent).savedIntent
        assertFalse(lockedIntent.hasExtra("cartName"))
    }

    @Test
    fun savedHasNoActionsAndExpires() {
        notifier.present(awaiting, false)
        assertEquals(2, platform.posts.last().second.actions.size)
        notifier.present(NfcQuickLogResultState.Saved(3, "Test Cart A"), false)
        val notification = platform.posts.last().second
        assertTrue(notification.actions.isNullOrEmpty())
        assertEquals(4_000L, notification.timeoutAfter)
        assertTrue(notification.flags and Notification.FLAG_AUTO_CANCEL != 0)
    }

    @Test
    fun noCartOpensCartPickerEvenWhenLocked() {
        notifier.present(NfcQuickLogResultState.NoCart, true)
        val action = platform.posts.single().second.actions.single()
        assertEquals("Choose cart", action.title.toString())
        val intent = shadowOf(action.actionIntent).savedIntent
        assertEquals(MainActivity::class.java.name, intent.component?.className)
        assertEquals("consumption", intent.getStringExtra(EXTRA_START_ROUTE))
        assertTrue(intent.getBooleanExtra(EXTRA_OPEN_CART_PICKER, false))
    }

    @Test
    fun unavailablePlatformDoesNotPost() {
        assertTrue(notifier.canPresent())
        assertTrue(notifier.present(awaiting, false))
        assertEquals(1, platform.posts.size)
        platform.posts.clear()
        platform.enabled = false
        assertFalse(notifier.canPresent())
        assertFalse(notifier.present(awaiting, false))
        assertEquals(0, platform.posts.size)
    }

    @Test
    fun allCopyMatchesPopupAndAvoidsProductWords() {
        val cases = listOf(
            Triple(NfcQuickLogResultState.Parsing, "Reading NFC tag…", "Reading NFC tag…"),
            Triple(NfcQuickLogResultState.DuplicatePresentation, "Tag is still being held near the phone.", "Tag is still being held near the phone."),
            Triple(NfcQuickLogResultState.Staging, "Preparing quick log…", "Preparing quick log…"),
            Triple(awaiting.copy(uses = 2, cartName = null), "2 uses queued for the current Pen cart", "2 uses queued"),
            Triple(NfcQuickLogResultState.Saving(2, null), "Saving 2 uses…", "Saving 2 uses…"),
            Triple(NfcQuickLogResultState.Saved(2, null), "2 uses saved for the current Pen cart", "2 uses saved"),
            Triple(NfcQuickLogResultState.Undone(2), "2 uses not logged", "2 uses not logged"),
            Triple(NfcQuickLogResultState.UnregisteredTag, "This NFC tag is not registered.", "This NFC tag is not registered."),
            Triple(NfcQuickLogResultState.RegistryMismatch, "This tag configuration does not match its registration.", "This tag configuration does not match its registration."),
            Triple(NfcQuickLogResultState.RegistryCorrupt, "NFC tag registrations need recovery in Settings.", "NFC tag registrations need recovery in Settings."),
            Triple(NfcQuickLogResultState.NoCart, "No Pen cart is currently loaded.", "No Pen cart is currently loaded."),
            Triple(NfcQuickLogResultState.PreviousTapBusy, "Previous tap is still saving—try again.", "Previous tap is still saving—try again."),
            Triple(NfcQuickLogResultState.StorageFailure, "The tap could not be saved. Keep it and retry.", "The tap could not be saved. Keep it and retry."),
            Triple(NfcQuickLogResultState.InvalidTag, "Unsupported Cannsheet NFC tag.", "Unsupported Cannsheet NFC tag."),
            Triple(NfcQuickLogResultState.NfcLaunchDisabled, "Launch via NFC is disabled for Cannsheet.", "Launch via NFC is disabled for Cannsheet."),
            Triple(NfcQuickLogResultState.NfcUnavailable, "NFC is unavailable or turned off.", "NFC is unavailable or turned off."),
            Triple(NfcQuickLogResultState.UnexpectedFailure("private detail"), "NFC quick-log could not be completed.", "NFC quick-log could not be completed."),
        )
        val banned = listOf("cannabis", "weed", "thc", "gram")
        fun matches(text: String) = banned.filter { text.contains(it, ignoreCase = true) }
        assertEquals(banned, matches("cannabis weed THC gram"))
        for ((state, unlockedCopy, lockedCopy) in cases) {
            for (locked in listOf(false, true)) {
                val text = nfcQuickLogMessage(state, locked)
                assertEquals(if (locked) lockedCopy else unlockedCopy, text)
                assertTrue(matches(text).isEmpty())
                notifier.present(state, locked)
                val notification = platform.posts.last().second
                assertTrue(matches(notification.text()).isEmpty())
                assertTrue(matches(notification.publicVersion.text()).isEmpty())
            }
        }
    }

    @Test
    fun surfaceRefreshPostsSavedOnlyOnceAfterPayloadLeavesOutbox() {
        notifier.present(awaiting, false)
        val queuedCount = platform.posts.size
        notifier.onNfcSurfaceRefresh("c1")
        assertEquals(queuedCount, platform.posts.size)
        notifier.onNfcSurfaceRefresh(null)
        assertEquals(queuedCount + 1, platform.posts.size)
        assertEquals("3 uses saved for Test Cart A", platform.posts.last().second.text())
        notifier.onNfcSurfaceRefresh(null)
        assertEquals(queuedCount + 1, platform.posts.size)
    }
}
