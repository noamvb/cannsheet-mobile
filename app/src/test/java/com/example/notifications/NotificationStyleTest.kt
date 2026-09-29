package com.example.notifications

import android.app.Notification
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.data.sync.QueueAlert
import com.example.data.sync.QueueAlertReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class NotificationStyleTest {

    private class CapturingPlatform : QueueAlertNotificationPlatform {
        var postedNotification: Notification? = null
        override fun canPost(): Boolean = true
        override fun ensureChannel() {}
        override fun post(notificationId: Int, notification: Notification) {
            postedNotification = notification
        }
        override fun cancel(notificationId: Int) {}
    }

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun buildViaProduction(reason: QueueAlertReason, count: Int = 1): Notification {
        val platform = CapturingPlatform()
        val notifier = QueueAlertNotifier(context, platform)
        val presented = notifier.tryPresent(
            QueueAlert(
                reason = reason,
                pendingActionCount = count,
                queueAgeMillis = 0L,
            ),
        )
        assertTrue("tryPresent should succeed with CapturingPlatform", presented)
        return checkNotNull(platform.postedNotification) { "Notification should be posted" }
    }

    private fun findBannedWords(text: String?): List<String> {
        if (text == null) return emptyList()
        val banned = listOf("cannabis", "weed", "thc", "gram")
        return banned.filter { keyword ->
            text.contains(keyword, ignoreCase = true)
        }
    }

    @Test
    fun notificationColorEqualsLedgerGreen() {
        val notification = buildViaProduction(QueueAlertReason.STUCK_QUEUE)
        val expectedColor = 0xFF145F58.toInt()
        assertEquals(expectedColor, notification.color)
    }

    @Test
    fun notificationTitleAndTextContainNoProductWords() {
        // Anti-vacuity check: verify probe detects all banned keywords when present
        val probeMatches = findBannedWords("Sample 1 gram cannabis flower weed with high THC content")
        assertEquals(
            listOf("cannabis", "weed", "thc", "gram"),
            probeMatches,
        )

        // Production check: across all reasons and plural counts
        for (reason in QueueAlertReason.entries) {
            for (count in listOf(1, 5)) {
                val notification = buildViaProduction(reason, count)
                val extras = notification.extras
                val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
                val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
                val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()

                val titleMatches = findBannedWords(title)
                val textMatches = findBannedWords(text)
                val bigTextMatches = findBannedWords(bigText)

                assertTrue(
                    "Title for $reason (count=$count) contained banned words: $titleMatches in '$title'",
                    titleMatches.isEmpty(),
                )
                assertTrue(
                    "Text for $reason (count=$count) contained banned words: $textMatches in '$text'",
                    textMatches.isEmpty(),
                )
                assertTrue(
                    "BigText for $reason (count=$count) contained banned words: $bigTextMatches in '$bigText'",
                    bigTextMatches.isEmpty(),
                )
            }
        }
    }
}
