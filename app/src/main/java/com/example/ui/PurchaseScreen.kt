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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
fun PurchaseScreen(viewModel: CannsheetViewModel) {
    val products by viewModel.allProducts.collectAsState()
    val purchaseDefaultsState by viewModel.purchaseDefaultsState.collectAsState()
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
        onQueuePurchase = { submission -> viewModel.queuePurchase(submission) },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PurchaseContent(
    products: List<Product>,
    purchaseDefaultsState: PurchaseDefaultsState,
    onQueuePurchase: (PurchaseSubmission) -> Unit,
    modifier: Modifier = Modifier,
) {
    val initialDate = remember { currentSubmissionDateTime().date }
    var date by remember { mutableStateOf(initialDate) }
    var showDatePicker by remember { mutableStateOf(false) }
    var type by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var cost by remember { mutableStateOf("") }
    var thc by remember { mutableStateOf("") }
    var grams by remember { mutableStateOf("") }
    var borrowed by remember { mutableStateOf(false) }
    var postTax by remember { mutableStateOf(false) }
    var saveAsDefault by remember { mutableStateOf(false) }
    var typeExpanded by remember { mutableStateOf(false) }
    var nameExpanded by remember { mutableStateOf(false) }
    var appliedAutofillMessage by remember { mutableStateOf<String?>(null) }
    var validationMessage by remember { mutableStateOf<String?>(null) }

    val categories = ProductTypes.CODES
    val suggestions = remember(products, purchaseDefaultsState, type, name) {
        if (purchaseDefaultsState is PurchaseDefaultsState.Loaded) {
            purchaseSuggestions(products = products, selectedType = type, query = name)
        } else {
            emptyList()
        }
    }

    fun clearValuesForNewSelection() {
        cost = ""
        thc = ""
        grams = ""
        borrowed = false
        postTax = false
        saveAsDefault = false
        appliedAutofillMessage = null
        validationMessage = null
    }

    fun resetForm() {
        date = initialDate
        type = ""
        name = ""
        clearValuesForNewSelection()
        typeExpanded = false
        nameExpanded = false
    }

    if (showDatePicker) {
        val initialMillis = remember(date) {
            parsePickerDateToMillis(date) ?: currentLocalDateAsPickerMillis()
        }
        val datePickerState = rememberDatePickerState(initialSelectedDateMillis = initialMillis)
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        datePickerState.selectedDateMillis?.let { date = pickerDateToWire(it) }
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

        OutlinedTextField(
            value = date,
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
                value = type.ifEmpty { "Select Type" },
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
                            if (type != category) {
                                type = category
                                name = ""
                                clearValuesForNewSelection()
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
            expanded = nameExpanded && type.isNotBlank() && suggestions.isNotEmpty(),
            onExpandedChange = { expanded ->
                nameExpanded = expanded && type.isNotBlank()
            },
        ) {
            OutlinedTextField(
                value = name,
                onValueChange = {
                    name = it
                    appliedAutofillMessage = null
                    validationMessage = null
                    nameExpanded = type.isNotBlank()
                },
                enabled = type.isNotBlank(),
                label = { Text("Product Name") },
                modifier = Modifier
                    .menuAnchor()
                    .fillMaxWidth()
                    .testTag(PurchaseContentTestTags.NAME),
                trailingIcon = {
                    ExposedDropdownMenuDefaults.TrailingIcon(
                        expanded = nameExpanded && type.isNotBlank(),
                    )
                },
                colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
            )
            ExposedDropdownMenu(
                expanded = nameExpanded && type.isNotBlank() && suggestions.isNotEmpty(),
                onDismissRequest = { nameExpanded = false },
                modifier = Modifier.testTag(PurchaseContentTestTags.SUGGESTIONS),
            ) {
                suggestions.forEach { product ->
                    DropdownMenuItem(
                        text = { Text(product.name) },
                        modifier = Modifier.testTag(PurchaseContentTestTags.suggestion(product.name)),
                        onClick = {
                            name = product.name
                            clearValuesForNewSelection()
                            val savedDefault = matchingSavedDefault(
                                defaultsState = purchaseDefaultsState,
                                type = type,
                                name = product.name,
                            )
                            if (savedDefault != null) {
                                cost = canonicalPurchaseNumber(savedDefault.cost)
                                thc = canonicalPurchaseNumber(savedDefault.thc * 100.0)
                                grams = canonicalPurchaseNumber(savedDefault.grams)
                                appliedAutofillMessage = "Saved defaults applied."
                            } else {
                                cost = product.cost.takeIf { it.isFinite() && it > 0.0 }
                                    ?.let(::canonicalPurchaseNumber)
                                    .orEmpty()
                                thc = catalogThcPercent(product.thc)
                                    ?.let(::canonicalPurchaseNumber)
                                    .orEmpty()
                                grams = product.grams.takeIf { it.isFinite() && it > 0.0 }
                                    ?.let(::canonicalPurchaseNumber)
                                    .orEmpty()
                            }
                            nameExpanded = false
                        },
                    )
                }
            }
        }

        appliedAutofillMessage?.let { message ->
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = message,
                modifier = Modifier.testTag(PurchaseContentTestTags.APPLIED_AUTOFILL),
                style = MaterialTheme.typography.bodySmall,
            )
        }

        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = cost,
                onValueChange = {
                    cost = it
                    validationMessage = null
                },
                label = { Text("Pre-tax Cost") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                isError = validationMessage != null && !isNonNegativeFinite(cost),
                modifier = Modifier
                    .weight(1f)
                    .testTag(PurchaseContentTestTags.COST),
            )
            OutlinedTextField(
                value = thc,
                onValueChange = {
                    thc = it
                    validationMessage = null
                },
                label = { Text("THC") },
                trailingIcon = { Text("%", modifier = Modifier.padding(end = 12.dp)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                isError = validationMessage != null && !isValidThcPercent(
                    value = thc,
                    requiresExplicitValue = saveAsDefault,
                ),
                modifier = Modifier
                    .weight(1f)
                    .testTag(PurchaseContentTestTags.THC),
            )
        }

        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
            value = grams,
            onValueChange = {
                grams = it
                validationMessage = null
            },
            label = { Text("Grams") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            isError = validationMessage != null && !isPositiveFinite(grams),
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
                checked = borrowed,
                onCheckedChange = {
                    borrowed = it
                    validationMessage = null
                },
                modifier = Modifier.testTag(PurchaseContentTestTags.BORROWED),
            )
        }

        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(
                checked = postTax,
                onCheckedChange = {
                    postTax = it
                    validationMessage = null
                },
                modifier = Modifier.testTag(PurchaseContentTestTags.POST_TAX),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text("Post-tax")
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
                checked = saveAsDefault,
                onCheckedChange = {
                    saveAsDefault = it
                    validationMessage = null
                },
                modifier = Modifier.testTag(PurchaseContentTestTags.SAVE_AS_DEFAULT),
            )
        }

        validationMessage?.let { message ->
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
                val costValue = cost.toDoubleOrNull()
                val thcPercent = thc.toDoubleOrNull()
                val gramsValue = grams.toDoubleOrNull()
                val hasOrdinaryRequiredFields =
                    type.isNotBlank() &&
                        name.isNotBlank() &&
                        cost.isNotBlank() &&
                        grams.isNotBlank()
                val hasValidSavedDefaultValues =
                    costValue != null && costValue.isFinite() && costValue >= 0.0 &&
                        isValidThcPercent(thc, requiresExplicitValue = true) &&
                        gramsValue != null && gramsValue.isFinite() && gramsValue > 0.0
                val valid =
                    type.isNotBlank() &&
                        name.isNotBlank() &&
                        if (saveAsDefault) {
                            hasValidSavedDefaultValues
                        } else {
                            hasOrdinaryRequiredFields
                        }
                if (!valid) {
                    validationMessage =
                        "Choose a type and name, then enter a non-negative cost and positive grams. THC must be between 0 and 100%; saving a default requires THC to be entered."
                } else {
                    onQueuePurchase(
                        PurchaseSubmission(
                            date = date,
                            type = type.trim(),
                            name = name.trim(),
                            cost = costValue ?: 0.0,
                            thc = (thcPercent ?: 0.0) / 100.0,
                            grams = gramsValue ?: 0.0,
                            borrowed = borrowed,
                            postTax = postTax,
                            saveAsDefault = saveAsDefault,
                        ),
                    )
                    resetForm()
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
