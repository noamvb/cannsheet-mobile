package com.example.notifications

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.example.MainActivity
import com.example.R
import com.example.data.sync.QueueAlert
import com.example.data.sync.QueueAlertPresenter
import com.example.data.sync.QueueAlertReason
import com.example.domain.EXTRA_START_ROUTE

const val QUEUE_ALERT_CHANNEL_ID = "cannsheet-queue-alerts"
const val QUEUE_ALERT_NOTIFICATION_ID = 1001

internal const val ACTION_OPEN_QUEUE_ALERT =
    "com.noamv.cannsheet.mobile.notification.OPEN_QUEUE_ALERT"

/** Android implementation of Cannsheet's one privacy-preserving queue alert. */
class QueueAlertNotifier internal constructor(
    context: Context,
    private val platform: QueueAlertNotificationPlatform,
) : QueueAlertPresenter {
    constructor(context: Context) : this(
        context = context.applicationContext,
        platform = AndroidQueueAlertNotificationPlatform(context.applicationContext),
    )

    private val context = context.applicationContext

    override fun canPresent(): Boolean = try {
        platform.canPost()
    } catch (_: RuntimeException) {
        false
    }

    override fun tryPresent(alert: QueueAlert): Boolean {
        return try {
            if (!platform.canPost()) return false
            platform.ensureChannel()
            // Permission, app settings, or channel importance can change during the first check.
            if (!platform.canPost()) return false
            platform.post(
                notificationId = QUEUE_ALERT_NOTIFICATION_ID,
                notification = buildNotification(alert),
            )
            true
        } catch (_: RuntimeException) {
            false
        }
    }

    override fun clear() {
        try {
            platform.cancel(QUEUE_ALERT_NOTIFICATION_ID)
        } catch (_: RuntimeException) {
            // Advisory only. A later reconciliation can try again.
        }
    }

    private fun buildNotification(alert: QueueAlert): Notification {
        val (title, body) = queueAlertNotificationCopy(context, alert)
        return NotificationCompat.Builder(context, QUEUE_ALERT_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_queue_alert_status)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(queueAlertPendingIntent(context))
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
    }
}

internal interface QueueAlertNotificationPlatform {
    fun canPost(): Boolean

    fun ensureChannel()

    fun post(notificationId: Int, notification: Notification)

    fun cancel(notificationId: Int)
}

private class AndroidQueueAlertNotificationPlatform(
    private val context: Context,
) : QueueAlertNotificationPlatform {
    private val compatManager = NotificationManagerCompat.from(context)
    private val platformManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    override fun canPost(): Boolean {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        if (!compatManager.areNotificationsEnabled()) return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = platformManager.getNotificationChannel(QUEUE_ALERT_CHANNEL_ID)
            channel == null || channel.importance != NotificationManager.IMPORTANCE_NONE
        } else {
            true
        }
    }

    override fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            QUEUE_ALERT_CHANNEL_ID,
            context.getString(R.string.queue_alert_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = context.getString(R.string.queue_alert_channel_description)
            setShowBadge(true)
        }
        platformManager.createNotificationChannel(channel)
    }

    @SuppressLint("MissingPermission")
    override fun post(notificationId: Int, notification: Notification) {
        // canPost() is checked immediately before this call; the notifier still catches a race.
        compatManager.notify(notificationId, notification)
    }

    override fun cancel(notificationId: Int) {
        compatManager.cancel(notificationId)
    }
}

internal fun queueAlertIntent(context: Context): Intent =
    Intent(context, MainActivity::class.java).apply {
        action = ACTION_OPEN_QUEUE_ALERT
        data = "cannsheet://queue-alert".toUri()
        putExtra(EXTRA_START_ROUTE, "settings")
        addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP,
        )
    }

private fun queueAlertPendingIntent(context: Context): PendingIntent = PendingIntent.getActivity(
    context,
    QUEUE_ALERT_NOTIFICATION_ID,
    queueAlertIntent(context),
    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
)

internal fun queueAlertNotificationCopy(
    context: Context,
    alert: QueueAlert,
): Pair<String, String> = when (alert.reason) {
    QueueAlertReason.STUCK_QUEUE ->
        context.getString(R.string.queue_alert_stuck_title) to
            context.resources.getQuantityString(
                R.plurals.queue_alert_stuck_body,
                alert.pendingActionCount,
                alert.pendingActionCount,
            )

    QueueAlertReason.PARTIAL_REJECTIONS ->
        context.getString(R.string.queue_alert_rejected_title) to
            context.getString(R.string.queue_alert_rejected_body)

    QueueAlertReason.BACKEND_CAPABILITY_PENDING ->
        context.getString(R.string.queue_alert_capability_title) to
            context.getString(R.string.queue_alert_capability_body)

    QueueAlertReason.ENVIRONMENT_MISMATCH ->
        context.getString(R.string.queue_alert_environment_title) to
            context.getString(R.string.queue_alert_environment_body)
}
