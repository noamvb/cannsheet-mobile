package com.example.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.data.Product
import com.example.data.PurchaseDefaultsState
import com.example.data.PurchaseSubmission

@Composable
fun PurchaseScreen(
    viewModel: CannsheetViewModel,
    onScanRequested: (() -> Unit)? = null,
) {
    val products by viewModel.allProducts.collectAsState()
    val purchaseDefaultsState by viewModel.purchaseDefaultsState.collectAsState()
    val formState by viewModel.purchaseFormState.collectAsState()
    val purchaseTaxRate by viewModel.purchaseTaxRate.collectAsState()
    val purchaseFeedback by viewModel.purchaseFeedback.collectAsState()
    val context = LocalContext.current

    LaunchedEffect(purchaseFeedback) {
        purchaseFeedback?.let { feedback ->
            Toast.makeText(context, feedback.message, Toast.LENGTH_SHORT).show()
            viewModel.clearPurchaseFeedback()
        }
    }

    PurchaseContent(
        products = products,
        purchaseDefaultsState = purchaseDefaultsState,
        formState = formState,
        taxRate = purchaseTaxRate,
        onFormChange = viewModel::updatePurchaseForm,
        onQueuePurchase = { submission -> viewModel.queuePurchase(submission) },
        onScanRequested = onScanRequested,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PurchaseContent(
    products: List<Product>,
    purchaseDefaultsState: PurchaseDefaultsState,
    formState: PurchaseFormState,
    taxRate: Double? = null,
    onFormChange: (PurchaseFormState) -> Unit,
    onQueuePurchase: (PurchaseSubmission) -> Unit,
    modifier: Modifier = Modifier,
    onScanRequested: (() -> Unit)? = null,
) {
    var showDatePicker by remember { mutableStateOf(false) }
    var typeExpanded by remember { mutableStateOf(false) }
    var nameExpanded by remember { mutableStateOf(false) }
    val costTaxPreview = purchaseTaxPreview(formState.cost, formState.postTax, taxRate)

    val categories = ProductTypes.CODES
    val suggestions = remember(products, purchaseDefaultsState, formState.type, formState.name) {
        if (purchaseDefaultsState is PurchaseDefaultsState.Loaded) {
            purchaseSuggestions(
                products = products,
                selectedType = formState.type,
                query = formState.name,
            )
        } else {
            emptyList()
        }
    }

    if (showDatePicker) {
        val initialMillis = remember(formState.date) {
            parsePickerDateToMillis(formState.date) ?: currentLocalDateAsPickerMillis()
        }
        val datePickerState = rememberDatePickerState(initialSelectedDateMillis = initialMillis)
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        datePickerState.selectedDateMillis?.let { selectedDateMillis ->
                            onFormChange(formState.copy(date = pickerDateToWire(selectedDateMillis)))
                        }
                        showDatePicker = false
                    },
                ) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) { Text("Cancel") }
            },
        ) {
            DatePicker(state = datePickerState)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Add Purchase", style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.height(16.dp))

        if (onScanRequested != null) {
            OutlinedButton(
                onClick = onScanRequested,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(PurchaseContentTestTags.SCAN),
            ) {
                Icon(
                    Icons.Default.QrCodeScanner,
                    contentDescription = null,
                    modifier = Modifier.padding(end = 8.dp),
                )
                Text("Scan product barcode")
            }
            Spacer(modifier = Modifier.height(16.dp))
        }

        OutlinedTextField(
            value = formState.date,
            onValueChange = {},
            readOnly = true,
            label = { Text("Date") },
            modifier = Modifier
                .fillMaxWidth()
                .testTag(PurchaseContentTestTags.DATE),
            trailingIcon = {
                IconButton(onClick = { showDatePicker = true }) {
                    Icon(Icons.Default.DateRange, contentDescription = "Select Date")
                }
            },
        )
        Spacer(modifier = Modifier.height(8.dp))

        ExposedDropdownMenuBox(
            expanded = typeExpanded,
            onExpandedChange = { typeExpanded = !typeExpanded },
        ) {
            OutlinedTextField(
                value = formState.type.ifEmpty { "Select Type" },
                onValueChange = {},
                readOnly = true,
                label = { Text("Type") },
                trailingIcon = {
                    ExposedDropdownMenuDefaults.TrailingIcon(expanded = typeExpanded)
                },
                modifier = Modifier
                    .menuAnchor()
                    .fillMaxWidth()
                    .testTag(PurchaseContentTestTags.TYPE),
            )
            ExposedDropdownMenu(
                expanded = typeExpanded,
                onDismissRequest = { typeExpanded = false },
                modifier = Modifier.testTag(PurchaseContentTestTags.TYPE_MENU),
            ) {
                categories.forEach { category ->
                    DropdownMenuItem(
                        text = { Text(category) },
                        modifier = Modifier.testTag(PurchaseContentTestTags.typeOption(category)),
                        onClick = {
                            if (formState.type != category) {
                                onFormChange(
                                    formState.copy(type = category, name = "")
                                        .clearedForNewSelection(),
                                )
                                nameExpanded = false
                            }
                            typeExpanded = false
                        },
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
        ExposedDropdownMenuBox(
            expanded = nameExpanded && formState.type.isNotBlank() && suggestions.isNotEmpty(),
            onExpandedChange = { expanded ->
                nameExpanded = expanded && formState.type.isNotBlank()
            },
        ) {
            OutlinedTextField(
                value = formState.name,
                onValueChange = {
                    onFormChange(
                        formState.copy(
                            name = it,
                            appliedAutofillMessage = null,
                            validationMessage = null,
                        ),
                    )
                    nameExpanded = formState.type.isNotBlank()
                },
                enabled = formState.type.isNotBlank(),
                label = { Text("Product Name") },
                modifier = Modifier
                    .menuAnchor()
                    .fillMaxWidth()
                    .testTag(PurchaseContentTestTags.NAME),
                trailingIcon = {
                    ExposedDropdownMenuDefaults.TrailingIcon(
                        expanded = nameExpanded && formState.type.isNotBlank(),
                    )
                },
                colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
            )
            ExposedDropdownMenu(
                expanded = nameExpanded && formState.type.isNotBlank() && suggestions.isNotEmpty(),
                onDismissRequest = { nameExpanded = false },
                modifier = Modifier.testTag(PurchaseContentTestTags.SUGGESTIONS),
            ) {
                suggestions.forEach { product ->
                    DropdownMenuItem(
                        text = { Text(product.name) },
                        modifier = Modifier.testTag(PurchaseContentTestTags.suggestion(product.name)),
                        onClick = {
                            onFormChange(formState.withAutofillFor(product, purchaseDefaultsState))
                            nameExpanded = false
                        },
                    )
                }
            }
        }

        formState.appliedAutofillMessage?.let { message ->
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = message,
                modifier = Modifier.testTag(PurchaseContentTestTags.APPLIED_AUTOFILL),
                style = MaterialTheme.typography.bodySmall,
            )
        }

        if (formState.pendingScanGtin != null) {
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Barcode attached. It will be remembered when you add this purchase.",
                    modifier = Modifier
                        .weight(1f)
                        .testTag(PurchaseContentTestTags.SCAN_ATTACHED),
                    style = MaterialTheme.typography.bodySmall,
                )
                TextButton(
                    onClick = { onFormChange(formState.withoutPendingScan()) },
                    modifier = Modifier.testTag(PurchaseContentTestTags.SCAN_DETACH),
                ) {
                    Text("Remove")
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Price entered as",
            style = MaterialTheme.typography.labelLarge,
        )
        Spacer(modifier = Modifier.height(4.dp))
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            SegmentedButton(
                selected = !formState.postTax,
                onClick = {
                    onFormChange(formState.copy(postTax = false, validationMessage = null))
                },
                shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                modifier = Modifier.testTag(PurchaseContentTestTags.PRICE_BASIS_PRE_TAX),
            ) {
                Text("Pre-tax")
            }
            SegmentedButton(
                selected = formState.postTax,
                onClick = {
                    onFormChange(formState.copy(postTax = true, validationMessage = null))
                },
                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                modifier = Modifier.testTag(PurchaseContentTestTags.PRICE_BASIS_POST_TAX),
            ) {
                Text("Post-tax")
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = formState.cost,
                onValueChange = {
                    onFormChange(formState.copy(cost = it, validationMessage = null))
                },
                label = { Text(if (formState.postTax) "Post-tax cost" else "Pre-tax cost") },
                supportingText = if (costTaxPreview != null) {
                    {
                        Text(
                            text = costTaxPreview,
                            modifier = Modifier.testTag(PurchaseContentTestTags.COST_TAX_PREVIEW),
                        )
                    }
                } else {
                    null
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                isError = formState.validationMessage != null &&
                    !isNonNegativeFinite(formState.cost),
                modifier = Modifier
                    .weight(1f)
                    .testTag(PurchaseContentTestTags.COST),
            )
            OutlinedTextField(
                value = formState.thc,
                onValueChange = {
                    onFormChange(
                        formState.copy(
                            thc = it,
                            validationMessage = null,
                            thcNeedsVerification = false,
                        ),
                    )
                },
                label = { Text("THC") },
                supportingText = if (formState.thcNeedsVerification) {
                    {
                        Text(
                            text = "New batch, check this",
                            modifier = Modifier.testTag(PurchaseContentTestTags.THC_STALE),
                        )
                    }
                } else {
                    null
                },
                trailingIcon = { Text("%", modifier = Modifier.padding(end = 12.dp)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                isError = formState.validationMessage != null && !isValidThcPercent(
                    value = formState.thc,
                    requiresExplicitValue = formState.saveAsDefault,
                ),
                modifier = Modifier
                    .weight(1f)
                    .testTag(PurchaseContentTestTags.THC),
            )
        }

        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
            value = formState.grams,
            onValueChange = {
                onFormChange(formState.copy(grams = it, validationMessage = null))
            },
            label = { Text("Grams") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            isError = formState.validationMessage != null && !isPositiveFinite(formState.grams),
            modifier = Modifier
                .fillMaxWidth()
                .testTag(PurchaseContentTestTags.GRAMS),
        )

        Spacer(modifier = Modifier.height(16.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("Borrowed")
            Switch(
                checked = formState.borrowed,
                onCheckedChange = {
                    onFormChange(formState.copy(borrowed = it, validationMessage = null))
                },
                modifier = Modifier.testTag(PurchaseContentTestTags.BORROWED),
            )
        }

        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "Use these values as future defaults for this product and type",
                modifier = Modifier.weight(1f),
            )
            Switch(
                checked = formState.saveAsDefault,
                onCheckedChange = {
                    onFormChange(formState.copy(saveAsDefault = it, validationMessage = null))
                },
                modifier = Modifier.testTag(PurchaseContentTestTags.SAVE_AS_DEFAULT),
            )
        }

        formState.validationMessage?.let { message ->
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = message,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag(PurchaseContentTestTags.VALIDATION_ERROR),
            )
        }

        Spacer(modifier = Modifier.height(32.dp))
        Button(
            onClick = {
                val costValue = formState.cost.toDoubleOrNull()
                val thcPercent = formState.thc.toDoubleOrNull()
                val gramsValue = formState.grams.toDoubleOrNull()
                val hasOrdinaryRequiredFields =
                    formState.type.isNotBlank() &&
                        formState.name.isNotBlank() &&
                        formState.cost.isNotBlank() &&
                        formState.grams.isNotBlank()
                val hasValidSavedDefaultValues =
                    costValue != null && costValue.isFinite() && costValue >= 0.0 &&
                        isValidThcPercent(formState.thc, requiresExplicitValue = true) &&
                        gramsValue != null && gramsValue.isFinite() && gramsValue > 0.0
                val valid =
                    formState.type.isNotBlank() &&
                        formState.name.isNotBlank() &&
                        if (formState.saveAsDefault) {
                            hasValidSavedDefaultValues
                        } else {
                            hasOrdinaryRequiredFields
                        }
                if (!valid) {
                    onFormChange(
                        formState.copy(
                            validationMessage =
                                "Choose a type and name, then enter a non-negative cost and positive grams. THC must be between 0 and 100%; saving a default requires THC to be entered.",
                        ),
                    )
                } else {
                    onQueuePurchase(
                        PurchaseSubmission(
                            date = formState.date,
                            type = formState.type.trim(),
                            name = formState.name.trim(),
                            cost = costValue ?: 0.0,
                            thc = (thcPercent ?: 0.0) / 100.0,
                            grams = gramsValue ?: 0.0,
                            borrowed = formState.borrowed,
                            postTax = formState.postTax,
                            saveAsDefault = formState.saveAsDefault,
                        ),
                    )
                    onFormChange(formState.reset())
                    typeExpanded = false
                    nameExpanded = false
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .testTag(PurchaseContentTestTags.SUBMIT),
        ) {
            Text("Add Purchase", style = MaterialTheme.typography.titleMedium)
        }
    }
}

private fun isPositiveFinite(value: String): Boolean =
    value.toDoubleOrNull()?.let { it.isFinite() && it > 0.0 } == true

private fun isNonNegativeFinite(value: String): Boolean =
    value.toDoubleOrNull()?.let { it.isFinite() && it >= 0.0 } == true

private fun isValidThcPercent(value: String, requiresExplicitValue: Boolean = false): Boolean {
    if (value.isBlank()) return !requiresExplicitValue
    return value.toDoubleOrNull()?.let { it.isFinite() && it in 0.0..100.0 } == true
}
