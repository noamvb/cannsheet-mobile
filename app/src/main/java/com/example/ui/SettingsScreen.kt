package com.example.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import com.example.BuildConfig
import com.example.data.ProductTypeKey
import com.example.domain.PenQuickLogState
import com.example.nfc.NfcQuickLogSettingsCoordinator
import com.example.widget.WidgetSettingsCoordinator
import java.net.URI
import com.example.data.SyncPreferences
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.ui.theme.tabular

@Composable
fun SettingsScreen(viewModel: CannsheetViewModel, nowMillisProvider: () -> Long = System::currentTimeMillis) {
    val gasUrl by viewModel.gasUrl.collectAsState()
    val syncStatus by viewModel.syncStatus.collectAsState()
    val pendingCount by viewModel.pendingActionCount.collectAsState()
    val quantityPresets by viewModel.quantityPresets.collectAsState()
    val quantityPresetOverrides by viewModel.quantityPresetOverrides.collectAsState()
    val secondsPerUseOverrides by viewModel.secondsPerUseOverrides.collectAsState()
    val productTypeOptions by viewModel.productTypeOptions.collectAsState()
    val timerValue by viewModel.submissionTimer.collectAsState()
    val backgroundSyncPreferences by viewModel.backgroundSyncPreferences.collectAsState()
    val penQuickLogState by viewModel.penQuickLogState.collectAsState()
    val loadedPenProductId by viewModel.loadedPenProductId.collectAsState()
    val context = LocalContext.current
    var runtimePermissionResult by rememberSaveable { mutableStateOf<Boolean?>(null) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        runtimePermissionResult = granted
    }
    val backgroundSyncLastRunLabel = backgroundSyncLastRunText(
        lastRunEpochMillis = backgroundSyncPreferences.lastMeaningfulSyncAtEpochMillis,
        lastResult = backgroundSyncPreferences.lastResult,
        nowEpochMillis = nowMillisProvider(),
    )

    SettingsContent(
        gasUrl = gasUrl,
        syncStatus = syncStatus,
        pendingCount = pendingCount,
        quantityPresets = quantityPresets,
        quantityPresetOverrides = quantityPresetOverrides,
        secondsPerUseOverrides = secondsPerUseOverrides,
        productTypeOptions = productTypeOptions,
        timerValue = timerValue,
        backgroundSyncPreferences = backgroundSyncPreferences,
        penQuickLogState = penQuickLogState,
        loadedPenProductId = loadedPenProductId,
        runtimePermissionResult = runtimePermissionResult,
        backgroundSyncLastRunLabel = backgroundSyncLastRunLabel,
        nowMillisProvider = nowMillisProvider,
        onSetSubmissionTimer = viewModel::setSubmissionTimer,
        onSaveQuantityPresets = viewModel::updateQuantityPresets,
        onSaveQuantityPresetsForType = viewModel::updateQuantityPresetsForType,
        onClearQuantityPresetsForType = viewModel::clearQuantityPresetsForType,
        onSaveSecondsPerUseForType = viewModel::updateSecondsPerUseForType,
        onClearSecondsPerUseForType = viewModel::clearSecondsPerUseForType,
        onSetBackgroundSyncEnabled = viewModel::setBackgroundSyncEnabled,
        notificationsAvailable = viewModel::canPresentQueueAlerts,
        runtimePermissionGranted = {
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS,
                ) == PackageManager.PERMISSION_GRANTED
        },
        onRuntimePermissionResultConsumed = { runtimePermissionResult = null },
        requestRuntimePermission = { permissionLauncher.launchPostNotificationsPermission() },
        onQueueAlertsChanged = viewModel::setQueueAlertsEnabled,
        onSyncNow = viewModel::syncQueue,
        onFetchProducts = viewModel::fetchProducts,
    )
}


