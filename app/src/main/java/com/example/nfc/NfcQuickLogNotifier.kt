package com.example.nfc

import android.Manifest
import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.nfc.NfcAdapter
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R
import com.example.domain.EXTRA_OPEN_CART_PICKER
import com.example.domain.EXTRA_START_ROUTE

const val NFC_QUICK_LOG_CHANNEL_ID = "cannsheet-nfc-quick-log"
const val NFC_QUICK_LOG_NOTIFICATION_ID = 1002

internal data class NfcQuickLogActionData(val commitId: String, val uses: Int, val cartName: String?)

/** One replaceable, lock-safe result for the NFC surface. No durable payload lives here. */
class NfcQuickLogNotifier internal constructor(
    context: Context,
    private val platform: NfcQuickLogNotificationPlatform,
) {
    constructor(context: Context) : this(
        context.applicationContext,
        AndroidNfcQuickLogNotificationPlatform(context.applicationContext),
    )

    private val context = context.applicationContext

    fun canPresent(): Boolean = try {
        platform.canPost()
    } catch (_: RuntimeException) {
        false
    }

    fun present(state: NfcQuickLogResultState, locked: Boolean): Boolean =
        presentActionResult(state, locked, null)

    internal fun presentActionResult(
        state: NfcQuickLogResultState,
        locked: Boolean,
        actionData: NfcQuickLogActionData?,
    ): Boolean = synchronized(recordLock) {
        try {
            if (!platform.canPost()) return false
            platform.ensureChannel()
            if (!platform.canPost()) return false
            platform.post(NFC_QUICK_LOG_NOTIFICATION_ID, buildNotification(state, locked, actionData))
            when (state) {
                is NfcQuickLogResultState.AwaitingUndo -> lastAwaiting = AwaitingRecord(state.commitId, state.uses, state.cartName, locked)
                is NfcQuickLogResultState.Saved, is NfcQuickLogResultState.Undone -> lastAwaiting = null
                else -> Unit
            }
            true
        } catch (_: RuntimeException) {
            false
        }
    }

    fun onNfcSurfaceRefresh(pendingCommitId: String?) {
        synchronized(recordLock) {
            val recorded = lastAwaiting ?: return
            if (recorded.commitId == pendingCommitId) return
            val locked = recorded.locked ||
                context.getSystemService(KeyguardManager::class.java)?.isDeviceLocked == true
            present(NfcQuickLogResultState.Saved(recorded.uses, recorded.cartName), locked)
            lastAwaiting = null
        }
    }

    private fun buildNotification(
        state: NfcQuickLogResultState,
        locked: Boolean,
        actionData: NfcQuickLogActionData?,
    ): Notification {
        val publicVersion = baseBuilder(nfcQuickLogMessage(state, true)).build()
        val builder = baseBuilder(nfcQuickLogMessage(state, locked))
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion)
        when (state) {
            is NfcQuickLogResultState.AwaitingUndo -> {
                val data = NfcQuickLogActionData(state.commitId, state.uses, state.cartName)
                builder.addAction(0, "Undo", broadcastAction(ACTION_NFC_UNDO, data, locked))
                builder.addAction(0, "Submit now", broadcastAction(ACTION_NFC_SUBMIT, data, locked))
                builder.setTimeoutAfter(60_000)
            }
            is NfcQuickLogResultState.Saved, is NfcQuickLogResultState.Undone ->
                builder.setTimeoutAfter(4_000).setAutoCancel(true)
            NfcQuickLogResultState.StorageFailure, NfcQuickLogResultState.PreviousTapBusy ->
                builder.addAction(0, "Retry", broadcastAction(ACTION_NFC_RETRY, actionData, locked))
            NfcQuickLogResultState.NoCart -> builder.addAction(
                0, "Choose cart", activityAction(
                    Intent(context, MainActivity::class.java)
                        .putExtra(EXTRA_START_ROUTE, "consumption")
                        .putExtra(EXTRA_OPEN_CART_PICKER, true),
                    10,
                ),
            )
            NfcQuickLogResultState.UnregisteredTag,
            NfcQuickLogResultState.RegistryMismatch,
            NfcQuickLogResultState.RegistryCorrupt,
            -> builder.addAction(
                0, "Open NFC tag settings", activityAction(
                    Intent(context, MainActivity::class.java).putExtra(EXTRA_START_ROUTE, "settings"),
                    11,
                ),
            )
            NfcQuickLogResultState.NfcUnavailable -> builder.addAction(
                0, "Open NFC settings", activityAction(settingsIntent(Settings.ACTION_NFC_SETTINGS), 12),
            )
            NfcQuickLogResultState.NfcLaunchDisabled -> builder.addAction(
                0, "Open Launch via NFC", activityAction(
                    settingsIntent(
                        if (Build.VERSION.SDK_INT >= 36) NfcAdapter.ACTION_CHANGE_TAG_INTENT_PREFERENCE
                        else Settings.ACTION_NFC_SETTINGS,
                    ),
                    13,
                ),
            )
            else -> Unit
        }
        return builder.build()
    }

    private fun baseBuilder(message: String): NotificationCompat.Builder =
        NotificationCompat.Builder(context, NFC_QUICK_LOG_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_queue_alert_status)
            .setColor(0xFF145F58.toInt())
            .setContentTitle(context.getString(R.string.nfc_quick_log_notification_title))
            .setContentText(message)
            .setOnlyAlertOnce(false)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_HIGH)

    private fun broadcastAction(action: String, data: NfcQuickLogActionData?, locked: Boolean): PendingIntent {
        val intent = Intent(context, NfcQuickLogActionReceiver::class.java).setAction(action)
        data?.let {
            intent.putExtra("commitId", it.commitId).putExtra("uses", it.uses)
            if (!locked) intent.putExtra("cartName", it.cartName)
        }
        return PendingIntent.getBroadcast(
            context, NFC_QUICK_LOG_NOTIFICATION_ID, intent,
            PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun activityAction(intent: Intent, requestCode: Int): PendingIntent = PendingIntent.getActivity(
        context, requestCode, intent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun settingsIntent(action: String): Intent {
        val intent = Intent(action)
        // Resolve before creating the immutable PendingIntent so every action is explicit.
        val target = intent.resolveActivity(context.packageManager)
            ?: Intent(Settings.ACTION_NFC_SETTINGS).resolveActivity(context.packageManager)
            ?: Intent(Settings.ACTION_SETTINGS).resolveActivity(context.packageManager)
            ?: ComponentName("com.android.settings", "com.android.settings.Settings")
        return intent.setComponent(target)
    }

    private data class AwaitingRecord(
        val commitId: String,
        val uses: Int,
        val cartName: String?,
        val locked: Boolean,
    )

    companion object {
        private val recordLock = Any()
        private var lastAwaiting: AwaitingRecord? = null
    }
}

internal interface NfcQuickLogNotificationPlatform {
    fun canPost(): Boolean
    fun ensureChannel()
    fun post(notificationId: Int, notification: Notification)
}

private class AndroidNfcQuickLogNotificationPlatform(private val context: Context) : NfcQuickLogNotificationPlatform {
    private val compatManager = NotificationManagerCompat.from(context)
    private val platformManager = context.getSystemService(NotificationManager::class.java)

    override fun canPost(): Boolean {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return false
        if (!compatManager.areNotificationsEnabled()) return false
        return Build.VERSION.SDK_INT < 26 ||
            platformManager.getNotificationChannel(NFC_QUICK_LOG_CHANNEL_ID)?.importance != NotificationManager.IMPORTANCE_NONE
    }

    override fun ensureChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        platformManager.createNotificationChannel(
            NotificationChannel(
                NFC_QUICK_LOG_CHANNEL_ID,
                context.getString(R.string.nfc_quick_log_channel_name),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = context.getString(R.string.nfc_quick_log_channel_description)
                setSound(null, null)
                enableVibration(false)
            },
        )
    }

    @SuppressLint("MissingPermission")
    override fun post(notificationId: Int, notification: Notification) {
        compatManager.notify(notificationId, notification)
    }
}

internal fun nfcQuickLogMessage(state: NfcQuickLogResultState, locked: Boolean): String = when (state) {
    NfcQuickLogResultState.Parsing -> "Reading NFC tag…"
    NfcQuickLogResultState.DuplicatePresentation -> "Tag is still being held near the phone."
    NfcQuickLogResultState.Staging -> "Preparing quick log…"
    is NfcQuickLogResultState.AwaitingUndo -> if (locked) {
        "${state.uses} uses queued"
    } else {
        "${state.uses} uses queued for ${state.cartName ?: "the current Pen cart"}"
    }
    is NfcQuickLogResultState.Saving -> "Saving ${state.uses} uses…"
    is NfcQuickLogResultState.Saved -> if (locked) {
        "${state.uses} uses saved"
    } else {
        "${state.uses} uses saved for ${state.cartName ?: "the current Pen cart"}"
    }
    is NfcQuickLogResultState.Undone -> "${state.uses} uses not logged"
    NfcQuickLogResultState.UnregisteredTag -> "This NFC tag is not registered."
    NfcQuickLogResultState.RegistryMismatch -> "This tag configuration does not match its registration."
    NfcQuickLogResultState.RegistryCorrupt -> "NFC tag registrations need recovery in Settings."
    NfcQuickLogResultState.NoCart -> "No Pen cart is currently loaded."
    NfcQuickLogResultState.PreviousTapBusy -> "Previous tap is still saving—try again."
    NfcQuickLogResultState.StorageFailure -> "The tap could not be saved. Keep it and retry."
    NfcQuickLogResultState.InvalidTag -> "Unsupported Cannsheet NFC tag."
    NfcQuickLogResultState.NfcLaunchDisabled -> "Launch via NFC is disabled for Cannsheet."
    NfcQuickLogResultState.NfcUnavailable -> "NFC is unavailable or turned off."
    is NfcQuickLogResultState.UnexpectedFailure -> "NFC quick-log could not be completed."
}
