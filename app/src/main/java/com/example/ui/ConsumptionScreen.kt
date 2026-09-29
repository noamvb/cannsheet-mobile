package com.example.ui

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.semantics.text
import androidx.compose.ui.semantics.clearAndSetSemantics
import android.text.format.DateFormat
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.Product
import com.example.data.ProductStatus
import com.example.data.productStatus
import com.example.domain.PenQuickLogState
import com.example.domain.ProductRunway
import com.example.domain.formatQuantityInInputUnit
import com.example.ui.theme.PlexMono
import com.example.ui.theme.tabular
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs

private enum class ProductPickerMode {
    LOG_TARGET,
    LOADED_PEN,
}

internal object ConsumptionLedgerTestTags {
    const val LEDGER = "consumption-ledger"
    fun productRow(productId: String) = "consumption-ledger-product-$productId"
    fun remainingQuantity(productId: String) = "consumption-ledger-remaining-$productId"
}

internal object PenQuickLogTestTags {
    const val CARD = "pen-quick-log-card"
    const val CHOOSE_CART = "pen-quick-log-choose-cart"
    const val SWAP_CART = "pen-quick-log-swap-cart"
    const val RUNWAY = "pen-quick-log-runway"

    fun quickLogChip(position: Int) = "pen-quick-log-chip-$position"
}

internal object ConsumptionRunwayTestTags {
    const val SELECTED_PRODUCT_RUNWAY = "consumption-selected-product-runway"
}

