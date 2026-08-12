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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.Product
import com.example.data.ProductStatus
import com.example.data.productStatus
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Calendar

private val categoryColors = mapOf(
    "P" to Color(0xFFE57373),
    "E" to Color(0xFF81C784),
    "J" to Color(0xFF64B5F6),
    "F" to Color(0xFFFFB74D),
    "S" to Color(0xFFBA68C8),
    "K" to Color(0xFF4DB6AC),
)

private enum class ProductPickerMode {
    LOG_TARGET,
    LOADED_PEN,
}

internal object PenQuickLogTestTags {
    const val CARD = "pen-quick-log-card"
    const val CHOOSE_CART = "pen-quick-log-choose-cart"
    const val SWAP_CART = "pen-quick-log-swap-cart"

    fun quickLogChip(position: Int) = "pen-quick-log-chip-$position"
}

@Composable
internal fun PenQuickLogCard(
    state: PenQuickLogState,
    onQuickLogPen: (Double) -> Unit,
    onChooseCart: () -> Unit,
) {
    when (state) {
        PenQuickLogState.Unavailable -> Unit
        PenQuickLogState.NoCartLoaded -> {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(PenQuickLogTestTags.CARD),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text("Which cart is in the battery?", style = MaterialTheme.typography.titleMedium)
                    Button(
                        onClick = onChooseCart,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag(PenQuickLogTestTags.CHOOSE_CART),
                    ) {
                        Text("Choose pen cart")
                    }
                }
            }
        }

        is PenQuickLogState.Loaded -> {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(PenQuickLogTestTags.CARD),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(state.product.name, style = MaterialTheme.typography.titleMedium)
                            Text(
                                state.syncedUses?.let {
                                    "${state.product.productStatus.label} · synced ${formatUsageAmount(it)} uses"
                                } ?: "${state.product.productStatus.label} · synced unavailable",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            if (state.pendingUses > 0.0) {
                                Text(
                                    "Pending: +${formatUsageAmount(state.pendingUses)} uses",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                        TextButton(
                            onClick = onChooseCart,
                            modifier = Modifier.testTag(PenQuickLogTestTags.SWAP_CART),
                        ) {
                            Text("Swap cart")
                        }
                    }
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(state.presetUses) { preset ->
                            val position = state.presetUses.indexOf(preset) + 1
                            FilterChip(
                                selected = false,
                                onClick = { onQuickLogPen(preset) },
                                label = {
                                    Text(formatQuantityInInputUnit(preset, state.secondsPerUse))
                                },
                                modifier = Modifier.testTag(
                                    PenQuickLogTestTags.quickLogChip(position),
                                ),
                            )
                        }
                    }
                    Text(
                        "${formatQuantityInInputUnit(1.0, state.secondsPerUse)} = 1 use",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConsumptionScreen(viewModel: CannsheetViewModel) {
    val allProducts by viewModel.allProducts.collectAsStateWithLifecycle()
    val recentProducts by viewModel.recentProducts.collectAsStateWithLifecycle()
    val quantityPresets by viewModel.effectiveQuantityPresets.collectAsStateWithLifecycle()
    val includeUnopened by viewModel.includeUnopened.collectAsStateWithLifecycle()
    val formState by viewModel.consumptionFormState.collectAsStateWithLifecycle()
    val pendingUsesByProduct by viewModel.pendingUsesByProduct.collectAsStateWithLifecycle()
    val penQuickLog by viewModel.penQuickLogState.collectAsStateWithLifecycle()
    val secondsPerUse by viewModel.effectiveSecondsPerUse.collectAsStateWithLifecycle()
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
        pendingUsesByProduct = pendingUsesByProduct,
        onSelectProduct = viewModel::selectConsumptionProduct,
        onQuantityChange = viewModel::updateConsumptionQuantity,
        onIncludeUnopenedChange = viewModel::setIncludeUnopened,
        onLog = viewModel::queueConsumption,
        onLogBorrowed = viewModel::queueBorrowedConsumption,
        onFinishWithoutConsumption = viewModel::queueFinishProduct,
        penQuickLog = penQuickLog,
        secondsPerUse = secondsPerUse,
        onQuickLogPen = viewModel::quickLogPen,
        onChooseLoadedPen = viewModel::setLoadedPenProduct,
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
    pendingUsesByProduct: Map<String, Double> = emptyMap(),
    onSelectProduct: (String) -> Unit,
    onQuantityChange: (String) -> Unit,
    onIncludeUnopenedChange: (Boolean) -> Unit,
    onLog: (String, String, String, Double, Boolean) -> Unit,
    onLogBorrowed: (date: String, time: String, type: String, name: String, uses: Double) -> Unit,
    onFinishWithoutConsumption: (String) -> Unit,
    penQuickLog: PenQuickLogState = PenQuickLogState.Unavailable,
    secondsPerUse: Double? = null,
    onQuickLogPen: (Double) -> Unit = {},
    onChooseLoadedPen: (String) -> Unit = {},
) {
    var showProductPicker by rememberSaveable { mutableStateOf(false) }
    var pickerMode by rememberSaveable { mutableStateOf(ProductPickerMode.LOG_TARGET) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var selectedCategory by rememberSaveable { mutableStateOf<String?>(null) }
    var isFinished by rememberSaveable { mutableStateOf(false) }
    var adjustDateTime by rememberSaveable { mutableStateOf(false) }
    var customDateMillis by rememberSaveable { mutableLongStateOf(currentLocalDateAsPickerMillis()) }
    var customHour by rememberSaveable { mutableIntStateOf(Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) }
    var customMinute by rememberSaveable { mutableIntStateOf(Calendar.getInstance().get(Calendar.MINUTE)) }
    var showDatePicker by rememberSaveable { mutableStateOf(false) }
    var showTimePicker by rememberSaveable { mutableStateOf(false) }
    var showBorrowedProductDialog by rememberSaveable { mutableStateOf(false) }
    var borrowedProductName by rememberSaveable { mutableStateOf("") }
    var borrowedProductType by rememberSaveable { mutableStateOf("") }
    var borrowedProductValidationMessage by rememberSaveable { mutableStateOf<String?>(null) }
    var showFinishWithoutConsumptionConfirmation by rememberSaveable { mutableStateOf(false) }
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
    val penPickerProducts = remember(
        allProducts,
        includeUnopened,
        searchQuery,
    ) {
        filterSelectableProducts(
            products = allProducts,
            includeUnopened = includeUnopened,
            query = searchQuery,
            category = ProductTypes.PEN,
        )
    }
    val pickerProducts = if (pickerMode == ProductPickerMode.LOADED_PEN) {
        penPickerProducts
    } else {
        filteredProducts
    }
    val pickerCategories = if (pickerMode == ProductPickerMode.LOADED_PEN) {
        listOf(ProductTypes.PEN)
    } else {
        categories
    }
    val pickerCategory = if (pickerMode == ProductPickerMode.LOADED_PEN) {
        ProductTypes.PEN
    } else {
        selectedCategory
    }

    if (showProductPicker) {
        ProductPickerSheet(
            products = pickerProducts,
            categories = pickerCategories,
            selectedCategory = pickerCategory,
            searchQuery = searchQuery,
            includeUnopened = includeUnopened,
            onSearchQueryChange = { searchQuery = it },
            onCategoryChange = {
                if (pickerMode == ProductPickerMode.LOG_TARGET) {
                    selectedCategory = it
                }
            },
            onIncludeUnopenedChange = onIncludeUnopenedChange,
            onProductSelected = { product ->
                if (pickerMode == ProductPickerMode.LOADED_PEN) {
                    onChooseLoadedPen(product.id)
                } else {
                    onSelectProduct(product.id)
                }
                validationMessage = null
                showProductPicker = false
                pickerMode = ProductPickerMode.LOG_TARGET
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

    if (showFinishWithoutConsumptionConfirmation && selectedProduct != null) {
        AlertDialog(
            onDismissRequest = { showFinishWithoutConsumptionConfirmation = false },
            title = { Text("Finish ${selectedProduct.name}?") },
            text = {
                Text(
                    "Finishing removes this product from product choices without adding a consumption log.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onFinishWithoutConsumption(selectedProduct.id)
                        isFinished = false
                        showFinishWithoutConsumptionConfirmation = false
                    },
                ) { Text("Finish product") }
            },
            dismissButton = {
                TextButton(onClick = { showFinishWithoutConsumptionConfirmation = false }) {
                    Text("Cancel")
                }
            },
        )
    }

    if (showBorrowedProductDialog) {
        AlertDialog(
            onDismissRequest = { showBorrowedProductDialog = false },
            title = { Text("Log a borrowed product") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "Purchase numbers can remain unknown when logging a borrowed product.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    OutlinedTextField(
                        value = borrowedProductName,
                        onValueChange = {
                            borrowedProductName = it
                            borrowedProductValidationMessage = null
                        },
                        label = { Text("Product name") },
                        singleLine = true,
                        isError = borrowedProductValidationMessage != null && borrowedProductName.isBlank(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = borrowedProductType,
                        onValueChange = {
                            borrowedProductType = it
                            borrowedProductValidationMessage = null
                        },
                        label = { Text("Product type") },
                        singleLine = true,
                        isError = borrowedProductValidationMessage != null && borrowedProductType.isBlank(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    borrowedProductValidationMessage?.let { message ->
                        Text(
                            message,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val quantity = formState.quantityText.toDoubleOrNull()
                        when {
                            borrowedProductName.isBlank() || borrowedProductType.isBlank() -> {
                                borrowedProductValidationMessage =
                                    "Enter both a product name and product type."
                            }
                            quantity == null || !quantity.isFinite() || quantity <= 0.0 -> {
                                borrowedProductValidationMessage = "Enter a positive quantity."
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
                                onLogBorrowed(
                                    submittedAt.date,
                                    submittedAt.time,
                                    borrowedProductType.trim(),
                                    borrowedProductName.trim(),
                                    quantity,
                                )
                                borrowedProductName = ""
                                borrowedProductType = ""
                                borrowedProductValidationMessage = null
                                showBorrowedProductDialog = false
                                isFinished = false
                                adjustDateTime = false
                                validationMessage = null
                            }
                        }
                    },
                ) { Text("Log borrowed product") }
            },
            dismissButton = {
                TextButton(onClick = { showBorrowedProductDialog = false }) { Text("Cancel") }
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

            if (penQuickLog !is PenQuickLogState.Unavailable) {
                item {
                    PenQuickLogCard(
                        state = penQuickLog,
                        onQuickLogPen = onQuickLogPen,
                        onChooseCart = {
                            pickerMode = ProductPickerMode.LOADED_PEN
                            showProductPicker = true
                        },
                    )
                }
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
                                    pendingUses = pendingUsesByProduct[recent.product.id] ?: 0.0,
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
                    pendingUses = selectedProduct?.let { pendingUsesByProduct[it.id] } ?: 0.0,
                    onClick = {
                        pickerMode = ProductPickerMode.LOG_TARGET
                        showProductPicker = true
                    },
                )
            }

            item {
                OutlinedButton(
                    onClick = {
                        borrowedProductValidationMessage = null
                        showBorrowedProductDialog = true
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Log a borrowed product")
                }
            }

            item {
                QuantitySection(
                    presets = quantityPresets,
                    secondsPerUse = secondsPerUse,
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

            if (selectedProduct?.productStatus?.isSelectable == true) {
                item {
                    OutlinedButton(
                        onClick = { showFinishWithoutConsumptionConfirmation = true },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Finish without logging consumption")
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
    pendingUses: Double,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .width(184.dp)
            .heightIn(min = 132.dp)
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
            Text(
                recent.product.totalUses?.takeIf { it.isFinite() && it >= 0.0 }
                    ?.let { "Synced: ${formatUsageAmount(it)} uses" }
                    ?: "Synced: unavailable",
                style = MaterialTheme.typography.labelMedium,
            )
            if (pendingUses.isFinite() && pendingUses > 0.0) {
                Text(
                    "Pending: +${formatUsageAmount(pendingUses)} uses",
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }
}

@Composable
private fun ProductSelectionCard(product: Product?, pendingUses: Double, onClick: () -> Unit) {
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
                product?.let { selected ->
                    Column(
                        modifier = Modifier.semantics {
                            liveRegion = LiveRegionMode.Polite
                        },
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(
                            selected.totalUses?.takeIf { it.isFinite() && it >= 0.0 }
                                ?.let { "Synced total: ${formatUsageAmount(it)} uses" }
                                ?: "Synced total: unavailable",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        if (pendingUses.isFinite() && pendingUses > 0.0) {
                            Text(
                                "Pending: +${formatUsageAmount(pendingUses)} uses",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
            Icon(Icons.Default.Search, contentDescription = "Search products")
        }
    }
}

@Composable
private fun QuantitySection(
    presets: List<Double>,
    secondsPerUse: Double?,
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
                    label = { Text(formatQuantityInInputUnit(preset, secondsPerUse)) },
                )
            }
        }
        OutlinedTextField(
            value = quantityText,
            onValueChange = onQuantityChange,
            label = {
                Text(if (secondsPerUse == null) "Custom quantity" else "Custom quantity (uses)")
            },
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

internal fun formatUsageAmount(quantity: Double): String {
    require(quantity.isFinite() && quantity >= 0.0) {
        "Usage totals must be finite and nonnegative"
    }
    return BigDecimal.valueOf(if (quantity == -0.0) 0.0 else quantity)
        .setScale(6, RoundingMode.HALF_UP)
        .stripTrailingZeros()
        .toPlainString()
}

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
        .filter {
            category == null || ProductTypes.normalize(it.type) == ProductTypes.normalize(category)
        }
        .filter { product ->
            normalizedQuery.isEmpty() ||
                product.name.contains(normalizedQuery, ignoreCase = true) ||
                product.id.contains(normalizedQuery, ignoreCase = true) ||
                product.type.contains(normalizedQuery, ignoreCase = true)
        }
        .sortedBy { it.name.lowercase() }
        .toList()
}
