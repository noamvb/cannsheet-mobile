package com.example.notifications

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.MainActivity
import com.example.R
import com.example.data.sync.QUEUE_STUCK_THRESHOLD_MILLIS
import com.example.data.sync.QueueAlert
import com.example.data.sync.QueueAlertReason
import com.example.domain.EXTRA_START_ROUTE
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class QueueAlertNotifierTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val notificationManager: NotificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    @Before
    fun setUp() {
        notificationManager.cancel(QUEUE_ALERT_NOTIFICATION_ID)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notificationManager.deleteNotificationChannel(QUEUE_ALERT_CHANNEL_ID)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(
                context.packageName,
                Manifest.permission.POST_NOTIFICATIONS,
            )
        }
    }

    @After
    fun tearDown() {
        notificationManager.cancel(QUEUE_ALERT_NOTIFICATION_ID)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notificationManager.deleteNotificationChannel(QUEUE_ALERT_CHANNEL_ID)
        }
    }

    @Test
    fun postsReplacesAndClearsOneStablePrivacyPreservingNotification() {
        val notifier = QueueAlertNotifier(context)

        assertTrue(notifier.canPresent())
        assertTrue(notifier.tryPresent(alert(QueueAlertReason.STUCK_QUEUE)))
        var active = waitForActiveNotificationCount(1).single()
        assertEquals(QUEUE_ALERT_NOTIFICATION_ID, active.id)
        assertEquals(
            context.getString(R.string.queue_alert_stuck_title),
            active.notification.extras.getCharSequence(Notification.EXTRA_TITLE),
        )
        assertTrue(active.notification.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
        assertFalse(active.notification.extras.toString().contains("Blue Dream"))

        assertTrue(notifier.tryPresent(alert(QueueAlertReason.ENVIRONMENT_MISMATCH)))
        active = waitForNotificationTitle(
            context.getString(R.string.queue_alert_environment_title),
        )
        assertEquals(QUEUE_ALERT_NOTIFICATION_ID, active.id)
        assertEquals(
            context.getString(R.string.queue_alert_environment_title),
            active.notification.extras.getCharSequence(Notification.EXTRA_TITLE),
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = notificationManager.getNotificationChannel(QUEUE_ALERT_CHANNEL_ID)
            assertEquals(NotificationManager.IMPORTANCE_DEFAULT, channel.importance)
            assertEquals(
                context.getString(R.string.queue_alert_channel_description),
                channel.description,
            )
            assertTrue(channel.canShowBadge())
        }

        notifier.clear()
        waitForActiveNotificationCount(0)
    }

    @Test
    fun contentIntentTargetsTheSettingsRouteWithoutCollidingWithWidgetIntents() {
        val intent = queueAlertIntent(context)

        assertEquals(MainActivity::class.java.name, intent.component?.className)
        assertEquals(ACTION_OPEN_QUEUE_ALERT, intent.action)
        assertEquals("cannsheet://queue-alert", intent.dataString)
        assertEquals("settings", intent.getStringExtra(EXTRA_START_ROUTE))
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_CLEAR_TOP != 0)
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_SINGLE_TOP != 0)
    }

    @Test
    fun everyReasonMapsToResourceBackedNonSensitiveCopy() {
        val expected = mapOf(
            QueueAlertReason.STUCK_QUEUE to (
                R.string.queue_alert_stuck_title to
                    "2 entries are waiting to sync. This phone has had unsent " +
                    "entries for over a day. Open Cannsheet."
                ),
            QueueAlertReason.PARTIAL_REJECTIONS to
                (R.string.queue_alert_rejected_title to
                    context.getString(R.string.queue_alert_rejected_body)),
            QueueAlertReason.BACKEND_CAPABILITY_PENDING to
                (R.string.queue_alert_capability_title to
                    context.getString(R.string.queue_alert_capability_body)),
            QueueAlertReason.ENVIRONMENT_MISMATCH to
                (R.string.queue_alert_environment_title to
                    context.getString(R.string.queue_alert_environment_body)),
        )

        expected.forEach { (reason, expectedCopy) ->
            val actual = queueAlertNotificationCopy(context, alert(reason))
            assertEquals(context.getString(expectedCopy.first), actual.first)
            assertEquals(expectedCopy.second, actual.second)
            assertFalse(actual.first.contains("Blue Dream"))
            assertFalse(actual.second.contains("Blue Dream"))
        }
    }

    @Test
    fun platformBlocksAndFailuresAreTotalAndReportedAsNotPosted() {
        val unavailable = FakePlatform(canPostResults = mutableListOf(false))
        val unavailableNotifier = QueueAlertNotifier(context, unavailable)
        assertFalse(unavailableNotifier.canPresent())
        assertFalse(unavailableNotifier.tryPresent(alert()))
        assertEquals(0, unavailable.ensureChannelCalls)
        assertEquals(0, unavailable.postCalls)

        val changed = FakePlatform(canPostResults = mutableListOf(true, false))
        assertFalse(QueueAlertNotifier(context, changed).tryPresent(alert()))
        assertEquals(1, changed.ensureChannelCalls)
        assertEquals(0, changed.postCalls)

        val throwing = FakePlatform(
            canPostResults = mutableListOf(true, true),
            postError = SecurityException("permission changed"),
            cancelError = IllegalStateException("manager unavailable"),
        )
        val throwingNotifier = QueueAlertNotifier(context, throwing)
        assertFalse(throwingNotifier.tryPresent(alert()))
        throwingNotifier.clear()
        assertEquals(1, throwing.postCalls)
        assertEquals(1, throwing.cancelCalls)
    }

    private fun waitForActiveNotificationCount(expected: Int): List<android.service.notification.StatusBarNotification> {
        val deadline = SystemClock.uptimeMillis() + 2_000L
        var active = notificationManager.activeNotifications.toList()
            .filter { it.id == QUEUE_ALERT_NOTIFICATION_ID }
        while (active.size != expected && SystemClock.uptimeMillis() < deadline) {
            SystemClock.sleep(25L)
            active = notificationManager.activeNotifications.toList()
                .filter { it.id == QUEUE_ALERT_NOTIFICATION_ID }
        }
        assertEquals(expected, active.size)
        return active
    }

    private fun waitForNotificationTitle(
        expectedTitle: String,
    ): android.service.notification.StatusBarNotification {
        val deadline = SystemClock.uptimeMillis() + 2_000L
        var active: android.service.notification.StatusBarNotification? = null
        while (active == null && SystemClock.uptimeMillis() < deadline) {
            active = notificationManager.activeNotifications.firstOrNull { notification ->
                notification.id == QUEUE_ALERT_NOTIFICATION_ID &&
                    notification.notification.extras
                        .getCharSequence(Notification.EXTRA_TITLE)
                        ?.toString() == expectedTitle
            }
            if (active == null) SystemClock.sleep(25L)
        }
        return requireNotNull(active) { "Expected notification title: $expectedTitle" }
    }

    private class FakePlatform(
        private val canPostResults: MutableList<Boolean>,
        private val postError: RuntimeException? = null,
        private val cancelError: RuntimeException? = null,
    ) : QueueAlertNotificationPlatform {
        var ensureChannelCalls = 0
        var postCalls = 0
        var cancelCalls = 0

        override fun canPost(): Boolean = if (canPostResults.size > 1) {
            canPostResults.removeAt(0)
        } else {
            canPostResults.single()
        }

        override fun ensureChannel() {
            ensureChannelCalls += 1
        }

        override fun post(notificationId: Int, notification: Notification) {
            postCalls += 1
            postError?.let { throw it }
        }

        override fun cancel(notificationId: Int) {
            cancelCalls += 1
            cancelError?.let { throw it }
        }
    }

    private companion object {
        fun alert(reason: QueueAlertReason = QueueAlertReason.STUCK_QUEUE) = QueueAlert(
            reason = reason,
            pendingActionCount = 2,
            queueAgeMillis = QUEUE_STUCK_THRESHOLD_MILLIS,
        )
    }
}
