package com.example.ui

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.KeyboardType
import com.example.BuildConfig
import com.example.data.ConsumptionPreferencesRepository
import java.net.URI
import androidx.compose.ui.unit.dp

@Composable
fun SettingsScreen(viewModel: CannsheetViewModel) {
    val gasUrl by viewModel.gasUrl.collectAsState()
    val syncStatus by viewModel.syncStatus.collectAsState()
    val pendingCount by viewModel.pendingActionCount.collectAsState()
    val quantityPresets by viewModel.quantityPresets.collectAsState()
    val timerValue by viewModel.submissionTimer.collectAsState()

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
        Text("Offline Queue", style = MaterialTheme.typography.titleLarge)
        Spacer(modifier = Modifier.height(8.dp))
        Text("Pending Actions: $pendingCount")

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

@Composable
internal fun QuickLogQuantityEditor(
    quantityPresets: List<Double>,
    onSave: (List<Double>) -> Unit,
) {
    var presetInputs by remember {
        mutableStateOf(quantityPresets.toPresetInputStrings())
    }
    var presetsSaved by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current

    LaunchedEffect(quantityPresets) {
        presetInputs = quantityPresets.toPresetInputStrings()
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

    Text("Quick-log quantities", style = MaterialTheme.typography.titleLarge)
    Spacer(modifier = Modifier.height(8.dp))
    Text(
        "Choose up to 10 quantities to show as shortcuts when logging consumption.",
        style = MaterialTheme.typography.bodyMedium
    )
    Spacer(modifier = Modifier.height(12.dp))

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
                modifier = Modifier.weight(1f),
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
                enabled = presetInputs.size >
                    ConsumptionPreferencesRepository.MIN_QUANTITY_PRESETS,
            ) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "Remove preset ${index + 1}",
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
        modifier = Modifier.fillMaxWidth(),
        enabled = presetInputs.size <
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
            onSave(parsedPresets.filterNotNull())
            presetsSaved = true
            focusManager.clearFocus()
        },
        modifier = Modifier.fillMaxWidth(),
        enabled = presetsAreValid
    ) {
        Text("Save quantity presets")
    }
    if (presetsSaved) {
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            "Quantity presets saved",
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

internal fun endpointDiagnostic(url: String): String = runCatching {
    val uri = URI(url)
    val deployment = uri.path.substringAfter("/macros/s/").substringBefore('/').takeLast(10)
    "${uri.host}/…$deployment"
}.getOrDefault("invalid endpoint")

private fun List<Double>.toPresetInputStrings(): List<String> {
    val validPresets = takeIf(ConsumptionPreferencesRepository::isValidQuantityPresets)
        ?: ConsumptionPreferencesRepository.DEFAULT_QUANTITY_PRESETS

    return validPresets.map { it.toString().removeSuffix(".0") }
}