@Composable
internal fun SettingsContent(
    gasUrl: String,
    syncStatus: String?,
    pendingCount: Int,
    quantityPresets: List<Double>,
    quantityPresetOverrides: Map<ProductTypeKey, List<Double>>,
    secondsPerUseOverrides: Map<ProductTypeKey, Double>,
    productTypeOptions: List<String>,
    timerValue: Int,
    backgroundSyncPreferences: SyncPreferences,
    penQuickLogState: PenQuickLogState,
    loadedPenProductId: String?,
    runtimePermissionResult: Boolean?,
    backgroundSyncLastRunLabel: String,
    nowMillisProvider: () -> Long,
    onSetSubmissionTimer: (Int) -> Unit,
    onSaveQuantityPresets: suspend (List<Double>) -> Result<Unit>,
    onSaveQuantityPresetsForType: suspend (String, List<Double>) -> Result<Unit>,
    onClearQuantityPresetsForType: (String) -> Unit,
    onSaveSecondsPerUseForType: suspend (String, Double) -> Result<Unit>,
    onClearSecondsPerUseForType: (String) -> Unit,
    onSetBackgroundSyncEnabled: (Boolean) -> Unit,
    notificationsAvailable: () -> Boolean,
    runtimePermissionGranted: () -> Boolean,
    onRuntimePermissionResultConsumed: () -> Unit,
    requestRuntimePermission: () -> Unit,
    onQueueAlertsChanged: (Boolean) -> Unit,
    onSyncNow: () -> Unit,
    onFetchProducts: () -> Unit,
    scrollState: ScrollState = rememberScrollState(),
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
            .navigationBarsPadding()
            .verticalScroll(scrollState)
            .padding(16.dp)
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.height(8.dp))
        SettingsGroupHeading("SYNC & QUEUE")
        SettingsValueRow("Connection", syncStatus ?: "Ready")
        SettingsValueRow(
            label = "Pending actions",
            value = pendingCount.toString(),
            valueTag = "settings-pending-count",
        )
        SettingsSwitchRow(
            label = "Background sync",
            checked = backgroundSyncPreferences.enabled,
            onCheckedChange = onSetBackgroundSyncEnabled,
            testTag = BackgroundSyncSettingsTestTags.SWITCH,
            contentDescription = "Background sync",
        )
        Text(
            text = backgroundSyncLastRunLabel,
            modifier = Modifier
                .fillMaxWidth()
                .testTag(BackgroundSyncSettingsTestTags.LAST_RUN)
                .semantics { contentDescription = backgroundSyncLastRunLabel }
                .padding(vertical = 10.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(
            onClick = onSyncNow,
            modifier = Modifier.fillMaxWidth(),
            enabled = pendingCount > 0,
        ) {
            Text("Sync Now")
        }

        SettingsGroupHeading("LOGGING DEFAULTS")
        SettingsValueRow("Cancel window", "$timerValue seconds")
        Slider(
            value = timerValue.toFloat(),
            onValueChange = { onSetSubmissionTimer(it.toInt()) },
            valueRange = 0f..5f,
            steps = 4,
        )
        QuickLogQuantityEditor(
            quantityPresets = quantityPresets,
            onSave = onSaveQuantityPresets,
        )

        SettingsGroupHeading("PRODUCT TYPES")
        ProductTypeQuantityEditor(
            productTypes = productTypeOptions,
            globalPresets = quantityPresets,
            overrides = quantityPresetOverrides,
            onSave = onSaveQuantityPresetsForType,
            onReset = onClearQuantityPresetsForType,
            secondsPerUseOverrides = secondsPerUseOverrides,
            onSaveSecondsPerUse = onSaveSecondsPerUseForType,
            onClearSecondsPerUse = onClearSecondsPerUseForType,
        )

        SettingsGroupHeading("QUICK LOG")
        NfcQuickLogSettingsCoordinator(
            resolverDescription = nfcQuickLogResolverDescription(
                state = penQuickLogState,
                explicitLoadedPenProductId = loadedPenProductId,
            ),
        )
        WidgetSettingsCoordinator()

        SettingsGroupHeading("NOTIFICATIONS")
        QueueAlertSettingsCoordinator(
            preferenceEnabled = backgroundSyncPreferences.queueAlertsEnabled,
            pendingActionCount = pendingCount,
            queueNonEmptySinceEpochMillis =
                backgroundSyncPreferences.queueNonEmptySinceEpochMillis,
            nowEpochMillis = nowMillisProvider(),
            notificationsAvailable = notificationsAvailable,
            runtimePermissionRequired =
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU,
            runtimePermissionGranted = runtimePermissionGranted,
            runtimePermissionResult = runtimePermissionResult,
            onRuntimePermissionResultConsumed = onRuntimePermissionResultConsumed,
            requestRuntimePermission = {
                requestRuntimePermission()
            },
            onPreferenceChanged = onQueueAlertsChanged,
        )

        SettingsGroupHeading("DATA")
        SettingsValueRow("Endpoint", endpointDiagnostic(gasUrl))
        OutlinedButton(
            onClick = { onFetchProducts() },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Force Fetch Products")
        }

        SettingsGroupHeading("ABOUT")
        SettingsValueRow(
            "Environment",
            BuildConfig.APP_ENVIRONMENT.lowercase().replaceFirstChar(Char::uppercase),
        )
        SettingsValueRow("Package", BuildConfig.APPLICATION_ID)
        SettingsValueRow("Version", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
    }
}

@Composable
private fun SettingsGroupHeading(title: String) {
    Spacer(modifier = Modifier.height(22.dp))
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    Text(
        text = title,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp, bottom = 4.dp),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        letterSpacing = 1.sp,
    )
}

@Composable
private fun SettingsValueRow(
    label: String,
    value: String,
    valueTag: String? = null,
) {
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 48.dp)
            .padding(horizontal = 2.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(
            text = value,
            modifier = Modifier
                .widthIn(max = 220.dp)
                .then(valueTag?.let(Modifier::testTag) ?: Modifier),
            style = MaterialTheme.typography.bodyMedium.tabular(),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.End,
        )
    }
}

@Composable
private fun SettingsSwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    testTag: String,
    contentDescription: String,
) {
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 56.dp)
            .padding(horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.testTag(testTag).semantics {
                this.contentDescription = contentDescription
            },
        )
    }
}
internal fun nfcQuickLogResolverDescription(
    state: PenQuickLogState,
    explicitLoadedPenProductId: String?,
): String = when (state) {
    PenQuickLogState.Unavailable ->
        "Current tap target: unavailable because there is no selectable Pen cart."
    PenQuickLogState.NoCartLoaded ->
        "Current tap target: none. Choose or use a Pen cart before tapping a tag."
    is PenQuickLogState.Loaded -> if (state.product.id == explicitLoadedPenProductId) {
        "Current tap target: ${state.product.name} (loaded Pen cart)."
    } else {
        "Current tap target: ${state.product.name} (most recently logged Pen fallback)."
    }
}

internal fun endpointDiagnostic(url: String): String = runCatching {
    val uri = URI(url)
    val deployment = uri.path.substringAfter("/macros/s/").substringBefore('/').takeLast(10)
    "${uri.host}/…$deployment"
}.getOrDefault("invalid endpoint")
