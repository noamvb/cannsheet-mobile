package com.example.ui

import androidx.compose.ui.semantics.text
import androidx.compose.ui.semantics.selected
import android.text.format.DateFormat
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.Product
import com.example.data.ProductStatus
import com.example.data.productStatus
import com.example.domain.PenQuickLogState
import com.example.domain.ProductRunway
import com.example.ui.theme.PlexMono
import com.example.ui.theme.tabular
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Calendar
import java.util.Date

private enum class ProductPickerMode {
    LOG_TARGET,
    LOADED_PEN,
}

internal object ConsumptionLedgerTestTags {
    const val LEDGER = "consumption-ledger"
    fun productRow(productId: String) = "consumption-ledger-product-$productId"
    fun remainingQuantity(productId: String) = "consumption-ledger-remaining-$productId"
}

internal object ConsumptionRunwayTestTags {
    const val SELECTED_PRODUCT_RUNWAY = "consumption-selected-product-runway"
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
        val firstFive = combined.take(5)
        if (selected != null && firstFive.none { it.id == selected.id }) {
            firstFive.dropLast(1) + selected
        } else {
            firstFive
        }
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
                            text = "SYNCED",
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
                        lastQuantity = recentProducts.firstOrNull { it.product.id == product.id }?.lastQuantity,
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
                        Text("Log a borrowed product")
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
                        .heightIn(min = 48.dp)
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