@Composable
internal fun PenQuickLogCard(
    state: PenQuickLogState,
    onQuickLogPen: (Double) -> Unit,
    onChooseCart: () -> Unit,
    runwayByProductId: Map<String, ProductRunway> = emptyMap(),
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
                            runwayByProductId[state.product.id]?.let { runway ->
                                Text(
                                    runwaySummaryText(runway),
                                    modifier = Modifier.testTag(PenQuickLogTestTags.RUNWAY),
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
fun ConsumptionScreen(
    viewModel: CannsheetViewModel,
    openCartPickerRequests: Flow<Unit> = emptyFlow(),
) {
    val allProducts by viewModel.allProducts.collectAsStateWithLifecycle()
    val recentProducts by viewModel.recentProducts.collectAsStateWithLifecycle()
    val quantityPresets by viewModel.effectiveQuantityPresets.collectAsStateWithLifecycle()
    val includeUnopened by viewModel.includeUnopened.collectAsStateWithLifecycle()
    val formState by viewModel.consumptionFormState.collectAsStateWithLifecycle()
    val pendingUsesByProduct by viewModel.pendingUsesByProduct.collectAsStateWithLifecycle()
    val penQuickLog by viewModel.penQuickLogState.collectAsStateWithLifecycle()
    val secondsPerUse by viewModel.effectiveSecondsPerUse.collectAsStateWithLifecycle()
    val syncStatus by viewModel.syncStatus.collectAsStateWithLifecycle()
    val runwayPresentation by viewModel.runwayPresentationState.collectAsStateWithLifecycle()
    val runwayByProductId =
        (runwayPresentation.estimates as? RunwayEstimateState.Ready)?.runwayByProductId.orEmpty()
    val snackbarHostState = remember { SnackbarHostState() }

    DisposableEffect(viewModel) {
        viewModel.onRunwayVisible()
        onDispose(viewModel::onRunwayHidden)
    }

    LaunchedEffect(syncStatus) {
        syncStatus?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearSyncStatus()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        ConsumptionContent(
            allProducts = allProducts,
            recentProducts = recentProducts,
            quantityPresets = quantityPresets,
            includeUnopened = includeUnopened,
            formState = formState,
            modifier = Modifier.padding(innerPadding),
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
            runwayByProductId = runwayByProductId,
            openCartPickerRequests = openCartPickerRequests,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConsumptionContent(
    allProducts: List<Product>,
    recentProducts: List<RecentProduct>,
    quantityPresets: List<Double>,
    includeUnopened: Boolean,
    formState: ConsumptionFormState,
    modifier: Modifier = Modifier,
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
    runwayByProductId: Map<String, ProductRunway> = emptyMap(),
    openCartPickerRequests: Flow<Unit> = emptyFlow(),
    nowMillisProvider: () -> Long = System::currentTimeMillis,
) {
    var showProductPicker by rememberSaveable { mutableStateOf(false) }
    var pickerMode by rememberSaveable { mutableStateOf(ProductPickerMode.LOG_TARGET) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var selectedCategory by rememberSaveable { mutableStateOf<String?>(null) }
    var isFinished by rememberSaveable { mutableStateOf(false) }
    var adjustDateTime by rememberSaveable { mutableStateOf(false) }
    val initialCalendar = remember(nowMillisProvider) {
        Calendar.getInstance().apply { timeInMillis = nowMillisProvider() }
    }
    var customDateMillis by rememberSaveable {
        mutableLongStateOf(currentLocalDateAsPickerMillis(initialCalendar.timeInMillis))
    }
    var customHour by rememberSaveable { mutableIntStateOf(initialCalendar.get(Calendar.HOUR_OF_DAY)) }
    var customMinute by rememberSaveable { mutableIntStateOf(initialCalendar.get(Calendar.MINUTE)) }
    var showDatePicker by rememberSaveable { mutableStateOf(false) }
    var showTimePicker by rememberSaveable { mutableStateOf(false) }
    var showBorrowedProductDialog by rememberSaveable { mutableStateOf(false) }
    var borrowedProductName by rememberSaveable { mutableStateOf("") }
    var borrowedProductType by rememberSaveable { mutableStateOf("") }
    var borrowedProductValidationMessage by rememberSaveable { mutableStateOf<String?>(null) }
    var showFinishWithoutConsumptionConfirmation by rememberSaveable { mutableStateOf(false) }
    var validationMessage by rememberSaveable { mutableStateOf<String?>(null) }

    LaunchedEffect(openCartPickerRequests) {
        openCartPickerRequests.collect {
            pickerMode = ProductPickerMode.LOADED_PEN
            showProductPicker = true
        }
    }

    val selectedProduct = remember(allProducts, formState.selectedProductId) {
        allProducts.firstOrNull { it.id == formState.selectedProductId }
    }
    val categories = remember(allProducts) {
        allProducts.map(Product::type).filter(String::isNotBlank).distinct().sorted()
    }
    val selectableProducts = remember(allProducts, includeUnopened) {
        filterSelectableProducts(allProducts, includeUnopened, "", null)
    }
    val ledgerProducts = remember(selectableProducts, recentProducts, formState.selectedProductId) {
        val selected = selectableProducts.firstOrNull { it.id == formState.selectedProductId }
        val fromRecent = recentProducts.map { it.product }.filter { p -> selectableProducts.any { it.id == p.id } }
        val combined = (fromRecent + listOfNotNull(selected) + selectableProducts).distinctBy { it.id }
        combined.take(5)
    }

    val filteredPickerProducts = remember(
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
        filteredPickerProducts
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
                                    currentSubmissionDateTime(nowMillisProvider())
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
        modifier = modifier
            .fillMaxSize()
            .imePadding(),
    ) {
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // Log idle: date line + title
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 4.dp),
                ) {
                    Text(
                        text = formatHeaderDate(nowMillisProvider()),
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = PlexMono).tabular(),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "Log",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "Local record · sync ready",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
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
                        runwayByProductId = runwayByProductId,
                    )
                }
            }

            // Ledger header
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(ConsumptionLedgerTestTags.LEDGER),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 6.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "PRODUCT / TYPE",
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = PlexMono).tabular(),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = "CONFIRMED",
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = PlexMono).tabular(),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.End,
                            modifier = Modifier.width(76.dp),
                        )
                        Text(
                            text = "LOCAL",
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = PlexMono).tabular(),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.End,
                            modifier = Modifier.width(48.dp),
                        )
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }

            // Ledger product items
            if (ledgerProducts.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 16.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "No active products in ledger",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                items(ledgerProducts, key = Product::id) { product ->
                    val isSelected = product.id == formState.selectedProductId
                    val pendingUses = pendingUsesByProduct[product.id] ?: 0.0
                    val runway = runwayByProductId[product.id]

                    LedgerProductRow(
                        product = product,
                        isSelected = isSelected,
                        pendingUses = pendingUses,
                        runway = runway,
                        onClick = {
                            onSelectProduct(product.id)
                            validationMessage = null
                        },
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }

            // Choose other product / Search button
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(
                        onClick = {
                            pickerMode = ProductPickerMode.LOG_TARGET
                            showProductPicker = true
                        },
                    ) {
                        Icon(Icons.Default.Search, contentDescription = null)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Search all products")
                    }
                    TextButton(
                        onClick = {
                            borrowedProductValidationMessage = null
                            showBorrowedProductDialog = true
                        },
                    ) {
                        Text("Log borrowed product")
                    }
                }
            }

            // Quantity section
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

            // Mark product as finished
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { isFinished = !isFinished }
                        .padding(horizontal = 4.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Mark product as finished", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                        Text(
                            "It will no longer appear in product choices.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = isFinished, onCheckedChange = { isFinished = it })
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

            // Date & time row
            item {
                DateTimeSection(
                    adjustDateTime = adjustDateTime,
                    customDateMillis = customDateMillis,
                    customHour = customHour,
                    customMinute = customMinute,
                    nowMillisProvider = nowMillisProvider,
                    onToggleAdjustment = {
                        if (!adjustDateTime) {
                            val now = Calendar.getInstance().apply { timeInMillis = nowMillisProvider() }
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

            // Log consumption action button
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                ) {
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
                                        currentSubmissionDateTime(nowMillisProvider())
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
                            .heightIn(min = 48.dp),
                        shape = MaterialTheme.shapes.small,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                        ),
                    ) {
                        Text("Log consumption", style = MaterialTheme.typography.labelLarge)
                    }
                    Text(
                        text = "A brief undo window starts after saving.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 6.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun LedgerProductRow(
    product: Product,
    isSelected: Boolean,
    pendingUses: Double,
    runway: ProductRunway?,
    onClick: () -> Unit,
) {
    val confirmedTotal = product.totalUses?.takeIf { it.isFinite() && it >= 0.0 }
    val confirmedText = confirmedTotal?.let { formatUsageAmount(it) } ?: "—"
    val localText = if (pendingUses.isFinite() && pendingUses > 0.0) "+${formatUsageAmount(pendingUses)}" else "+0"
    val confirmedSpoken = confirmedTotal?.let { "Synced total: ${formatUsageAmount(it)} uses" } ?: "Synced: unavailable"
    val localSpoken = if (pendingUses.isFinite() && pendingUses > 0.0) "Pending: +${formatUsageAmount(pendingUses)} uses" else null

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (isSelected) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    androidx.compose.ui.graphics.Color.Transparent
                },
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 10.dp)
            .testTag(ConsumptionLedgerTestTags.productRow(product.id)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = product.name,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = "${product.productStatus.label} · ${ProductTypes.label(product.type)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "·",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "${formatQuantity(product.grams)} g remaining",
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = PlexMono).tabular(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag(ConsumptionLedgerTestTags.remainingQuantity(product.id)),
                )
            }
            if (isSelected && runway != null) {
                Text(
                    text = runwaySummaryText(runway),
                    modifier = Modifier.testTag(ConsumptionRunwayTestTags.SELECTED_PRODUCT_RUNWAY),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }

        // Confirmed count
        Text(
            text = confirmedText,
            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = PlexMono).tabular(),
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.End,
            modifier = Modifier
                .width(76.dp)
                .clearAndSetSemantics { text = AnnotatedString(confirmedSpoken) },
        )

        // Local count
        Text(
            text = localText,
            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = PlexMono).tabular(),
            color = if (pendingUses > 0.0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.End,
            modifier = Modifier
                .width(48.dp)
                .clearAndSetSemantics { if (localSpoken != null) text = AnnotatedString(localSpoken) },
        )
    }
}

