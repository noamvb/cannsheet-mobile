package com.example.ui

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PurchaseScreen(viewModel: CannsheetViewModel) {
    val context = LocalContext.current

    val today = remember { SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date()) }
    var date by remember { mutableStateOf(today) }
    var showDatePicker by remember { mutableStateOf(false) }

    var type by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var cost by remember { mutableStateOf("") }
    var thc by remember { mutableStateOf("") }
    var grams by remember { mutableStateOf("") }
    var borrowed by remember { mutableStateOf(false) }
    var postTax by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(false) }

    val categories = listOf("P", "E", "J", "F", "S", "K")

    if (showDatePicker) {
        val datePickerState = rememberDatePickerState(initialSelectedDateMillis = System.currentTimeMillis())
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    datePickerState.selectedDateMillis?.let {
                        date = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(it))
                    }
                    showDatePicker = false
                }) {
                    Text("OK")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) {
                    Text("Cancel")
                }
            }
        ) {
            DatePicker(state = datePickerState)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Add Purchase", style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.height(16.dp))

        OutlinedTextField(
            value = date,
            onValueChange = {},
            readOnly = true,
            label = { Text("Date") },
            modifier = Modifier.fillMaxWidth(),
            trailingIcon = {
                IconButton(onClick = { showDatePicker = true }) {
                    Icon(Icons.Default.DateRange, contentDescription = "Select Date")
                }
            }
        )
        Spacer(modifier = Modifier.height(8.dp))

        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = !expanded }
        ) {
            OutlinedTextField(
                value = type.ifEmpty { "Select Type" },
                onValueChange = {},
                readOnly = true,
                label = { Text("Type") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                modifier = Modifier.menuAnchor().fillMaxWidth()
            )
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                categories.forEach { cat ->
                    DropdownMenuItem(
                        text = { Text(cat) },
                        onClick = {
                            type = cat
                            expanded = false
                        }
                    )
                }
            }
        }

        val products by viewModel.allProducts.collectAsState()
        val productNames = remember(products) { products.map { it.name }.distinct() }
        val filteredNames = remember(name, productNames) {
            if (name.isEmpty()) productNames else productNames.filter { it.contains(name, ignoreCase = true) }
        }
        var nameExpanded by remember { mutableStateOf(false) }

        Spacer(modifier = Modifier.height(8.dp))
        ExposedDropdownMenuBox(
            expanded = nameExpanded && filteredNames.isNotEmpty(),
            onExpandedChange = { nameExpanded = !nameExpanded }
        ) {
            OutlinedTextField(
                value = name,
                onValueChange = {
                    name = it
                    nameExpanded = true
                },
                label = { Text("Product Name") },
                modifier = Modifier.menuAnchor().fillMaxWidth(),
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = nameExpanded) },
                colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors()
            )
            if (filteredNames.isNotEmpty()) {
                ExposedDropdownMenu(
                    expanded = nameExpanded,
                    onDismissRequest = { nameExpanded = false }
                ) {
                    filteredNames.take(5).forEach { suggestion ->
                        DropdownMenuItem(
                            text = { Text(suggestion) },
                            onClick = {
                                name = suggestion
                                val selectedProduct = products.lastOrNull { it.name == suggestion }
                                if (selectedProduct != null) {
                                    if (selectedProduct.type.isNotBlank()) type = selectedProduct.type
                                    if (selectedProduct.cost > 0) cost = selectedProduct.cost.toString()
                                    if (selectedProduct.thc > 0) thc = selectedProduct.thc.toString()
                                    if (selectedProduct.grams > 0) grams = selectedProduct.grams.toString()
                                }
                                nameExpanded = false
                            }
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = cost,
                onValueChange = { cost = it },
                label = { Text("Pre-tax Cost") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.weight(1f)
            )
            OutlinedTextField(
                value = thc,
                onValueChange = { thc = it },
                label = { Text("THC") },
                trailingIcon = { Text("%", modifier = Modifier.padding(end = 12.dp)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
            value = grams,
            onValueChange = { grams = it },
            label = { Text("Grams") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(16.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("Borrowed")
            Switch(checked = borrowed, onCheckedChange = { borrowed = it })
        }

        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("Post-tax")
            Checkbox(checked = postTax, onCheckedChange = { postTax = it })
        }

        Spacer(modifier = Modifier.height(32.dp))

        Button(
            onClick = {
                if (type.isEmpty() || name.isEmpty() || cost.isEmpty() || grams.isEmpty()) {
                    Toast.makeText(context, "Please fill required fields", Toast.LENGTH_SHORT).show()
                } else {
                    viewModel.queuePurchase(
                        date = date.ifEmpty { "Today" },
                        type = type,
                        name = name,
                        cost = cost.toDoubleOrNull() ?: 0.0,
                        thc = (thc.toDoubleOrNull() ?: 0.0) / 100.0,
                        grams = grams.toDoubleOrNull() ?: 0.0,
                        borrowed = borrowed,
                        postTax = postTax
                    )
                    // Reset
                    date = ""
                    type = ""
                    name = ""
                    cost = ""
                    thc = ""
                    grams = ""
                    borrowed = false
                    postTax = false
                }
            },
            modifier = Modifier.fillMaxWidth().height(56.dp)
        ) {
            Text("Add Purchase", style = MaterialTheme.typography.titleMedium)
        }
    }
}
