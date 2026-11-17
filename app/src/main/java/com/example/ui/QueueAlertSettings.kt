package com.example.ui

import android.Manifest
import android.annotation.SuppressLint
import androidx.activity.result.ActivityResultLauncher
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.R
import kotlinx.coroutines.launch

internal object BackgroundSyncSettingsTestTags {
    const val SWITCH = "settings-background-sync-switch"
    const val LAST_RUN = "settings-background-sync-last-run"
}

internal object QueueAlertSettingsTestTags {
    const val SWITCH = "settings-queue-alerts-switch"
    const val STATUS = "settings-queue-alerts-status"
    const val PERMISSION = "settings-queue-alerts-permission"
}

internal enum class QueueAlertEnableDecision {
    ENABLE,
    REQUEST_PERMISSION,
    BLOCKED,
}

internal fun queueAlertEnableDecision(
    runtimePermissionRequired: Boolean,
    runtimePermissionGranted: Boolean,
    notificationsAvailable: Boolean,
): QueueAlertEnableDecision = when {
    runtimePermissionRequired && !runtimePermissionGranted ->
        QueueAlertEnableDecision.REQUEST_PERMISSION
    notificationsAvailable -> QueueAlertEnableDecision.ENABLE
    else -> QueueAlertEnableDecision.BLOCKED
}

internal fun queueAlertsEffectivelyEnabled(
    preferenceEnabled: Boolean,
    notificationsAvailable: Boolean,
): Boolean = preferenceEnabled && notificationsAvailable

/**
 * Stateful permission and lifecycle boundary extracted from the full Settings screen so the
 * API-24 direct path and API-33 denial path can be exercised without a real application graph.
 */
