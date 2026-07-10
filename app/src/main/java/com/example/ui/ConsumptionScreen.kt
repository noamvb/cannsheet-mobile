package com.example.ui

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.data.Product
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConsumptionScreen(viewModel: CannsheetViewModel) {
    val allProducts by viewModel.allProducts.collectAsState()
    val showStatus2 by viewModel.showStatus2.collectAsState()
    val syncStatus by viewModel.syncStatus.collectAsState()
    val context = LocalContext.current

    LaunchedEffect(syncStatus) {
        syncStatus?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            viewModel.clearSyncStatus()
        }
    }

    var selectedCategory by remember { mutableStateOf<String?>(null) }
    var selectedProduct by remember { mutableStateOf<Product?>(null) }
    var expanded by remember { mutableStateOf(false) }

    var usesStr by remember { mutableStateOf("1.0") }
    var isFinished by remember { mutableStateOf(false) }

    val today = remember { SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date()) }
    var date by remember { mutableStateOf(today) }
    var showDatePicker by remember { mutableStateOf(false) }
    var time by remember { mutableStateOf("") }

    val categories = listOf("P", "E", "J", "F", "S", "K")

    val categoryColors = remember {
        mapOf(
            "P" to androidx.compose.ui.graphics.Color(0xFFE57373), // Red
            "E" to androidx.compose.ui.graphics.Color(0xFF81C784), // Green
            "J" to androidx.compose.ui.graphics.Color(0xFF64B5F6), // Blue
            "F" to androidx.compose.ui.graphics.Color(0xFFFFB74D), // Orange
            "S" to androidx.compose.ui.graphics.Color(0xFFBA68C8), // Purple
            "K" to androidx.compose.ui.graphics.Color(0xFF4DB6AC)  // Teal
        )
    }

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
        Text("Log Consumption", style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.height(16.dp))

        // Category Filter
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            categories.forEach { cat ->
                val catColor = categoryColors[cat] ?: MaterialTheme.colorScheme.primary
                FilterChip(
                    selected = selectedCategory == cat,
                    onClick = {
                        selectedCategory = if (selectedCategory == cat) null else cat
                        selectedProduct = null
                    },
                    label = { Text(cat) },
                    shape = CircleShape,
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = catColor.copy(alpha = 0.3f),
                        selectedLabelColor = catColor
                    )
                )
            }
        }
        Spacer(modifier = Modifier.height(16.dp))

        // Product Selection
        val filteredProducts = allProducts.filter {
            val statusMatches = if (showStatus2) {
                it.status == 0 || it.status == 2
            } else {
                it.status == 0
            }
            (selectedCategory == null || it.type == selectedCategory) && statusMatches
        }

        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = !expanded }
        ) {
            OutlinedTextField(
                value = selectedProduct?.name ?: "Select a product",
                onValueChange = {},
                readOnly = true,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                modifier = Modifier.menuAnchor().fillMaxWidth(),
                textStyle = LocalTextStyle.current.copy(
                    color = selectedProduct?.let { categoryColors[it.type] } ?: MaterialTheme.colorScheme.onSurface
                )
            )
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                if (filteredProducts.isEmpty()) {
                    DropdownMenuItem(text = { Text("No products found") }, onClick = { expanded = false })
                }
                filteredProducts.forEach { product ->
                    DropdownMenuItem(
                        text = { Text(product.name, color = categoryColors[product.type] ?: MaterialTheme.colorScheme.onSurface) },
                        onClick = {
                            selectedProduct = product
                            expanded = false
                        }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = showStatus2, onCheckedChange = { viewModel.toggleShowStatus2() })
            Text("Show unstarted products (Status 2)")
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Uses Stepper
        Text("Uses (Quantity)", style = MaterialTheme.typography.titleMedium)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxWidth()
        ) {
            FilledIconButton(
                onClick = {
                    val current = usesStr.toDoubleOrNull() ?: 1.0
                    usesStr = (current - 1).coerceAtLeast(0.0).toString()
                },
                modifier = Modifier.size(64.dp)
            ) {
                Icon(Icons.Filled.Remove, contentDescription = "Decrease", modifier = Modifier.size(32.dp))
            }
            Spacer(modifier = Modifier.width(16.dp))
            OutlinedTextField(
                value = usesStr,
                onValueChange = { usesStr = it },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.width(100.dp),
                textStyle = LocalTextStyle.current.copy(textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            )
            Spacer(modifier = Modifier.width(16.dp))
            FilledIconButton(
                onClick = {
                    val current = usesStr.toDoubleOrNull() ?: 0.0
                    usesStr = (current + 1).toString()
                },
                modifier = Modifier.size(64.dp)
            ) {
                Icon(Icons.Filled.Add, contentDescription = "Increase", modifier = Modifier.size(32.dp))
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = isFinished, onCheckedChange = { isFinished = it })
            Spacer(modifier = Modifier.width(8.dp))
            Text("This use has finished the product")
        }

        Spacer(modifier = Modifier.height(24.dp))

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
        OutlinedTextField(
            value = time,
            onValueChange = { time = it },
            label = { Text("Time (Optional, default now)") },
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(32.dp))

        Button(
            onClick = {
                val uses = usesStr.toDoubleOrNull()
                if (selectedProduct == null) {
                    Toast.makeText(context, "Please select a product", Toast.LENGTH_SHORT).show()
                } else if (uses == null || uses <= 0.0) {
                    Toast.makeText(context, "Please enter a positive amount", Toast.LENGTH_SHORT).show()
                } else if (date.isBlank()) {
                    Toast.makeText(context, "Please select a date", Toast.LENGTH_SHORT).show()
                } else {
                    viewModel.queueConsumption(
                        date = date,
                        time = time,
                        productId = selectedProduct!!.id,
                        uses = uses,
                        isFinished = isFinished
                    )
                    // Reset
                    // selectedProduct is kept for the next entry
                    usesStr = "1.0"
                    isFinished = false
                    date = today
                    time = ""
                }
            },
            modifier = Modifier.fillMaxWidth().height(56.dp)
        ) {
            Text("Log Consumption", style = MaterialTheme.typography.titleMedium)
        }
    }
}
