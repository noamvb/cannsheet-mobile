package com.example.ui

import android.text.format.DateFormat
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.Product
import com.example.data.ProductStatus
import com.example.data.productStatus
import java.math.BigDecimal
import java.util.Calendar

private val categoryColors = mapOf(
    "P" to Color(0xFFE57373),
    "E" to Color(0xFF81C784),
    "J" to Color(0xFF64B5F6),
    "F" to Color(0xFFFFB74D),
    "S" to Color(0xFFBA68C8),
    "K" to Color(0xFF4DB6AC),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConsumptionScreen(viewModel: CannsheetViewModel) {
    val allProducts by viewModel.allProducts.collectAsStateWithLifecycle()
    val recentProducts by viewModel.recentProducts.collectAsStateWithLifecycle()
    val quantityPresets by viewModel.quantityPresets.collectAsStateWithLifecycle()
    val includeUnopened by viewModel.includeUnopened.collectAsStateWithLifecycle()
    val formState by viewModel.consumptionFormState.collectAsStateWithLifecycle()
    val syncStatus by viewModel.syncStatus.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(syncStatus) {
        syncStatus?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            viewModel.clearSyncStatus()
        }
    }

    ConsumptionContent(
        allProducts = allProducts,
        recentProducts = recentProducts,
        quantityPresets = quantityPresets,
        includeUnopened = includeUnopened,
        formState = formState,
        onSelectProduct = viewModel::selectConsumptionProduct,
        onQuantityChange = viewModel::updateConsumptionQuantity,
        onIncludeUnopenedChange = viewModel::setIncludeUnopened,
        onLog = viewModel::queueConsumption,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConsumptionContent(
    allProducts: List<Product>,
    recentProducts: List<RecentProduct>,
    quantityPresets: List<Double>,
    includeUnopened: Boolean,
    formState: ConsumptionFormState,
    onSelectProduct: (String) -> Unit,
    onQuantityChange: (String) -> Unit,
    onIncludeUnopenedChange: (Boolean) -> Unit,
    onLog: (String, String, String, Double, Boolean) -> Unit,
) {
    var showProductPicker by rememberSaveable { mutableStateOf(false) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var selectedCategory by rememberSaveable { mutableStateOf<String?>(null) }
    var isFinished by rememberSaveable { mutableStateOf(false) }
    var adjustDateTime by rememberSaveable { mutableStateOf(false) }
    var customDateMillis by rememberSaveable { mutableLongStateOf(currentLocalDateAsPickerMillis()) }
    var customHour by rememberSaveable { mutableIntStateOf(Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) }
    var customMinute by rememberSaveable { mutableIntStateOf(Calendar.getInstance().get(Calendar.MINUTE)) }
    var showDatePicker by rememberSaveable { mutableStateOf(false) }
    var showTimePicker by rememberSaveable { mutableStateOf(false) }
    var validationMessage by rememberSaveable { mutableStateOf<String?>(null) }

    val selectedProduct = remember(allProducts, formState.selectedProductId) {
        allProducts.firstOrNull { it.id == formState.selectedProductId }
    }
    val categories = remember(allProducts) {
        allProducts.map(Product::type).filter(String::isNotBlank).distinct().sorted()
    }
    val filteredProducts = remember(
        allProducts,
        includeUnopened,
        searchQuery,
        selectedCategory,
    ) {
        filterSelectableProducts(
            products = allProducts,
            includeUnopened = includeUnopened,
            query = searchQuery,
            category = selectedCategory,
        )
    }

    if (showProductPicker) {
        ProductPickerSheet(
            products = filteredProducts,
            categories = categories,
            selectedCategory = selectedCategory,
            searchQuery = searchQuery,
            includeUnopened = includeUnopened,
            onSearchQueryChange = { searchQuery = it },
            onCategoryChange = { selectedCategory = it },
            onIncludeUnopenedChange = onIncludeUnopenedChange,
            onProductSelected = { product ->
                onSelectProduct(product.id)
                validationMessage = null
                showProductPicker = false
            },
            onDismiss = { showProductPicker = false },
        )
    }

    if (showDatePicker) {
        val datePickerState = rememberDatePickerState(initialSelectedDateMillis = customDateMillis)
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        datePickerState.selectedDateMillis?.let { customDateMillis = it }
                        showDatePicker = false
                    },
                ) { Text("Use date") }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) { Text("Cancel") }
            },
        ) {
            DatePicker(state = datePickerState)
        }
    }

    if (showTimePicker) {
        val context = LocalContext.current
        val timePickerState = rememberTimePickerState(
            initialHour = customHour,
            initialMinute = customMinute,
            is24Hour = DateFormat.is24HourFormat(context),
        )
        AlertDialog(
            onDismissRequest = { showTimePicker = false },
            title = { Text("Select time") },
            text = { TimePicker(state = timePickerState) },
            confirmButton = {
                TextButton(
                    onClick = {
                        customHour = timePickerState.hour
                        customMinute = timePickerState.minute
                        showTimePicker = false
                    },
                ) { Text("Use time") }
            },
            dismissButton = {
                TextButton(onClick = { showTimePicker = false }) { Text("Cancel") }
            },
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding(),
    ) {
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            item {
                Text("Log Consumption", style = MaterialTheme.typography.headlineMedium)
            }

            if (recentProducts.isNotEmpty()) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Recent products", style = MaterialTheme.typography.titleMedium)
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            items(recentProducts, key = { it.product.id }) { recent ->
                                RecentProductCard(
                                    recent = recent,
                                    selected = recent.product.id == formState.selectedProductId,
                                    onClick = {
                                        onSelectProduct(recent.product.id)
                                        validationMessage = null
                                    },
                                )
                            }
                        }
                    }
                }
            }

            item {
                ProductSelectionCard(
                    product = selectedProduct,
                    onClick = { showProductPicker = true },
                )
            }

            item {
                QuantitySection(
                    presets = quantityPresets,
                    quantityText = formState.quantityText,
                    onQuantityChange = {
                        onQuantityChange(it)
                        validationMessage = null
                    },
                )
            }

            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { isFinished = !isFinished },
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    ),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Mark product as finished", fontWeight = FontWeight.Medium)
                            Text(
                                "It will no longer appear in product choices.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Switch(checked = isFinished, onCheckedChange = { isFinished = it })
                    }
                }
            }

            item {
                DateTimeSection(
                    adjustDateTime = adjustDateTime,
                    customDateMillis = customDateMillis,
                    customHour = customHour,
                    customMinute = customMinute,
                    onToggleAdjustment = {
                        if (!adjustDateTime) {
                            val now = Calendar.getInstance()
                            customDateMillis = currentLocalDateAsPickerMillis(now.timeInMillis)
                            customHour = now.get(Calendar.HOUR_OF_DAY)
                            customMinute = now.get(Calendar.MINUTE)
                        }
                        adjustDateTime = !adjustDateTime
                    },
                    onUseNow = { adjustDateTime = false },
                    onChooseDate = { showDatePicker = true },
                    onChooseTime = { showTimePicker = true },
                )
            }

            validationMessage?.let { message ->
                item {
                    Text(
                        message,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }

        Button(
            onClick = {
                val quantity = formState.quantityText.toDoubleOrNull()
                when {
                    selectedProduct == null -> validationMessage = "Choose a product to continue."
                    !selectedProduct.productStatus.isSelectable -> {
                        validationMessage = "This product is no longer available. Choose another product."
                    }
                    quantity == null || !quantity.isFinite() || quantity <= 0.0 -> {
                        validationMessage = "Enter a positive quantity."
                    }
                    else -> {
                        val submittedAt = if (adjustDateTime) {
                            SubmissionDateTime(
                                date = pickerDateToWire(customDateMillis),
                                time = timeToWire(customHour, customMinute),
                            )
                        } else {
                            currentSubmissionDateTime()
                        }
                        onLog(
                            submittedAt.date,
                            submittedAt.time,
                            selectedProduct.id,
                            quantity,
                            isFinished,
                        )
                        isFinished = false
                        adjustDateTime = false
                        validationMessage = null
                    }
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .height(56.dp),
        ) {
            Text("Log Consumption", style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun RecentProductCard(
    recent: RecentProduct,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .width(156.dp)
            .heightIn(min = 104.dp)
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
        ),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                recent.product.name,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "${recent.product.productStatus.label} · ${recent.product.type}",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "Last: ${formatQuantity(recent.lastQuantity)}",
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

@Composable
private fun ProductSelectionCard(product: Product?, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    if (product == null) "Choose a product" else product.name,
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    if (product == null) {
                        "Search active and unopened products"
                    } else {
                        "${product.productStatus.label} · ${product.type} · ${product.id}"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(Icons.Default.Search, contentDescription = "Search products")
        }
    }
}

@Composable
private fun QuantitySection(
    presets: List<Double>,
    quantityText: String,
    onQuantityChange: (String) -> Unit,
) {
    val currentQuantity = quantityText.toDoubleOrNull()
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Quantity", style = MaterialTheme.typography.titleMedium)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(presets) { preset ->
                FilterChip(
                    selected = currentQuantity == preset,
                    onClick = { onQuantityChange(formatQuantity(preset)) },
                    label = { Text(formatQuantity(preset)) },
                )
            }
        }
        OutlinedTextField(
            value = quantityText,
            onValueChange = onQuantityChange,
            label = { Text("Custom quantity") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            singleLine = true,
            isError = quantityText.isNotBlank() &&
                (currentQuantity == null || !currentQuantity.isFinite() || currentQuantity <= 0.0),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun DateTimeSection(
    adjustDateTime: Boolean,
    customDateMillis: Long,
    customHour: Int,
    customMinute: Int,
    onToggleAdjustment: () -> Unit,
    onUseNow: () -> Unit,
    onChooseDate: () -> Unit,
    onChooseTime: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Date & time", fontWeight = FontWeight.Medium)
                    Text(
                        if (adjustDateTime) {
                            "${pickerDateToWire(customDateMillis)} at ${timeToWire(customHour, customMinute)}"
                        } else {
                            "Now"
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                TextButton(onClick = onToggleAdjustment) {
                    Text(if (adjustDateTime) "Collapse" else "Adjust")
                }
            }
            AnimatedVisibility(visible = adjustDateTime) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedButton(onClick = onChooseDate, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Default.DateRange, contentDescription = null)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Date")
                        }
                        OutlinedButton(onClick = onChooseTime, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Default.AccessTime, contentDescription = null)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Time")
                        }
                    }
                    TextButton(onClick = onUseNow, modifier = Modifier.align(Alignment.End)) {
                        Text("Use current date & time")
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProductPickerSheet(
    products: List<Product>,
    categories: List<String>,
    selectedCategory: String?,
    searchQuery: String,
    includeUnopened: Boolean,
    onSearchQueryChange: (String) -> Unit,
    onCategoryChange: (String?) -> Unit,
    onIncludeUnopenedChange: (Boolean) -> Unit,
    onProductSelected: (Product) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Choose a product", style = MaterialTheme.typography.headlineSmall)
            OutlinedTextField(
                value = searchQuery,
                onValueChange = onSearchQueryChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Search name, ID, or type") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { onSearchQueryChange("") }) {
                            Icon(Icons.Default.Close, contentDescription = "Clear search")
                        }
                    }
                },
                singleLine = true,
            )
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    FilterChip(
                        selected = selectedCategory == null,
                        onClick = { onCategoryChange(null) },
                        label = { Text("All types") },
                    )
                }
                items(categories) { category ->
                    val color = categoryColors[category] ?: MaterialTheme.colorScheme.primary
                    FilterChip(
                        selected = selectedCategory == category,
                        onClick = {
                            onCategoryChange(if (selectedCategory == category) null else category)
                        },
                        label = { Text(category) },
                        colors = androidx.compose.material3.FilterChipDefaults.filterChipColors(
                            selectedContainerColor = color.copy(alpha = 0.22f),
                        ),
                    )
                }
            }
            FilterChip(
                selected = includeUnopened,
                onClick = { onIncludeUnopenedChange(!includeUnopened) },
                label = { Text("Include unopened products") },
            )
            HorizontalDivider()
            if (products.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(160.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("No matching products")
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 480.dp),
                ) {
                    items(products, key = Product::id) { product ->
                        ListItem(
                            headlineContent = { Text(product.name) },
                            supportingContent = {
                                Text("${product.productStatus.label} · ${product.type} · ${product.id}")
                            },
                            modifier = Modifier.clickable { onProductSelected(product) },
                        )
                        HorizontalDivider()
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

private fun formatQuantity(quantity: Double): String =
    BigDecimal.valueOf(quantity).stripTrailingZeros().toPlainString()

internal fun filterSelectableProducts(
    products: List<Product>,
    includeUnopened: Boolean,
    query: String,
    category: String?,
): List<Product> {
    val normalizedQuery = query.trim()
    return products.asSequence()
        .filter { product ->
            product.productStatus == ProductStatus.ACTIVE ||
                (includeUnopened && product.productStatus == ProductStatus.UNOPENED)
        }
        .filter { category == null || it.type == category }
        .filter { product ->
            normalizedQuery.isEmpty() ||
                product.name.contains(normalizedQuery, ignoreCase = true) ||
                product.id.contains(normalizedQuery, ignoreCase = true) ||
                product.type.contains(normalizedQuery, ignoreCase = true)
        }
        .sortedBy { it.name.lowercase() }
        .toList()
}