@Composable
private fun QuantitySection(
    presets: List<Double>,
    secondsPerUse: Double?,
    quantityText: String,
    onQuantityChange: (String) -> Unit,
) {
    val effectivePresets = remember(presets) {
        if (presets.isNotEmpty()) presets else listOf(1.0, 2.0, 3.0)
    }
    val currentQuantity = quantityText.toDoubleOrNull()
    var isCustomMode by rememberSaveable { mutableStateOf(false) }

    val matchingPreset = effectivePresets.firstOrNull { preset ->
        currentQuantity != null && abs(preset - currentQuantity) < 0.0001
    }
    val isCustomSelected = isCustomMode || (matchingPreset == null && quantityText.isNotBlank())

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "QUANTITY",
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = PlexMono).tabular(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = if (secondsPerUse != null) "SECONDS" else "USES",
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = PlexMono).tabular(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Bold,
            )
        }

        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(effectivePresets) { preset ->
                val isSelected = !isCustomSelected && matchingPreset == preset
                val label = if (secondsPerUse != null) {
                    formatQuantityInInputUnit(preset, secondsPerUse)
                } else {
                    formatQuantity(preset)
                }
                FilterChip(
                    selected = isSelected,
                    onClick = {
                        isCustomMode = false
                        onQuantityChange(formatQuantity(preset))
                    },
                    label = {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelMedium.copy(fontFamily = PlexMono).tabular(),
                        )
                    },
                    shape = CircleShape,
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    ),
                )
            }
            item {
                FilterChip(
                    selected = isCustomSelected,
                    onClick = {
                        isCustomMode = true
                    },
                    label = {
                        Text("Custom", style = MaterialTheme.typography.labelMedium)
                    },
                    shape = CircleShape,
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    ),
                )
            }
        }

        AnimatedVisibility(visible = isCustomSelected) {
            OutlinedTextField(
                value = quantityText,
                onValueChange = {
                    isCustomMode = true
                    onQuantityChange(it)
                },
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
}

@Composable
private fun DateTimeSection(
    adjustDateTime: Boolean,
    customDateMillis: Long,
    customHour: Int,
    customMinute: Int,
    nowMillisProvider: () -> Long,
    onToggleAdjustment: () -> Unit,
    onUseNow: () -> Unit,
    onChooseDate: () -> Unit,
    onChooseTime: () -> Unit,
) {
    val nowCalendar = remember(nowMillisProvider, adjustDateTime) {
        Calendar.getInstance().apply { timeInMillis = nowMillisProvider() }
    }
    val displayTime = if (adjustDateTime) {
        "${pickerDateToWire(customDateMillis)} · ${formatTimeAmPm(customHour, customMinute)}"
    } else {
        "Now · ${formatTimeAmPm(nowCalendar.get(Calendar.HOUR_OF_DAY), nowCalendar.get(Calendar.MINUTE))}"
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onToggleAdjustment)
            .padding(vertical = 4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "Date & time",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = displayTime,
                    style = MaterialTheme.typography.bodyMedium.copy(fontFamily = PlexMono).tabular(),
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = if (adjustDateTime) "Done" else "Adjust",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        AnimatedVisibility(visible = adjustDateTime) {
            Column(
                modifier = Modifier.padding(top = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
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
    val isDark = isSystemInDarkTheme()

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
                    val color = ProductTypes.categoryColor(category, isDark)
                    FilterChip(
                        selected = selectedCategory == category,
                        onClick = {
                            onCategoryChange(if (selectedCategory == category) null else category)
                        },
                        label = { Text(category) },
                        colors = FilterChipDefaults.filterChipColors(
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

private fun formatHeaderDate(nowEpochMillis: Long): String =
    SimpleDateFormat("EEEE · d MMM", Locale.US).apply {
        timeZone = TimeZone.getDefault()
    }.format(Date(nowEpochMillis)).uppercase()

private fun formatTimeAmPm(hour: Int, minute: Int): String {
    val h = if (hour == 0) 12 else if (hour > 12) hour - 12 else hour
    val amPm = if (hour < 12) "AM" else "PM"
    return String.format(Locale.US, "%d:%02d %s", h, minute, amPm)
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
