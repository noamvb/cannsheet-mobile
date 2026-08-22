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
import androidx.compose.material3.Surface
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
        // Every state must survive recreation, not just AwaitingUndo: rotating while showing a
        // failure or a completed save would otherwise leave the recreated Activity on its default
        // Parsing state with no pending payload to recover and no way forward.
        outState.putString(KEY_STATE_ID, stateId(resultState))
        val state = resultState
        val uses = when (state) {
            is NfcQuickLogResultState.AwaitingUndo -> state.uses
            is NfcQuickLogResultState.Saving -> state.uses
            is NfcQuickLogResultState.Saved -> state.uses
            is NfcQuickLogResultState.Undone -> state.uses
            else -> null
        }
        uses?.let { outState.putInt(KEY_USES, it) }
        val cartName = when (state) {
            is NfcQuickLogResultState.AwaitingUndo -> state.cartName
            is NfcQuickLogResultState.Saving -> state.cartName
            is NfcQuickLogResultState.Saved -> state.cartName
            else -> null
        }
        // Do not copy private product/cart data into recreation state while the keyguard is
        // active. The locked result surface intentionally carries quantity only.
        if (!locked) cartName?.let { outState.putString(KEY_CART_NAME, it) }
        (state as? NfcQuickLogResultState.AwaitingUndo)?.let {
            outState.putString(KEY_COMMIT_ID, it.commitId)
        }
    }

    private fun consumeIntent(incoming: Intent) {
        if (NfcWriterSession.isActiveWithin()) {
            // The tag writer owns this tag. It normally suppresses dispatch by holding reader
            // mode, but a tag it just wrote carries our own Application Record, so a dispatch
            // slipping through while the writer pauses or resumes would launch this Activity over
            // it, report the not-yet-registered tag as unregistered, and pause the writer before
            // it could finish registering. Withdraw silently instead of stealing the tap.
            clearNfcIntent(incoming)
            finish()
            return
        }
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
            // authoritative UI for this continuous presentation. But this Activity is
            // `noHistory`, so a fresh instance can hit this branch while the process-wide gate
            // still has the tag active and this instance's own resultState never advanced past
            // Parsing; only leave the state untouched when it already holds a meaningful result.
            if (resultState is NfcQuickLogResultState.Parsing) {
                resultState = NfcQuickLogResultState.DuplicatePresentation
            }
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
                ?.let { wholeUsesInRangeOrNull(it.uses) }
            if (pending != null && pendingUses != null) {
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
                // A payload that was awaiting Undo before recreation is gone from the outbox, so
                // it committed while the Activity was being rebuilt.
                resultState = NfcQuickLogResultState.Saved(savedUses, savedCartName)
            } else {
                // No pending payload and nothing awaiting: restore whatever the Activity was
                // actually showing, rather than stranding it on Parsing.
                resultState = restoredState(
                    stateId = saved.getString(KEY_STATE_ID),
                    uses = savedUses.takeIf {
                        it in NfcQuickLogContract.MIN_USES..NfcQuickLogContract.MAX_USES
                    },
                    cartName = savedCartName,
                )
            }
        }
    }

    /**
     * Stable recreation key for [state]. Only the identity is persisted; any quantity or cart name
     * travels in its own key so the keyguard privacy rule in [onSaveInstanceState] still applies.
     */
    private fun stateId(state: NfcQuickLogResultState): String = when (state) {
        NfcQuickLogResultState.Parsing -> "parsing"
        NfcQuickLogResultState.DuplicatePresentation -> "duplicate"
        NfcQuickLogResultState.Staging -> "staging"
        is NfcQuickLogResultState.AwaitingUndo -> "awaiting"
        is NfcQuickLogResultState.Saving -> "saving"
        is NfcQuickLogResultState.Saved -> "saved"
        is NfcQuickLogResultState.Undone -> "undone"
        NfcQuickLogResultState.UnregisteredTag -> "unregistered"
        NfcQuickLogResultState.RegistryMismatch -> "mismatch"
        NfcQuickLogResultState.RegistryCorrupt -> "corrupt"
        NfcQuickLogResultState.NoCart -> "no-cart"
        NfcQuickLogResultState.PreviousTapBusy -> "busy"
        NfcQuickLogResultState.StorageFailure -> "storage-failure"
        NfcQuickLogResultState.InvalidTag -> "invalid-tag"
        NfcQuickLogResultState.NfcLaunchDisabled -> "launch-disabled"
        NfcQuickLogResultState.NfcUnavailable -> "nfc-unavailable"
        is NfcQuickLogResultState.UnexpectedFailure -> "unexpected"
    }

    /**
     * Inverse of [stateId] for the states that can outlive recreation. `Parsing`, `Staging`, and
     * `AwaitingUndo` are deliberately absent: the first two describe work this instance is no
     * longer doing, and a genuine `AwaitingUndo` is recovered from the durable payload instead.
     */
    private fun restoredState(
        stateId: String?,
        uses: Int?,
        cartName: String?,
    ): NfcQuickLogResultState = when (stateId) {
        "duplicate" -> NfcQuickLogResultState.DuplicatePresentation
        "saving", "saved" -> uses
            ?.let { NfcQuickLogResultState.Saved(it, cartName) }
            ?: NfcQuickLogResultState.UnexpectedFailure()
        "undone" -> uses
            ?.let { NfcQuickLogResultState.Undone(it) }
            ?: NfcQuickLogResultState.UnexpectedFailure()
        "unregistered" -> NfcQuickLogResultState.UnregisteredTag
        "mismatch" -> NfcQuickLogResultState.RegistryMismatch
        "corrupt" -> NfcQuickLogResultState.RegistryCorrupt
        "no-cart" -> NfcQuickLogResultState.NoCart
        "busy" -> NfcQuickLogResultState.PreviousTapBusy
        "storage-failure" -> NfcQuickLogResultState.StorageFailure
        "invalid-tag" -> NfcQuickLogResultState.InvalidTag
        "launch-disabled" -> NfcQuickLogResultState.NfcLaunchDisabled
        "nfc-unavailable" -> NfcQuickLogResultState.NfcUnavailable
        else -> NfcQuickLogResultState.UnexpectedFailure()
    }

    /**
     * [uses] as a whole number within [NfcQuickLogContract.MIN_USES]..[NfcQuickLogContract.MAX_USES],
     * or null if it is fractional, non-finite, or out of range. Shared by [restorePendingOperation]
     * and the [retrySave] recovery path so both apply the exact same acceptance rule to a durable
     * direct-uses payload.
     */
    private fun wholeUsesInRangeOrNull(uses: Double): Int? =
        uses
            .takeIf { it.isFinite() && it.toInt().toDouble() == it }
            ?.toInt()
            ?.takeIf { it in NfcQuickLogContract.MIN_USES..NfcQuickLogContract.MAX_USES }

    private suspend fun watchCommit(awaiting: NfcQuickLogResultState.AwaitingUndo) {
        val deadline = awaiting.submittedAtEpochMillis + COMMIT_DELAY_MILLIS + 2_000L
        while (currentCoroutineContext().isActive) {
            val pending = coordinator.pending()
            if (pending?.commitId != awaiting.commitId) {
                retryAwaiting = null
                resultState = NfcQuickLogResultState.Saved(
                    uses = awaiting.uses,
                    cartName = awaiting.cartName,
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
                NfcQuickLogResultState.Saved(awaiting.uses, awaiting.cartName)
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
        if (retryAwaiting != null) {
            submitNow()
            return
        }
        // A fresh instance (this Activity is `noHistory`) has no in-memory AwaitingUndo to
        // retry from. Recover the durable pending payload before giving up on Retry.
        lifecycleScope.launch {
            val pending = coordinator.pending()
            val recoveredUses = pending
                ?.takeIf { it.inputKind == com.example.widget.DeferredPenInputKind.DIRECT_USES }
                ?.let { wholeUsesInRangeOrNull(it.uses) }
            if (pending != null && recoveredUses != null) {
                retryAwaiting = NfcQuickLogResultState.AwaitingUndo(
                    commitId = pending.commitId,
                    uses = recoveredUses,
                    cartName = null,
                    submittedAtEpochMillis = pending.submittedAtEpochMillis,
                )
                submitNow()
            } else {
                resultState = NfcQuickLogResultState.UnexpectedFailure()
            }
        }
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
        // ignore() returns false when the controller could not begin ignoring the tag, in which
        // case no removal callback is ever delivered. Returning the platform result keeps the
        // caller's fallback expiry scheduled, so an intentional remove-and-retap is not discarded
        // as a duplicate for the full eight-second timestamp window.
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
        private const val KEY_STATE_ID = "nfc_state_id"
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
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = MaterialTheme.shapes.large,
        tonalElevation = 3.dp,
    ) {
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
}
