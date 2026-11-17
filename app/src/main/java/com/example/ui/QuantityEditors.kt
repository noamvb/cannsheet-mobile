package com.example.ui

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.font.FontWeight
import com.example.data.ConsumptionPreferencesRepository
import com.example.data.ProductTypeKey
import com.example.domain.formatQuantityInInputUnit
import java.math.BigDecimal
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

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
        FilledTonalButton(
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

private fun formatQuantityPreset(quantity: Double): String =
    BigDecimal.valueOf(quantity).stripTrailingZeros().toPlainString()

private fun List<Double>.toPresetInputStrings(): List<String> {
    val validPresets = takeIf(ConsumptionPreferencesRepository::isValidQuantityPresets)
        ?: ConsumptionPreferencesRepository.DEFAULT_QUANTITY_PRESETS

    return validPresets.map { it.toString().removeSuffix(".0") }
}
