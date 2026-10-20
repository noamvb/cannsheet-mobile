package com.example.nfc

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.widget.DeferredPenInputKind
import com.example.widget.PenWidgetCommitPayload
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

internal const val ACTION_NFC_UNDO = "com.noamv.cannsheet.mobile.nfc.UNDO"
internal const val ACTION_NFC_SUBMIT = "com.noamv.cannsheet.mobile.nfc.SUBMIT"
internal const val ACTION_NFC_RETRY = "com.noamv.cannsheet.mobile.nfc.RETRY"

internal interface NfcQuickLogOperations {
    suspend fun undo(commitId: String): Boolean
    suspend fun submitNow(commitId: String): Boolean
    suspend fun pending(): PenWidgetCommitPayload?
}

class NfcQuickLogActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pendingResult = goAsync()
        val appContext = context.applicationContext
        val locked = appContext.getSystemService(KeyguardManager::class.java)?.isDeviceLocked == true
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val notifier = NfcQuickLogNotifier(appContext)
                runCatching {
                    NfcQuickLogActionHandler(NfcQuickLogCoordinator.forContext(appContext), notifier)
                        .handle(intent, locked)
                }.onFailure { notifier.present(NfcQuickLogResultState.UnexpectedFailure(), locked) }
            } finally {
                pendingResult.finish()
            }
        }
    }
}

/** Notification actions only operate on the coordinator's existing durable outbox boundary. */
internal class NfcQuickLogActionHandler(
    private val ops: NfcQuickLogOperations,
    private val notifier: NfcQuickLogNotifier,
) {
    suspend fun handle(intent: Intent, locked: Boolean) {
        val action = intent.action
        if (action !in setOf(ACTION_NFC_UNDO, ACTION_NFC_SUBMIT, ACTION_NFC_RETRY)) return
        var id = intent.getStringExtra("commitId")?.takeIf { it.isNotBlank() }
        var uses = intent.getIntExtra("uses", 0)
        var cartName = if (locked) null else intent.getStringExtra("cartName")
        if (action == ACTION_NFC_RETRY && id == null) {
            val pending = ops.pending()
            val recoveredUses = pending
                ?.takeIf { it.inputKind == DeferredPenInputKind.DIRECT_USES }
                ?.uses
                ?.takeIf { it.isFinite() && it.toInt().toDouble() == it }
                ?.toInt()
                ?.takeIf { it in NfcQuickLogContract.MIN_USES..NfcQuickLogContract.MAX_USES }
            if (pending != null && recoveredUses != null) {
                id = pending.commitId
                uses = recoveredUses
                cartName = null
            }
        }
        if (id == null || uses !in NfcQuickLogContract.MIN_USES..NfcQuickLogContract.MAX_USES) {
            notifier.present(NfcQuickLogResultState.UnexpectedFailure(), locked)
            return
        }
        val data = NfcQuickLogActionData(id, uses, cartName)
        if (action == ACTION_NFC_UNDO) {
            val next = if (ops.undo(id)) {
                NfcQuickLogResultState.Undone(uses)
            } else if (ops.pending()?.commitId != id) {
                NfcQuickLogResultState.Saved(uses, cartName)
            } else {
                NfcQuickLogResultState.StorageFailure
            }
            notifier.presentActionResult(next, locked, data)
        } else {
            notifier.present(NfcQuickLogResultState.Saving(uses, cartName), locked)
            // The undo-window timer may commit the payload concurrently; submitNow then finds no
            // claim and returns false even though the Room write succeeded.
            val next = if (ops.submitNow(id) || ops.pending()?.commitId != id) {
                NfcQuickLogResultState.Saved(uses, cartName)
            } else {
                NfcQuickLogResultState.StorageFailure
            }
            notifier.presentActionResult(next, locked, data)
        }
    }
}
