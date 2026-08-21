package com.example.nfc

import android.app.KeyguardManager
import android.content.Intent
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.example.MainActivity
import com.example.domain.EXTRA_OPEN_CART_PICKER
import com.example.domain.EXTRA_START_ROUTE
import com.example.ui.theme.MyApplicationTheme
import com.example.widget.COMMIT_DELAY_MILLIS
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Small, dedicated NFC result surface. It intentionally does not reuse MainActivity: the
 * handler parses untrusted external input, may be shown over the keyguard, and must not put the
 * full application or private cart data above the lock screen.
 *
 * When target SDK moves to 37, audit this dedicated handler for Android 17's NFC-dispatch
 * permission and stopped-app delivery changes; do not apply that permission while targeting 36
 * without an API-24–36 compatibility check.
 */
class NfcQuickLogActivity : ComponentActivity() {
    private val presentationGate = NfcPresentationGate.processLocal()
    private val coordinator by lazy { NfcQuickLogCoordinator.forContext(this) }
    private var resultState by mutableStateOf<NfcQuickLogResultState>(NfcQuickLogResultState.Parsing)
    private var locked by mutableStateOf(false)
    private var activeTagId: String? = null
    private var watcher: Job? = null
    private var presentationFallbackExpiry: Job? = null
    private var retryAwaiting: NfcQuickLogResultState.AwaitingUndo? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureLockSafeWindow()
        updateLockState()
        setContent {
            MyApplicationTheme {
                NfcQuickLogResultContent(
                    state = resultState,
                    locked = locked,
                    onUndo = ::undo,
                    onSubmitNow = ::submitNow,
                    onRetry = ::retrySave,
                    onChooseCart = ::openCartPicker,
                    onOpenSettings = ::openSettings,
                    onOpenNfcSettings = ::openNfcSettings,
                    onOpenLaunchPreference = ::openLaunchPreference,
                    onClose = { finish() },
                )
            }
        }

        if (savedInstanceState == null) {
            consumeIntent(intent)
        } else {
            restorePendingOperation(savedInstanceState)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        updateLockState()
    }