@Composable
internal fun QueueAlertSettingsCoordinator(
    preferenceEnabled: Boolean,
    pendingActionCount: Int,
    queueNonEmptySinceEpochMillis: Long?,
    nowEpochMillis: Long,
    notificationsAvailable: () -> Boolean,
    runtimePermissionRequired: Boolean,
    runtimePermissionGranted: () -> Boolean,
    runtimePermissionResult: Boolean?,
    onRuntimePermissionResultConsumed: () -> Unit,
    requestRuntimePermission: () -> Unit,
    onPreferenceChanged: (Boolean) -> Unit,
    lifecycleOwner: LifecycleOwner = LocalLifecycleOwner.current,
) {
    var available by remember { mutableStateOf(notificationsAvailable()) }
    var notificationsBlocked by rememberSaveable { mutableStateOf(false) }
    var permissionRequestPending by rememberSaveable { mutableStateOf(false) }
    var disableRequested by remember { mutableStateOf(false) }
    val currentPreference by rememberUpdatedState(preferenceEnabled)
    val currentAvailabilityReader by rememberUpdatedState(notificationsAvailable)
    val currentPreferenceWriter by rememberUpdatedState(onPreferenceChanged)
    val currentRuntimePermissionGranted by rememberUpdatedState(runtimePermissionGranted)
    val currentPermissionResultConsumed by rememberUpdatedState(
        onRuntimePermissionResultConsumed,
    )

    fun finishPermissionRequest(granted: Boolean) {
        if (!permissionRequestPending) return
        permissionRequestPending = false
        val availableAfterResult = granted && currentAvailabilityReader()
        available = availableAfterResult
        notificationsBlocked = !availableAfterResult
        currentPreferenceWriter(availableAfterResult)
    }

    val reconcileAvailability = {
        val currentAvailable = currentAvailabilityReader()
        available = currentAvailable
        when {
            currentPreference && !currentAvailable -> {
                notificationsBlocked = true
                if (!disableRequested) {
                    disableRequested = true
                    currentPreferenceWriter(false)
                }
            }
            currentAvailable -> {
                notificationsBlocked = false
                disableRequested = false
            }
            !currentPreference -> disableRequested = false
        }
    }
    val currentReconcileAvailability by rememberUpdatedState(reconcileAvailability)

    // The StateFlow starts from a safe default; reconcile again when the persisted opt-in arrives.
    LaunchedEffect(preferenceEnabled) {
        currentReconcileAvailability()
    }
    LaunchedEffect(runtimePermissionResult) {
        runtimePermissionResult?.let { granted ->
            finishPermissionRequest(granted)
            currentPermissionResultConsumed()
        }
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                if (permissionRequestPending) {
                    // A recreated activity can reach RESUMED while the permission dialog is
                    // still open. A grant is safe to recover from platform state; a denial must
                    // wait for the Activity Result callback so it cannot race a later grant.
                    if (currentRuntimePermissionGranted()) {
                        finishPermissionRequest(true)
                    }
                } else {
                    currentReconcileAvailability()
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    QueueAlertSettingsSection(
        checked = queueAlertsEffectivelyEnabled(
            preferenceEnabled = preferenceEnabled,
            notificationsAvailable = available,
        ),
        switchEnabled = !permissionRequestPending,
        pendingActionCount = pendingActionCount,
        queueNonEmptySinceEpochMillis = queueNonEmptySinceEpochMillis,
        nowEpochMillis = nowEpochMillis,
        notificationsBlocked = notificationsBlocked,
        onCheckedChange = { requested ->
            if (!requested) {
                permissionRequestPending = false
                notificationsBlocked = false
                disableRequested = false
                onPreferenceChanged(false)
            } else {
                val currentAvailable = notificationsAvailable()
                available = currentAvailable
                when (
                    queueAlertEnableDecision(
                        runtimePermissionRequired = runtimePermissionRequired,
                        runtimePermissionGranted = runtimePermissionGranted(),
                        notificationsAvailable = currentAvailable,
                    )
                ) {
                    QueueAlertEnableDecision.ENABLE -> {
                        notificationsBlocked = false
                        onPreferenceChanged(true)
                    }

                    QueueAlertEnableDecision.REQUEST_PERMISSION -> {
                        notificationsBlocked = false
                        permissionRequestPending = true
                        requestRuntimePermission()
                    }

                    QueueAlertEnableDecision.BLOCKED -> {
                        notificationsBlocked = true
                        onPreferenceChanged(false)
                    }
                }
            }
        },
    )
}

@Composable
internal fun QueueAlertSettingsSection(
    checked: Boolean,
    switchEnabled: Boolean,
    pendingActionCount: Int,
    queueNonEmptySinceEpochMillis: Long?,
    nowEpochMillis: Long,
    notificationsBlocked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Text(
        text = stringResource(R.string.queue_alert_settings_heading),
        style = MaterialTheme.typography.titleLarge,
    )
    Spacer(modifier = Modifier.height(8.dp))
    val switchLabel = stringResource(R.string.queue_alert_settings_switch)
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(switchLabel)
        Switch(
            checked = checked,
            enabled = switchEnabled,
            onCheckedChange = onCheckedChange,
            modifier = Modifier
                .testTag(QueueAlertSettingsTestTags.SWITCH)
                .semantics { contentDescription = switchLabel },
        )
    }
    val status = queueAlertStatusText(
        pendingActionCount = pendingActionCount,
        queueNonEmptySinceEpochMillis = queueNonEmptySinceEpochMillis,
        nowEpochMillis = nowEpochMillis,
    )
    Text(
        text = status,
        modifier = Modifier
            .testTag(QueueAlertSettingsTestTags.STATUS)
            .semantics { contentDescription = status },
        style = MaterialTheme.typography.bodyMedium,
    )
    if (notificationsBlocked) {
        Text(
            text = stringResource(R.string.queue_alert_settings_blocked),
            modifier = Modifier.testTag(QueueAlertSettingsTestTags.PERMISSION),
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
    Spacer(modifier = Modifier.height(8.dp))
    Text(
        text = stringResource(R.string.queue_alert_settings_explanation),
        style = MaterialTheme.typography.bodyMedium,
    )
}

internal fun queueAlertStatusText(
    pendingActionCount: Int,
    queueNonEmptySinceEpochMillis: Long?,
    nowEpochMillis: Long,
): String {
    if (pendingActionCount <= 0) return "Everything is synced."
    val since = queueNonEmptySinceEpochMillis
    if (since == null || since < 0L || nowEpochMillis < since) {
        return "$pendingActionCount waiting to sync."
    }
    val elapsedMillis = nowEpochMillis - since
    val duration = when {
        elapsedMillis < MILLIS_PER_MINUTE -> "less than a minute"
        elapsedMillis < MILLIS_PER_HOUR -> durationText(
            elapsedMillis / MILLIS_PER_MINUTE,
            "minute",
        )
        elapsedMillis < MILLIS_PER_DAY -> durationText(
            elapsedMillis / MILLIS_PER_HOUR,
            "hour",
        )
        else -> durationText(elapsedMillis / MILLIS_PER_DAY, "day")
    }
    return "$pendingActionCount waiting to sync for $duration."
}

@SuppressLint("InlinedApi")
internal fun ActivityResultLauncher<String>.launchPostNotificationsPermission() {
    launch(Manifest.permission.POST_NOTIFICATIONS)
}

internal fun backgroundSyncLastRunText(
    lastRunEpochMillis: Long?,
    lastResult: Enum<*>?,
    nowEpochMillis: Long,
): String {
    if (lastRunEpochMillis == null) {
        return "Not run yet"
    }

    val elapsedMillis = (nowEpochMillis - lastRunEpochMillis).coerceAtLeast(0L)
    val relativeTime = when {
        elapsedMillis < MILLIS_PER_MINUTE -> "just now"
        elapsedMillis < MILLIS_PER_HOUR -> elapsedText(
            elapsedMillis / MILLIS_PER_MINUTE,
            "minute",
        )
        elapsedMillis < MILLIS_PER_DAY -> elapsedText(
            elapsedMillis / MILLIS_PER_HOUR,
            "hour",
        )
        else -> elapsedText(elapsedMillis / MILLIS_PER_DAY, "day")
    }

    return "Last run: $relativeTime — ${backgroundSyncResultText(lastResult)}"
}

private fun elapsedText(amount: Long, unit: String): String = "${
    durationText(amount, unit)
} ago"

private fun durationText(amount: Long, unit: String): String =
    "$amount $unit${if (amount == 1L) "" else "s"}"

private fun backgroundSyncResultText(result: Enum<*>?): String = when (result?.name) {
    "APPLIED", "SUCCESS", "SUCCEEDED", "COMPLETED" -> "Sync successful"
    "FAILURE", "FAILED", "ERROR" -> "Failed"
    "PARTIAL_REJECTIONS" -> "Some actions need attention"
    "BACKEND_CAPABILITY_PENDING" -> "Server update needed; actions still pending"
    "COMPLETED_WITHOUT_ACK" -> "Completed without confirmation"
    "RETRY_EXHAUSTED" -> "Could not sync; actions still pending"
    "ENVIRONMENT_MISMATCH" -> "Sync setup needs attention"
    "CANCELLED", "CANCELED" -> "Cancelled"
    "SKIPPED" -> "Skipped"
    null -> "Unknown result"
    else -> requireNotNull(result).name
        .lowercase()
        .replace('_', ' ')
        .replaceFirstChar(Char::uppercase)
}

private const val MILLIS_PER_MINUTE = 60_000L
private const val MILLIS_PER_HOUR = 60 * MILLIS_PER_MINUTE
private const val MILLIS_PER_DAY = 24 * MILLIS_PER_HOUR
