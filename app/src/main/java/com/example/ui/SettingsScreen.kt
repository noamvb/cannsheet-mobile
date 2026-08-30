package com.example.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.font.FontWeight
import com.example.BuildConfig
import com.example.data.ConsumptionPreferencesRepository
import com.example.data.ProductTypeKey
import com.example.domain.formatQuantityInInputUnit
import com.example.domain.PenQuickLogState
import com.example.nfc.NfcQuickLogSettingsCoordinator
import com.example.widget.WidgetSettingsCoordinator
import java.math.BigDecimal
import java.net.URI
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.R
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(viewModel: CannsheetViewModel) {
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
        nowEpochMillis = System.currentTimeMillis(),
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text("Settings & Sync", style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.height(24.dp))

        Text("Environment: ${BuildConfig.APP_ENVIRONMENT.lowercase().replaceFirstChar(Char::uppercase)}")
        Text("Package: ${BuildConfig.APPLICATION_ID}")
        Text("Version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        Text("Endpoint: ${endpointDiagnostic(gasUrl)}")

        Spacer(modifier = Modifier.height(32.dp))
        Text("Submission Timer", style = MaterialTheme.typography.titleLarge)
        Spacer(modifier = Modifier.height(8.dp))

        Text("Cancel window: $timerValue seconds")
        Slider(
            value = timerValue.toFloat(),
            onValueChange = { viewModel.setSubmissionTimer(it.toInt()) },
            valueRange = 0f..5f,
            steps = 4
        )

        Spacer(modifier = Modifier.height(32.dp))
        QuickLogQuantityEditor(
            quantityPresets = quantityPresets,
            onSave = viewModel::updateQuantityPresets,
        )

        Spacer(modifier = Modifier.height(32.dp))
        ProductTypeQuantityEditor(
            productTypes = productTypeOptions,
            globalPresets = quantityPresets,
            overrides = quantityPresetOverrides,
            onSave = viewModel::updateQuantityPresetsForType,
            onReset = viewModel::clearQuantityPresetsForType,
            secondsPerUseOverrides = secondsPerUseOverrides,
            onSaveSecondsPerUse = viewModel::updateSecondsPerUseForType,
            onClearSecondsPerUse = viewModel::clearSecondsPerUseForType,
        )

        Spacer(modifier = Modifier.height(32.dp))
        WidgetSettingsCoordinator()

        Spacer(modifier = Modifier.height(32.dp))
        NfcQuickLogSettingsCoordinator(
            resolverDescription = nfcQuickLogResolverDescription(
                state = penQuickLogState,
                explicitLoadedPenProductId = loadedPenProductId,
            ),
        )

        Spacer(modifier = Modifier.height(32.dp))
        Text("Offline Queue", style = MaterialTheme.typography.titleLarge)
        Spacer(modifier = Modifier.height(8.dp))
        Text("Pending Actions: $pendingCount")

        Spacer(modifier = Modifier.height(16.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("Background sync")
            Switch(
                checked = backgroundSyncPreferences.enabled,
                onCheckedChange = viewModel::setBackgroundSyncEnabled,
                modifier = Modifier
                    .testTag(BackgroundSyncSettingsTestTags.SWITCH)
                    .semantics { contentDescription = "Background sync" },
            )
        }
        Text(
            text = backgroundSyncLastRunLabel,
            modifier = Modifier
                .testTag(BackgroundSyncSettingsTestTags.LAST_RUN)
                .semantics { contentDescription = backgroundSyncLastRunLabel },
            style = MaterialTheme.typography.bodyMedium,
        )

        Spacer(modifier = Modifier.height(24.dp))
        QueueAlertSettingsCoordinator(
            preferenceEnabled = backgroundSyncPreferences.queueAlertsEnabled,
            pendingActionCount = pendingCount,
            queueNonEmptySinceEpochMillis =
                backgroundSyncPreferences.queueNonEmptySinceEpochMillis,
            nowEpochMillis = System.currentTimeMillis(),
            notificationsAvailable = viewModel::canPresentQueueAlerts,
            runtimePermissionRequired =
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU,
            runtimePermissionGranted = {
                Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                    ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.POST_NOTIFICATIONS,
                    ) == PackageManager.PERMISSION_GRANTED
            },
            runtimePermissionResult = runtimePermissionResult,
            onRuntimePermissionResultConsumed = { runtimePermissionResult = null },
            requestRuntimePermission = {
                permissionLauncher.launchPostNotificationsPermission()
            },
            onPreferenceChanged = viewModel::setQueueAlertsEnabled,
        )

        Spacer(modifier = Modifier.height(16.dp))
        Button(
            onClick = { viewModel.syncQueue() },
            modifier = Modifier.fillMaxWidth(),
            enabled = pendingCount > 0
        ) {
            Text("Sync Now")
        }

        Spacer(modifier = Modifier.height(16.dp))
        Button(
            onClick = { viewModel.fetchProducts() },
            modifier = Modifier.fillMaxWidth(),
            enabled = true
        ) {
            Text("Force Fetch Products")
        }

        Spacer(modifier = Modifier.height(32.dp))
        if (syncStatus != null) {
            Text("Status: $syncStatus", color = MaterialTheme.colorScheme.primary)
        }
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

@Composable
internal fun QuickLogQuantityEditor(
    quantityPresets: List<Double>,
    onSave: suspend (List<Double>) -> Result<Unit>,
) {
    Text("Quick-log quantities", style = MaterialTheme.typography.titleLarge)
    Spacer(modifier = Modifier.height(8.dp))
    Text(
        "Choose up to 10 quantities to show as shortcuts when logging consumption.",
        style = MaterialTheme.typography.bodyMedium
    )
    Spacer(modifier = Modifier.height(12.dp))
    QuantityPresetListEditor(
        presets = quantityPresets,
        tags = QuantityPresetEditorTags(
            addPreset = "quick-log-add-preset",
            savePresets = "quick-log-save-presets",
            presetInputPrefix = "quick-log-preset-input-",
            removePresetPrefix = "quick-log-remove-preset-",
        ),
        saveButtonLabel = "Save quantity presets",
        savedMessage = "Quantity presets saved",
        onSave = onSave,
    )
}

internal data class QuantityPresetEditorTags(
    val addPreset: String,
    val savePresets: String,
    val presetInputPrefix: String,
    val removePresetPrefix: String,
) {
    fun presetInput(position: Int) = "$presetInputPrefix$position"
    fun removePreset(position: Int) = "$removePresetPrefix$position"
}

@Composable
internal fun QuantityPresetListEditor(
    presets: List<Double>,
    tags: QuantityPresetEditorTags,
    saveButtonLabel: String,
    savedMessage: String,
    onSave: suspend (List<Double>) -> Result<Unit>,
    seedKey: Any? = Unit,
) {
    var presetInputs by remember {
        mutableStateOf(presets.toPresetInputStrings())
    }
    var presetsSaved by remember { mutableStateOf(false) }
    var saveInProgress by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(seedKey, presets) {
        presetInputs = presets.toPresetInputStrings()
        presetsSaved = false
    }

    val parsedPresets = presetInputs.map(String::toDoubleOrNull)
    val duplicatePresets = parsedPresets
        .filterNotNull()
        .filter { it.isFinite() }
        .groupingBy { it }
        .eachCount()
        .filterValues { it > 1 }
        .keys
    val presetErrors = presetInputs.mapIndexed { index, input ->
        val value = parsedPresets[index]
        when {
            input.isBlank() -> "Required"
            value == null || !value.isFinite() -> "Enter a valid number"
            value <= 0.0 -> "Must be greater than 0"
            value in duplicatePresets -> "Must be unique"
            else -> null
        }
    }
    val presetsAreValid =
        presetInputs.size in
            ConsumptionPreferencesRepository.MIN_QUANTITY_PRESETS..
                ConsumptionPreferencesRepository.MAX_QUANTITY_PRESETS &&
            presetErrors.all { it == null }

    presetInputs.forEachIndexed { index, input ->
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { newValue ->
                    presetInputs = presetInputs.toMutableList().also { it[index] = newValue }
                    presetsSaved = false
                },
                label = { Text("Preset ${index + 1}") },
                modifier = Modifier
                    .weight(1f)
                    .testTag(tags.presetInput(index + 1)),
                enabled = !saveInProgress,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
                isError = presetErrors[index] != null,
                supportingText = presetErrors[index]?.let { error ->
                    { Text(error) }
                }
            )
            IconButton(
                onClick = {
                    presetInputs = presetInputs.toMutableList().also { it.removeAt(index) }
                    presetsSaved = false
                },
                enabled = !saveInProgress && presetInputs.size >
                    ConsumptionPreferencesRepository.MIN_QUANTITY_PRESETS,
                modifier = Modifier
                    .testTag(tags.removePreset(index + 1))
                    .semantics {
                        contentDescription = "Remove preset ${index + 1}"
                        if (
                            presetInputs.size <=
                            ConsumptionPreferencesRepository.MIN_QUANTITY_PRESETS
                        ) {
                            disabled()
                        }
                    },
            ) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = null,
                )
            }
        }
        if (index < presetInputs.lastIndex) {
            Spacer(modifier = Modifier.height(4.dp))
        }
    }

    Spacer(modifier = Modifier.height(8.dp))
    OutlinedButton(
        onClick = {
            presetInputs = presetInputs + ""
            presetsSaved = false
        },
        modifier = Modifier
            .fillMaxWidth()
            .testTag(tags.addPreset),
        enabled = !saveInProgress && presetInputs.size <
            ConsumptionPreferencesRepository.MAX_QUANTITY_PRESETS,
    ) {
        Text("Add preset")
    }
    Text(
        "${presetInputs.size} of ${ConsumptionPreferencesRepository.MAX_QUANTITY_PRESETS} presets",
        style = MaterialTheme.typography.bodySmall,
    )

    Spacer(modifier = Modifier.height(8.dp))
    Button(
        onClick = {
            coroutineScope.launch {
                saveInProgress = true
                presetsSaved = false
                val result = onSave(parsedPresets.filterNotNull())
                if (result.isSuccess) {
                    presetsSaved = true
                    focusManager.clearFocus()
                }
                saveInProgress = false
            }
        },
        modifier = Modifier
            .fillMaxWidth()
            .testTag(tags.savePresets),
        enabled = presetsAreValid && !saveInProgress,
    ) {
        Text(saveButtonLabel)
    }
    if (presetsSaved) {
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            savedMessage,
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

internal object ProductTypeQuantityEditorTestTags {
    const val TYPE_PICKER = "type-quantities-type-picker"
    const val TYPE_MENU = "type-quantities-type-menu"
    const val STATUS = "type-quantities-status"
    const val SUMMARY = "type-quantities-summary"
    const val RESET = "type-quantities-reset"
    const val ADD_PRESET = "type-quantities-add-preset"
    const val SAVE_PRESETS = "type-quantities-save-presets"
    const val DURATION_SWITCH = "type-quantities-duration-switch"
    const val SECONDS_PER_USE = "type-quantities-seconds-per-use"
    const val SAVE_DURATION = "type-quantities-save-duration"
    const val DURATION_PREVIEW = "type-quantities-duration-preview"

    fun typeOption(type: String) = "type-quantities-type-option-$type"
    fun presetInput(position: Int) = "type-quantities-preset-input-$position"
    fun removePreset(position: Int) = "type-quantities-remove-preset-$position"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ProductTypeQuantityEditor(
    productTypes: List<String>,
    globalPresets: List<Double>,
    overrides: Map<ProductTypeKey, List<Double>>,
    onSave: suspend (String, List<Double>) -> Result<Unit>,
    onReset: (String) -> Unit,
    secondsPerUseOverrides: Map<ProductTypeKey, Double> = emptyMap(),
    onSaveSecondsPerUse: suspend (String, Double) -> Result<Unit> = { _, _ -> Result.success(Unit) },
    onClearSecondsPerUse: (String) -> Unit = {},
) {
    var selectedType by rememberSaveable { mutableStateOf(productTypes.firstOrNull().orEmpty()) }
    var typeExpanded by rememberSaveable { mutableStateOf(false) }
    var durationEnabled by remember { mutableStateOf(false) }
    var secondsPerUseInput by remember { mutableStateOf("") }
    var durationSaved by remember { mutableStateOf(false) }
    var durationSaveInProgress by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(productTypes) {
        if (selectedType !in productTypes) {
            selectedType = productTypes.firstOrNull().orEmpty()
        }
    }

    Text("Quantities by product type", style = MaterialTheme.typography.titleLarge)
    Spacer(modifier = Modifier.height(8.dp))
    Text(
        "Override the quantities above for one product type. Types without an override use the quantities above.",
        style = MaterialTheme.typography.bodyMedium,
    )
    Spacer(modifier = Modifier.height(12.dp))

    if (productTypes.isEmpty()) {
        Text("No product types available.", style = MaterialTheme.typography.bodyMedium)
        return
    }

    ExposedDropdownMenuBox(
        expanded = typeExpanded,
        onExpandedChange = { typeExpanded = !typeExpanded },
    ) {
        OutlinedTextField(
            value = ProductTypes.displayName(selectedType),
            onValueChange = {},
            readOnly = true,
            label = { Text("Product type") },
            trailingIcon = {
                ExposedDropdownMenuDefaults.TrailingIcon(expanded = typeExpanded)
            },
            modifier = Modifier
                .menuAnchor()
                .fillMaxWidth()
                .testTag(ProductTypeQuantityEditorTestTags.TYPE_PICKER),
        )
        ExposedDropdownMenu(
            expanded = typeExpanded,
            onDismissRequest = { typeExpanded = false },
            modifier = Modifier.testTag(ProductTypeQuantityEditorTestTags.TYPE_MENU),
        ) {
            productTypes.forEach { type ->
                DropdownMenuItem(
                    text = { Text(ProductTypes.displayName(type)) },
                    modifier = Modifier.testTag(ProductTypeQuantityEditorTestTags.typeOption(type)),
                    onClick = {
                        selectedType = type
                        typeExpanded = false
                    },
                )
            }
        }
    }

    Spacer(modifier = Modifier.height(12.dp))
    val selectedOverride = overrides[ProductTypeKey(selectedType)]
    val selectedSecondsPerUse = secondsPerUseOverrides[ProductTypeKey(selectedType)]
    LaunchedEffect(selectedType, selectedSecondsPerUse) {
        durationEnabled = selectedSecondsPerUse != null
        secondsPerUseInput = selectedSecondsPerUse?.let(::formatQuantityPreset).orEmpty()
        durationSaved = false
    }
    val effectivePresets = ConsumptionPreferencesRepository.effectiveQuantityPresets(
        globalPresets = globalPresets,
        overrides = overrides,
        productType = selectedType,
    )
    Text(
        text = selectedOverride?.let { presets ->
            "Custom: ${presets.joinToString(", ", transform = ::formatQuantityPreset)}"
        } ?: "Using the default quantities",
        modifier = Modifier.testTag(ProductTypeQuantityEditorTestTags.STATUS),
        style = MaterialTheme.typography.bodyMedium,
    )
    Spacer(modifier = Modifier.height(12.dp))

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text("Enter quantity as duration", fontWeight = FontWeight.Medium)
            Text(
                "Show duration chips while storing the same quantity in uses.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Switch(
            checked = durationEnabled,
            onCheckedChange = { enabled ->
                durationEnabled = enabled
                durationSaved = false
                if (!enabled) {
                    onClearSecondsPerUse(selectedType)
                    focusManager.clearFocus()
                }
            },
            modifier = Modifier.testTag(ProductTypeQuantityEditorTestTags.DURATION_SWITCH),
            enabled = !durationSaveInProgress,
        )
    }

    if (durationEnabled) {
        Spacer(modifier = Modifier.height(8.dp))
        val parsedSecondsPerUse = secondsPerUseInput.toDoubleOrNull()
        val secondsPerUseError = when {
            secondsPerUseInput.isBlank() -> "Required"
            parsedSecondsPerUse == null || !parsedSecondsPerUse.isFinite() -> "Enter a valid number"
            !ConsumptionPreferencesRepository.isValidSecondsPerUse(parsedSecondsPerUse) ->
                "Must be greater than 0 and no more than ${ConsumptionPreferencesRepository.MAX_SECONDS_PER_USE}"
            else -> null
        }
        OutlinedTextField(
            value = secondsPerUseInput,
            onValueChange = {
                secondsPerUseInput = it
                durationSaved = false
            },
            label = { Text("Seconds per use") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            singleLine = true,
            enabled = !durationSaveInProgress,
            isError = secondsPerUseError != null,
            supportingText = secondsPerUseError?.let { error -> { Text(error) } },
            modifier = Modifier
                .fillMaxWidth()
                .testTag(ProductTypeQuantityEditorTestTags.SECONDS_PER_USE),
        )
        Spacer(modifier = Modifier.height(8.dp))
        Button(
            onClick = {
                secondsPerUseInput.toDoubleOrNull()?.let { seconds ->
                    coroutineScope.launch {
                        durationSaveInProgress = true
                        durationSaved = false
                        val result = onSaveSecondsPerUse(selectedType, seconds)
                        if (result.isSuccess) {
                            durationSaved = true
                            focusManager.clearFocus()
                        }
                        durationSaveInProgress = false
                    }
                }
            },
            enabled = secondsPerUseError == null && !durationSaveInProgress,
            modifier = Modifier
                .fillMaxWidth()
                .testTag(ProductTypeQuantityEditorTestTags.SAVE_DURATION),
        ) {
            Text("Save duration")
        }
        if (durationSaved) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "Duration saved",
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }

    val previewSecondsPerUse = if (durationEnabled) {
        secondsPerUseInput.toDoubleOrNull()
            ?.takeIf(ConsumptionPreferencesRepository::isValidSecondsPerUse)
    } else {
        null
    }
    Text(
        text = "Presets show as: ${effectivePresets.joinToString(" · ") { preset ->
            formatQuantityInInputUnit(preset, previewSecondsPerUse)
        }}",
        modifier = Modifier.testTag(ProductTypeQuantityEditorTestTags.DURATION_PREVIEW),
        style = MaterialTheme.typography.bodyMedium,
    )
    Spacer(modifier = Modifier.height(12.dp))

    QuantityPresetListEditor(
        presets = effectivePresets,
        tags = QuantityPresetEditorTags(
            addPreset = ProductTypeQuantityEditorTestTags.ADD_PRESET,
            savePresets = ProductTypeQuantityEditorTestTags.SAVE_PRESETS,
            presetInputPrefix = "type-quantities-preset-input-",
            removePresetPrefix = "type-quantities-remove-preset-",
        ),
        saveButtonLabel = "Save quantities for ${ProductTypes.displayName(selectedType)}",
        savedMessage = "Quantities saved",
        onSave = { presets -> onSave(selectedType, presets) },
        seedKey = selectedType,
    )

    Spacer(modifier = Modifier.height(12.dp))
    OutlinedButton(
        onClick = { onReset(selectedType) },
        enabled = overrides.containsKey(ProductTypeKey(selectedType)),
        modifier = Modifier
            .fillMaxWidth()
            .testTag(ProductTypeQuantityEditorTestTags.RESET),
    ) {
        Text("Use default quantities for ${ProductTypes.displayName(selectedType)}")
    }

    Spacer(modifier = Modifier.height(12.dp))
    val customTypes = overrides.keys.map { it.type }.sorted()
    Text(
        text = if (customTypes.isEmpty()) {
            "No product types have custom quantities."
        } else {
            "Custom quantities: ${customTypes.joinToString(", ")}"
        },
        modifier = Modifier.testTag(ProductTypeQuantityEditorTestTags.SUMMARY),
        style = MaterialTheme.typography.bodyMedium,
    )
}

internal object QuickLogQuantityEditorTestTags {
    const val ADD_PRESET = "quick-log-add-preset"
    const val SAVE_PRESETS = "quick-log-save-presets"

    fun presetInput(position: Int) = "quick-log-preset-input-$position"

    fun removePreset(position: Int) = "quick-log-remove-preset-$position"
}

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
private fun ActivityResultLauncher<String>.launchPostNotificationsPermission() {
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

internal fun endpointDiagnostic(url: String): String = runCatching {
    val uri = URI(url)
    val deployment = uri.path.substringAfter("/macros/s/").substringBefore('/').takeLast(10)
    "${uri.host}/…$deployment"
}.getOrDefault("invalid endpoint")

private fun formatQuantityPreset(quantity: Double): String =
    BigDecimal.valueOf(quantity).stripTrailingZeros().toPlainString()

private fun List<Double>.toPresetInputStrings(): List<String> {
    val validPresets = takeIf(ConsumptionPreferencesRepository::isValidQuantityPresets)
        ?: ConsumptionPreferencesRepository.DEFAULT_QUANTITY_PRESETS

    return validPresets.map { it.toString().removeSuffix(".0") }
}