    override fun onDestroy() {
        presentationFallbackExpiry?.cancel()
        watcher?.cancel()
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) updateLockState()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        val awaiting = resultState as? NfcQuickLogResultState.AwaitingUndo
        awaiting?.let {
            outState.putString(KEY_COMMIT_ID, it.commitId)
            outState.putInt(KEY_USES, it.uses)
            // Do not copy private product/cart data into recreation state while the keyguard is
            // active. The locked result surface intentionally carries quantity only.
            if (!locked) outState.putString(KEY_CART_NAME, it.cartName)
        }
    }

    private fun consumeIntent(incoming: Intent) {
        val adapter = NfcAdapter.getDefaultAdapter(this)
        if (adapter == null) {
            clearNfcIntent(incoming)
            resultState = NfcQuickLogResultState.NfcUnavailable
            return
        }
        if (!adapter.isEnabled) {
            clearNfcIntent(incoming)
            resultState = NfcQuickLogResultState.NfcUnavailable
            return
        }
        if (Build.VERSION.SDK_INT >= 36 &&
            adapter.isTagIntentAppPreferenceSupported() &&
            !adapter.isTagIntentAllowed()
        ) {
            clearNfcIntent(incoming)
            resultState = NfcQuickLogResultState.NfcLaunchDisabled
            return
        }

        val parsed = NfcQuickLogContract.parse(incoming, packageName)
        val tag = extractTag(incoming)
        clearNfcIntent(incoming)
        if (parsed == null) {
            resultState = NfcQuickLogResultState.InvalidTag
            return
        }
        if (!presentationGate.begin(parsed.tagId)) {
            // A second dispatch while the same physical tag remains present must not replace
            // the accepted operation or reset its Undo countdown. The existing result is the
            // authoritative UI for this continuous presentation.
            return
        }
        activeTagId = parsed.tagId
        resultState = NfcQuickLogResultState.Staging
        presentationFallbackExpiry?.cancel()
        presentationFallbackExpiry = null
        if (!establishRemovalCallback(adapter, tag, parsed.tagId)) {
            // Some tags/devices do not provide a usable removal callback. Keep the fallback
            // deliberately short: it is only a platform-limitation guard, never the event-ID
            // or Room correctness boundary. A normal remove-and-retap should still be accepted.
            presentationFallbackExpiry = lifecycleScope.launch {
                delay(PRESENTATION_FALLBACK_EXPIRY_MILLIS)
                presentationGate.removed(parsed.tagId)
                if (activeTagId == parsed.tagId) activeTagId = null
            }
        }
        watcher?.cancel()
        watcher = lifecycleScope.launch {
            val next = runCatching { coordinator.accept(parsed) }
                .getOrElse { NfcQuickLogResultState.UnexpectedFailure(it.message) }
            resultState = next
            if (next is NfcQuickLogResultState.AwaitingUndo) {
                retryAwaiting = next
                watchCommit(next)
            } else if (next !is NfcQuickLogResultState.PreviousTapBusy) {
                retryAwaiting = null
            }
        }
    }

    private fun restorePendingOperation(saved: Bundle) {
        val savedCommitId = saved.getString(KEY_COMMIT_ID)
        val savedUses = saved.getInt(KEY_USES, 0)
        val savedCartName = saved.getString(KEY_CART_NAME)
        watcher?.cancel()
        watcher = lifecycleScope.launch {
            val pending = coordinator.pending()
            val pendingUses = pending
                ?.takeIf { it.inputKind == com.example.widget.DeferredPenInputKind.DIRECT_USES }
                ?.uses
                ?.takeIf { it.isFinite() && it.toInt().toDouble() == it }
                ?.toInt()
            if (
                pending != null &&
                pendingUses != null &&
                pendingUses in NfcQuickLogContract.MIN_USES..NfcQuickLogContract.MAX_USES
            ) {
                resultState = NfcQuickLogResultState.AwaitingUndo(
                    commitId = pending.commitId,
                    uses = pendingUses,
                    cartName = null,
                    submittedAtEpochMillis = pending.submittedAtEpochMillis,
                )
                retryAwaiting = resultState as NfcQuickLogResultState.AwaitingUndo
                watchCommit(resultState as NfcQuickLogResultState.AwaitingUndo)
            } else if (
                savedCommitId != null &&
                savedUses in NfcQuickLogContract.MIN_USES..NfcQuickLogContract.MAX_USES
            ) {
                resultState = NfcQuickLogResultState.Saved(savedUses, savedCartName, offline = true)
            }
        }
    }

    private suspend fun watchCommit(awaiting: NfcQuickLogResultState.AwaitingUndo) {
        val deadline = awaiting.submittedAtEpochMillis + COMMIT_DELAY_MILLIS + 2_000L
        while (currentCoroutineContext().isActive) {
            val pending = coordinator.pending()
            if (pending?.commitId != awaiting.commitId) {
                retryAwaiting = null
                resultState = NfcQuickLogResultState.Saved(
                    uses = awaiting.uses,
                    cartName = awaiting.cartName,
                    offline = true,
                )
                return
            }
            if (System.currentTimeMillis() >= deadline) {
                retryAwaiting = awaiting
                resultState = NfcQuickLogResultState.StorageFailure
                return
            }
            delay(250L)
        }
    }

    private fun undo() {
        val awaiting = resultState as? NfcQuickLogResultState.AwaitingUndo ?: return
        watcher?.cancel()
        watcher = null
        lifecycleScope.launch {
            if (coordinator.undo(awaiting.commitId)) {
                retryAwaiting = null
                resultState = NfcQuickLogResultState.Undone(awaiting.uses)
            } else {
                resultState = NfcQuickLogResultState.Saving(awaiting.uses, awaiting.cartName)
                watchCommit(awaiting)
            }
        }
    }

    private fun submitNow() {
        val awaiting = (resultState as? NfcQuickLogResultState.AwaitingUndo) ?: retryAwaiting ?: return
        watcher?.cancel()
        watcher = null
        resultState = NfcQuickLogResultState.Saving(awaiting.uses, awaiting.cartName)
        lifecycleScope.launch {
            val next = if (coordinator.submitNow(awaiting.commitId)) {
                retryAwaiting = null
                NfcQuickLogResultState.Saved(awaiting.uses, awaiting.cartName, offline = true)
            } else {
                retryAwaiting = awaiting
                NfcQuickLogResultState.StorageFailure
            }
            resultState = next
            if (resultState is NfcQuickLogResultState.StorageFailure) {
                watchCommit(awaiting)
            }
        }
    }

    private fun retrySave() {
        if (retryAwaiting != null) submitNow()
    }

    private fun openCartPicker() {
        if (locked) return
        startActivity(
            Intent(this, MainActivity::class.java)
                .putExtra(EXTRA_START_ROUTE, "consumption")
                .putExtra(EXTRA_OPEN_CART_PICKER, true),
        )
        finish()
    }

    private fun openSettings() {
        if (locked) return
        startActivity(
            Intent(this, MainActivity::class.java)
                .putExtra(EXTRA_START_ROUTE, "settings"),
        )
        finish()
    }

    private fun openNfcSettings() {
        if (locked) return
        val nfcIntent = Intent(Settings.ACTION_NFC_SETTINGS)
        val fallback = Intent(Settings.ACTION_SETTINGS)
        val target = nfcIntent.takeIf { it.resolveActivity(packageManager) != null } ?: fallback
        runCatching { startActivity(target) }
    }

    private fun openLaunchPreference() {
        if (locked) return
        if (Build.VERSION.SDK_INT >= 36) {
            val preferenceIntent = Intent(NfcAdapter.ACTION_CHANGE_TAG_INTENT_PREFERENCE)
            if (preferenceIntent.resolveActivity(packageManager) != null) {
                runCatching { startActivity(preferenceIntent) }
            } else {
                openNfcSettings()
            }
        } else {
            openNfcSettings()
        }
    }

    private fun updateLockState() {
        val keyguard = getSystemService(KeyguardManager::class.java)
        locked = if (Build.VERSION.SDK_INT >= 23) keyguard?.isDeviceLocked == true else keyguard?.isKeyguardLocked == true
    }

    private fun configureLockSafeWindow() {
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
            )
        }
    }

    private fun establishRemovalCallback(adapter: NfcAdapter, tag: Tag?, tagId: String): Boolean {
        if (tag == null || Build.VERSION.SDK_INT < 24) return false
        return runCatching {
            adapter.ignore(
                tag,
                1_500,
                {
                    presentationGate.removed(tagId)
                    if (activeTagId == tagId) activeTagId = null
                    presentationFallbackExpiry?.cancel()
                    presentationFallbackExpiry = null
                },
                Handler(Looper.getMainLooper()),
            )
            true
        }.getOrDefault(false)
    }

    @Suppress("DEPRECATION")
    private fun extractTag(incoming: Intent): Tag? =
        runCatching {
            if (Build.VERSION.SDK_INT >= 33) {
                incoming.getParcelableExtra(NfcAdapter.EXTRA_TAG, Tag::class.java)
            } else {
                incoming.getParcelableExtra(NfcAdapter.EXTRA_TAG) as? Tag
            }
        }.getOrNull()

    private fun clearNfcIntent(incoming: Intent) {
        incoming.action = null
        incoming.data = null
        incoming.removeExtra(NfcAdapter.EXTRA_NDEF_MESSAGES)
        incoming.removeExtra(NfcAdapter.EXTRA_TAG)
        // No later path forwards this intent. Clearing any remaining caller-controlled extras
        // makes that invariant explicit if the Activity is recreated or inspected in a debugger.
        incoming.replaceExtras(null)
    }

    companion object {
        private const val KEY_COMMIT_ID = "nfc_commit_id"
        private const val KEY_USES = "nfc_uses"
        private const val KEY_CART_NAME = "nfc_cart_name"
        private const val PRESENTATION_FALLBACK_EXPIRY_MILLIS = 8_000L
    }
}

@androidx.compose.runtime.Composable
private fun NfcQuickLogResultContent(
    state: NfcQuickLogResultState,
    locked: Boolean,
    onUndo: () -> Unit,
    onSubmitNow: () -> Unit,
    onRetry: () -> Unit,
    onChooseCart: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenNfcSettings: () -> Unit,
    onOpenLaunchPreference: () -> Unit,
    onClose: () -> Unit,
) {
    val terminal = state is NfcQuickLogResultState.Saved || state is NfcQuickLogResultState.Undone
    LaunchedEffect(state) {
        if (terminal) {
            delay(1_200L)
            onClose()
        }
    }
    Column(
        modifier = Modifier
            .padding(24.dp)
            .fillMaxWidth()
            .testTag("nfc-quick-log-result"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val message = when (state) {
            NfcQuickLogResultState.Parsing -> "Reading NFC tag…"
            NfcQuickLogResultState.DuplicatePresentation -> "Tag is still being held near the phone."
            NfcQuickLogResultState.Staging -> "Preparing quick log…"
            is NfcQuickLogResultState.AwaitingUndo -> if (locked) {
                "${state.uses} uses queued"
            } else {
                "${state.uses} uses queued for ${state.cartName ?: "the current Pen cart"}"
            }
            is NfcQuickLogResultState.Saving -> "Saving ${state.uses} uses…"
            is NfcQuickLogResultState.Saved -> {
                val savedCopy = if (state.offline) " saved offline" else " saved"
                if (locked) {
                    "${state.uses} uses$savedCopy"
                } else {
                    "${state.uses} uses$savedCopy for ${state.cartName ?: "the current Pen cart"}"
                }
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
        Text(message, style = MaterialTheme.typography.titleLarge)
        when (state) {
            is NfcQuickLogResultState.AwaitingUndo -> {
                Button(onClick = onUndo, modifier = Modifier.fillMaxWidth()) { Text("Undo") }
                OutlinedButton(onClick = onSubmitNow, modifier = Modifier.fillMaxWidth()) {
                    Text("Submit now")
                }
            }
            NfcQuickLogResultState.NoCart -> if (!locked) {
                Button(onClick = onChooseCart, modifier = Modifier.fillMaxWidth()) { Text("Choose cart") }
            }
            NfcQuickLogResultState.UnregisteredTag,
            NfcQuickLogResultState.RegistryMismatch,
            NfcQuickLogResultState.RegistryCorrupt,
            -> if (!locked) {
                Button(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth()) {
                    Text("Open NFC tag settings")
                }
            }
            NfcQuickLogResultState.NfcUnavailable -> if (!locked) {
                Button(onClick = onOpenNfcSettings, modifier = Modifier.fillMaxWidth()) {
                    Text("Open NFC settings")
                }
            }
            NfcQuickLogResultState.NfcLaunchDisabled -> if (!locked) {
                Button(onClick = onOpenLaunchPreference, modifier = Modifier.fillMaxWidth()) {
                    Text("Open Launch via NFC")
                }
            }
            NfcQuickLogResultState.StorageFailure,
            NfcQuickLogResultState.PreviousTapBusy,
            -> Button(onClick = onRetry, modifier = Modifier.fillMaxWidth()) { Text("Retry") }
            else -> Unit
        }
        if (!terminal) {
            OutlinedButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) { Text("Close") }
        }
    }
}
