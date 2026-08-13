package com.example.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.AnalyticsProductDto
import com.example.data.DailyActivityDto
import com.example.data.HistoryEventDto
import com.example.data.HistoryFilters
import com.example.data.InsightsRange
import com.example.data.InsightsResponseDto
import com.example.data.MAX_CONSUMPTION_CORRECTION_REASON_LENGTH
import com.example.data.Product
import com.example.data.QualityWarningsDto
import com.example.domain.ProductRunway
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Calendar
import java.util.TimeZone

@Composable
fun InsightsScreen(
    viewModel: CannsheetViewModel,
    windowWidth: WindowWidth = WindowWidth.COMPACT,
) {
    val runwayPresentation by viewModel.runwayPresentationState.collectAsStateWithLifecycle()
    val insights = runwayPresentation.insights
    val history by viewModel.historyState.collectAsStateWithLifecycle()
    val pendingCount = runwayPresentation.pendingActionCount ?: 0
    val isSyncing by viewModel.isSyncing.collectAsStateWithLifecycle()
    val products by viewModel.allProducts.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }

    DisposableEffect(viewModel) {
        viewModel.onInsightsVisible()
        onDispose(viewModel::onInsightsHidden)
    }
    LaunchedEffect(tab) {
        if (tab == 1) viewModel.onHistoryVisible() else viewModel.onOverviewVisible()
    }

    Column(Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Overview") })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("History") })
        }
        if (tab == 0) {
            InsightsContent(
                state = insights,
                pendingCount = pendingCount,
                isSyncing = isSyncing,
                onSync = viewModel::syncQueue,
                onRefresh = viewModel::refreshInsights,
                runwayState = runwayPresentation.estimates,
                windowWidth = windowWidth,
            )
        } else {
            HistoryContent(
                state = history,
                products = products,
                pendingCount = pendingCount,
                isSyncing = isSyncing,
                onSync = viewModel::syncQueue,
                onRefresh = viewModel::refreshHistory,
                onLoadMore = viewModel::loadMoreHistory,
                onRefreshIfNotCurrent = viewModel::refreshHistoryIfNotCurrent,
                correctionState = viewModel.historyCorrectionState.collectAsStateWithLifecycle().value,
                onQueueCorrection = viewModel::queueHistoryCorrection,
                onCancelCorrection = viewModel::cancelHistoryCorrection,
                windowWidth = windowWidth,
            )
        }
    }
}

@Composable
internal fun InsightsContent(
    state: InsightsUiState,
    pendingCount: Int,
    isSyncing: Boolean,
    onSync: () -> Unit,
    onRefresh: (InsightsRange) -> Unit,
    runwayState: RunwayEstimateState =
        RunwayEstimateState.Suppressed(RunwaySuppressionReason.NO_SNAPSHOT),
    windowWidth: WindowWidth = WindowWidth.COMPACT,
) {
    var showCustom by rememberSaveable { mutableStateOf(false) }
    var selectedProductId by rememberSaveable { mutableStateOf<String?>(null) }
    var showAllProducts by rememberSaveable { mutableStateOf(false) }
    var productQuery by rememberSaveable { mutableStateOf("") }
    var productSort by rememberSaveable { mutableStateOf("Most logged") }
    if (showCustom) {
        CustomRangeDialog(
            onDismiss = { showCustom = false },
            onApply = {
                showCustom = false
                onRefresh(it)
            },
        )
    }
    val data = state.data
    val selectedProduct = selectedProductId?.let { id ->
        data?.products?.firstOrNull { it.productId == id }
    }
    if (windowWidth != WindowWidth.EXPANDED) selectedProduct?.let { product ->
        ProductAnalyticsSheet(
            product = product,
            onDismiss = { selectedProductId = null },
            runway = (runwayState as? RunwayEstimateState.Ready)
                ?.runwayByProductId
                ?.get(product.productId),
        )
    }

    if (data == null && state.isInitialLoading) {
        LoadingAnalytics("Loading synced analytics…")
        return
    }
    if (data == null) {
        AnalyticsErrorState(state.error, onRetry = { onRefresh(state.displayedRange) })
        return
    }

    Row(Modifier.fillMaxSize()) {
        Box(
            modifier = if (windowWidth == WindowWidth.EXPANDED) {
                Modifier
                    .weight(0.4f)
                    .fillMaxHeight()
                    .testTag(AdaptiveInsightsTestTags.INSIGHTS_LIST_PANE)
            } else {
                Modifier.fillMaxSize()
            },
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .testTag(InsightsRunwayTestTags.CONTENT),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column {
                    Text("Insights", style = MaterialTheme.typography.headlineMedium)
                    Text(
                        "${data.range.from} – ${data.range.to} · ${analyticsTimeZoneLabel(data.timeZone)} time",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                IconButton(onClick = { onRefresh(state.displayedRange) }) {
                    Icon(Icons.Default.Refresh, contentDescription = "Refresh Insights")
                }
            }
            if (state.isRefreshing) CircularProgressIndicator(Modifier.size(22.dp))
            SnapshotNotice(state.isFromCache, state.isStale, state.lastUpdatedEpochMillis, state.error)
        }
        item {
            RangeChips(
                selected = state.pendingRange ?: state.displayedRange,
                anchor = data.range.to,
                onSelect = onRefresh,
                onCustom = { showCustom = true },
            )
        }
        if (pendingCount > 0) {
            item { PendingBanner(pendingCount, isSyncing, onSync) }
        }
        if (!data.dataQuality.complete) {
            item {
                NoticeCard(
                    "Some totals use incomplete source data",
                    "Open Data notes below for the affected fields.",
                )
            }
        }
        item {
            MetricGrid(data)
        }
        item {
            SectionCard("Activity") {
                val buckets = bucketActivity(data.dailyActivity)
                NativeBarChart(buckets, "activity")
                val streaks = calculateStreaks(data.dailyActivity)
                Text(
                    "Current streak: ${streaks.first} days · Longest: ${streaks.second} days",
                    style = MaterialTheme.typography.bodyMedium,
                )
                val average = if (data.overview.activeDayCount == 0) 0.0
                else data.overview.logCount.toDouble() / data.overview.activeDayCount
                Text("Average ${formatDecimal(average)} logs per active day")
            }
        }
        item {
            SectionCard("Patterns") {
                val weekdays = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
                NativeBarChart(
                    data.byWeekday.sortedBy { it.isoDay }.map {
                        weekdays[it.isoDay - 1] to it.logCount
                    },
                    "weekday activity",
                )
                Spacer(Modifier.height(8.dp))
                HourHeatmap(data.byHour.associate { it.hour to it.logCount })
            }
        }
        item {
            SectionCard("Spending (CAD)") {
                Text("Selected range", style = MaterialTheme.typography.labelLarge)
                Text(
                    "${cad(data.spending.range.personalSpendCents)} personal spending",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text("${cad(data.spending.range.borrowedRecordedValueCents)} borrowed recorded value")
                Text(
                    "All time: ${cad(data.spending.allTime.personalSpendCents)} personal",
                    style = MaterialTheme.typography.bodySmall,
                )
                (runwayState as? RunwayEstimateState.Ready)?.spendRunRate?.let { runRate ->
                    Text(
                        spendRunRateText(runRate),
                        modifier = Modifier.testTag(InsightsRunwayTestTags.SPEND_PROJECTION),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                NativeBarChart(
                    data.spending.byMonth.map {
                        formatChartMonth(it.month) to
                            it.personalSpendCents.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                    },
                    "monthly personal spending in cents",
                    valueLabel = { cad(it.toLong()) },
                    minimumCellWidth = 64.dp,
                )
            }
        }
        item {
            SectionCard("Inventory") {
                Text("${data.inventory.activeCount} active · ${data.inventory.unopenedCount} unopened")
                Text("${data.inventory.finishedCount} finished")
                Text("${cad(data.inventory.currentPersonalOriginalCostCents)} current personal original cost")
                Text("${cad(data.inventory.currentBorrowedRecordedValueCents)} current borrowed recorded value")
                if (data.inventory.unknownCurrentCostCount > 0) {
                    Text("${data.inventory.unknownCurrentCostCount} current costs unknown")
                }
            }
        }
        item {
            RunwaySection(
                estimates = runwayState,
                products = data.products,
                pendingActionCount = pendingCount,
            )
        }
        if (data.byType.isNotEmpty()) {
            item {
                SectionCard("Uses by type") {
                    NativeBarChart(
                        data.byType.map { it.type to it.rangeLogCount },
                        "uses by product type",
                        valueLabel = { "${formatWholeNumber(it)} uses" },
                        minimumCellWidth = 64.dp,
                    )
                }
            }
        }
        item {
            val productComparator = when (productSort) {
                "Most recent" -> compareByDescending<AnalyticsProductDto> {
                    it.allTime.lastLogAtEpochMillis ?: Long.MIN_VALUE
                }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
                "Lowest cost/log" -> compareBy<AnalyticsProductDto> {
                    it.costPerLogToDateCents == null
                }.thenBy { it.costPerLogToDateCents ?: Long.MAX_VALUE }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
                else -> compareByDescending<AnalyticsProductDto> { it.range.logCount }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
                    .thenBy { it.productId }
            }
            val sorted = data.products
                .filter {
                    productQuery.isBlank() ||
                        it.name.contains(productQuery, ignoreCase = true) ||
                        it.productId.contains(productQuery, ignoreCase = true)
                }
                .sortedWith(productComparator)
            SectionCard("Products") {
                if (showAllProducts) {
                    OutlinedTextField(
                        value = productQuery,
                        onValueChange = { productQuery = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Search products") },
                        singleLine = true,
                    )
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        listOf("Most logged", "Most recent", "Lowest cost/log").forEach { option ->
                            FilterChip(
                                selected = productSort == option,
                                onClick = { productSort = option },
                                label = { Text(option) },
                            )
                        }
                    }
                }
                (if (showAllProducts) sorted else sorted.take(5)).forEach { product ->
                    ProductAnalyticsRow(product, onClick = { selectedProductId = product.productId })
                    HorizontalDivider()
                }
                if (sorted.size > 5) {
                    TextButton(onClick = { showAllProducts = !showAllProducts }) {
                        Text(if (showAllProducts) "Show top five" else "See all products")
                    }
                }
            }
        }
        val eligible = data.products
            .filter { it.completedValueComparisonEligible && it.costPerLogToDateCents != null }
            .sortedBy { it.costPerLogToDateCents }
        if (eligible.size >= 2) {
            item {
                SectionCard("Completed product value") {
                    eligible.take(10).forEach {
                        Text("${it.name}: ${cad(requireNotNull(it.costPerLogToDateCents))} per log")
                    }
                }
            }
        }
        item {
            SectionCard("Data notes") {
                QualityNotes(data.dataQuality.warnings)
                Spacer(Modifier.height(8.dp))
                Text("Sync health: server acknowledgements only", fontWeight = FontWeight.SemiBold)
                Text("${data.syncHealth.acknowledgedRequestCount30d} acknowledged requests in 30 days")
                Text("${data.syncHealth.partialRequestCount30d} partial requests")
                Text(
                    "Source: ${data.sourceRevision.eventRowCount} events, " +
                        "${data.sourceRevision.purchaseRowCount} purchases",
                )
                Text("Server duration: ${data.serverDurationMs} ms", style = MaterialTheme.typography.bodySmall)
            }
            }
        }
        }
        if (windowWidth == WindowWidth.EXPANDED) {
            AdaptivePaneDivider()
            Box(
                modifier = Modifier
                    .weight(0.6f)
                    .fillMaxHeight()
                    .testTag(AdaptiveInsightsTestTags.INSIGHTS_DETAIL_PANE),
            ) {
                if (selectedProduct == null) {
                    DetailPlaceholder()
                } else {
                    ProductAnalyticsDetail(
                        product = selectedProduct,
                        runway = (runwayState as? RunwayEstimateState.Ready)
                            ?.runwayByProductId
                            ?.get(selectedProduct.productId),
                    )
                }
            }
        }
    }
}

internal object InsightsRunwayTestTags {
    const val CONTENT = "insights-content"
    const val SECTION = "insights-runway-section"
    const val SHOW_ALL = "insights-runway-show-all"
    const val SPEND_PROJECTION = "insights-spend-projection"
    const val SUPPRESSION_NOTICE = "insights-runway-suppression"
    const val DIAGNOSTIC_PREFIX = "insights-runway-diagnostic-"

    fun row(productId: String): String = "insights-runway-row-$productId"

    fun diagnostic(type: String, position: Int): String = "$DIAGNOSTIC_PREFIX$type-$position"

    fun sheet(productId: String): String = "insights-runway-sheet-$productId"
}

internal object AdaptiveInsightsTestTags {
    const val INSIGHTS_LIST_PANE = "adaptive-insights-list-pane"
    const val INSIGHTS_DETAIL_PANE = "adaptive-insights-detail-pane"
    const val HISTORY_LIST_PANE = "adaptive-history-list-pane"
    const val HISTORY_DETAIL_PANE = "adaptive-history-detail-pane"
    const val DETAIL_PLACEHOLDER = "adaptive-detail-placeholder"
    const val HISTORY_SHEET = "history-event-sheet"
    const val CORRECTION_EDITOR = "history-correction-editor"
    const val CORRECTION_CONFIRMATION = "history-correction-confirmation"
    const val CANCELLATION_CONFIRMATION = "history-correction-cancellation-confirmation"
    const val CORRECTION_QUANTITY = "history-correction-quantity"
    const val CORRECTION_REASON = "history-correction-reason"
}

@Composable
internal fun RunwaySection(
    estimates: RunwayEstimateState,
    products: List<AnalyticsProductDto>,
    pendingActionCount: Int = 0,
) {
    val ready = when (estimates) {
        is RunwayEstimateState.Ready -> estimates
        is RunwayEstimateState.Suppressed -> {
            val notice = runwaySuppressionText(estimates.reason, pendingActionCount)
            if (notice != null) {
                Column(Modifier.testTag(InsightsRunwayTestTags.SECTION)) {
                    SectionCard("Runway") {
                        Text(
                            notice,
                            modifier = Modifier
                                .testTag(InsightsRunwayTestTags.SUPPRESSION_NOTICE),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
            return
        }
    }
    val productsById = remember(products) { products.associateBy(AnalyticsProductDto::productId) }
    val rows = remember(ready.runwayByProductId, productsById) {
        ready.runwayByProductId.values
            .mapNotNull { runway ->
                productsById[runway.productId]?.let { product -> product to runway }
            }
            .sortedWith(
                compareBy<Pair<AnalyticsProductDto, ProductRunway>> { it.second.estimatedDaysRemaining }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { it.first.name }
                    .thenBy { it.first.productId },
            )
    }
    if (rows.isEmpty() && ready.diagnostics.isEmpty()) return

    var showAll by rememberSaveable { mutableStateOf(false) }
    val displayedRows = if (showAll) rows else rows.take(MAX_RUNWAY_ROWS)
    val displayedDiagnostics = if (showAll) {
        ready.diagnostics
    } else {
        ready.diagnostics.take(MAX_RUNWAY_DIAGNOSTICS)
    }
    val hasMoreDetails =
        rows.size > MAX_RUNWAY_ROWS || ready.diagnostics.size > MAX_RUNWAY_DIAGNOSTICS
    Column(Modifier.testTag(InsightsRunwayTestTags.SECTION)) {
        SectionCard("Runway") {
            displayedRows.forEachIndexed { index, row ->
                val (product, runway) = row
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(InsightsRunwayTestTags.row(product.productId)),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(product.name, fontWeight = FontWeight.SemiBold)
                    Text(
                        runwaySummaryText(runway),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (index != displayedRows.lastIndex) {
                    HorizontalDivider()
                }
            }
            displayedDiagnostics.forEachIndexed { position, diagnostic ->
                Text(
                    runwayDiagnosticText(diagnostic),
                    modifier = Modifier.testTag(
                        InsightsRunwayTestTags.diagnostic(diagnostic.type, position),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (hasMoreDetails) {
                TextButton(
                    onClick = { showAll = !showAll },
                    modifier = Modifier.testTag(InsightsRunwayTestTags.SHOW_ALL),
                ) {
                    Text(if (showAll) "Show fewer details" else "See all runway details")
                }
            }
        }
    }
}

@Composable
internal fun HistoryContent(
    state: HistoryUiState,
    products: List<Product>,
    pendingCount: Int,
    isSyncing: Boolean,
    onSync: () -> Unit,
    onRefresh: (HistoryFilters) -> Unit,
    onLoadMore: () -> Unit,
    onRefreshIfNotCurrent: () -> Unit = {},
    correctionState: HistoryCorrectionUiState = HistoryCorrectionUiState(),
    onQueueCorrection: (HistoryCorrectionDraft) -> Unit = {},
    onCancelCorrection: (String) -> Unit = {},
    windowWidth: WindowWidth = WindowWidth.COMPACT,
) {
    var search by rememberSaveable(state.appliedFilters.query) {
        mutableStateOf(state.appliedFilters.query.orEmpty())
    }
    var showFilters by rememberSaveable { mutableStateOf(false) }
    // Hold the identity, not the snapshot. A refresh replaces every HistoryEventDto instance, and
    // the correction draft reads expectedHeadId off whatever copy this sheet was given.
    var selectedEventId by rememberSaveable { mutableStateOf<String?>(null) }
    var missingEntryNotice by rememberSaveable { mutableStateOf(false) }
    val selectedEvent = selectedEventId?.let { id ->
        state.events.firstOrNull { it.eventUuid == id }
    }
    var editorOperationName by rememberSaveable(selectedEventId) { mutableStateOf<String?>(null) }
    var confirmOperationName by rememberSaveable(selectedEventId) { mutableStateOf<String?>(null) }
    var confirmCancellation by rememberSaveable(selectedEventId) { mutableStateOf(false) }
    val selectedAvailability = selectedEvent?.let { selected ->
        historyCorrectionAvailability(state, selected, correctionState.queuedTargetIds)
    }
    val selectedStatus = selectedEvent?.let { selected ->
        correctionState.status.takeIf {
            correctionState.statusTargetEventId == selected.eventUuid
        }
    }
    val isSelectedCorrectionQueued = selectedEvent?.eventUuid?.let {
        it in correctionState.queuedTargetIds
    } == true

    // Opening an entry from a cached or stale page starts the refresh the "Editing unavailable"
    // notice describes. Keyed on the identity so it runs once per opened entry, not per recomposition.
    LaunchedEffect(selectedEventId) {
        if (selectedEventId != null) onRefreshIfNotCurrent()
    }

    // A correction, a void, or a filter change can remove the opened entry from the refreshed page.
    // Keeping the sheet open on a copy that is no longer in history would show values the server
    // no longer agrees with, so close it and say why. Events are only replaced on success, so this
    // cannot fire mid-refresh.
    LaunchedEffect(selectedEventId, selectedEvent, state.isRefreshing, state.isInitialLoading) {
        if (
            selectedEventId != null &&
            selectedEvent == null &&
            !state.isRefreshing &&
            !state.isInitialLoading
        ) {
            selectedEventId = null
            missingEntryNotice = true
        }
    }
    if (missingEntryNotice) {
        AlertDialog(
            onDismissRequest = { missingEntryNotice = false },
            title = { Text("Entry not in refreshed History") },
            text = {
                Text(
                    "This entry is no longer on the refreshed page. It may have been corrected, " +
                        "voided, or excluded by the current filters.",
                )
            },
            confirmButton = {
                TextButton(onClick = { missingEntryNotice = false }) { Text("OK") }
            },
        )
    }
    if (showFilters) {
        HistoryFilterSheet(
            current = state.appliedFilters.copy(query = search),
            products = products,
            onDismiss = { showFilters = false },
            onApply = {
                showFilters = false
                search = it.query.orEmpty()
                onRefresh(it)
            },
        )
    }
    if (windowWidth != WindowWidth.EXPANDED) selectedEvent?.let { selected ->
        HistoryEventSheet(
            event = selected,
            availability = requireNotNull(selectedAvailability),
            status = selectedStatus,
            isCorrectionQueued = isSelectedCorrectionQueued,
            onRefresh = { onRefresh(state.appliedFilters) },
            isRefreshing = state.isRefreshing,
            refreshError = state.error,
            onCorrect = { editorOperationName = HistoryCorrectionOperation.REPLACE.name },
            onVoid = { confirmOperationName = HistoryCorrectionOperation.VOID.name },
            onRestore = { confirmOperationName = HistoryCorrectionOperation.RESTORE.name },
            onCancelPending = { confirmCancellation = true },
            onDismiss = { selectedEventId = null },
        )
    }

    selectedEvent?.let { selected ->
        if (editorOperationName == HistoryCorrectionOperation.REPLACE.name) {
            CorrectionEditorDialog(
                event = selected,
                products = products,
                onDismiss = { editorOperationName = null },
                onQueue = {
                    onQueueCorrection(it)
                    editorOperationName = null
                },
            )
        }
        confirmOperationName
            ?.let { name -> HistoryCorrectionOperation.entries.firstOrNull { it.name == name } }
            ?.let { operation ->
                CorrectionConfirmationDialog(
                    event = selected,
                    operation = operation,
                    onDismiss = { confirmOperationName = null },
                    onQueue = {
                        onQueueCorrection(it)
                        confirmOperationName = null
                    },
                )
            }
        if (confirmCancellation) {
            AlertDialog(
                modifier = Modifier.testTag(AdaptiveInsightsTestTags.CANCELLATION_CONFIRMATION),
                onDismissRequest = { confirmCancellation = false },
                title = { Text("Cancel pending correction?") },
                text = {
                    Text(
                        "This removes the saved local correction. It does not delete the original history entry.",
                    )
                },
                confirmButton = {
                    Button(onClick = {
                        onCancelCorrection(selected.eventUuid)
                        confirmCancellation = false
                    }) { Text("Cancel correction") }
                },
                dismissButton = {
                    TextButton(onClick = { confirmCancellation = false }) { Text("Keep it") }
                },
            )
        }
    }

    Row(Modifier.fillMaxSize()) {
        Column(
            modifier = if (windowWidth == WindowWidth.EXPANDED) {
                Modifier
                    .weight(0.4f)
                    .fillMaxHeight()
                    .testTag(AdaptiveInsightsTestTags.HISTORY_LIST_PANE)
            } else {
                Modifier.fillMaxSize()
            },
        ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = search,
                onValueChange = { if (it.length <= 80) search = it },
                modifier = Modifier.weight(1f),
                label = { Text("Search product") },
                singleLine = true,
                trailingIcon = {
                    IconButton(
                        onClick = { onRefresh(state.appliedFilters.copy(query = search.trim().ifBlank { null })) },
                    ) { Icon(Icons.Default.Search, contentDescription = "Search History") }
                },
            )
            IconButton(onClick = { showFilters = true }) {
                Icon(Icons.Default.FilterList, contentDescription = "History filters")
            }
            IconButton(
                onClick = { onRefresh(state.appliedFilters) },
                enabled = !state.isRefreshing,
            ) {
                Icon(Icons.Default.Refresh, contentDescription = "Refresh History")
            }
        }
        if (state.isRefreshing) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CircularProgressIndicator(Modifier.size(22.dp))
                Text("Refreshing History…", style = MaterialTheme.typography.bodySmall)
            }
        }
        if (pendingCount > 0) {
            Box(Modifier.padding(horizontal = 16.dp)) { PendingBanner(pendingCount, isSyncing, onSync) }
        }
        SnapshotNotice(
            state.isFromCache,
            state.isStale,
            state.generatedAtEpochMillis,
            state.error,
            Modifier.padding(horizontal = 16.dp),
        )
        if (state.events.isEmpty() && state.isInitialLoading) {
            LoadingAnalytics("Loading synced history…")
        } else if (state.events.isEmpty()) {
            AnalyticsErrorState(
                state.error,
                emptyMessage = if (hasHistoryFilters(state.appliedFilters)) {
                    "No entries match these filters."
                } else {
                    "No synced history yet."
                },
                onRetry = { onRefresh(state.appliedFilters) },
            )
        } else {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                items(state.events, key = HistoryEventDto::eventUuid) { event ->
                    HistoryRow(event, onClick = { selectedEventId = event.eventUuid })
                    HorizontalDivider()
                }
                item {
                    Column(
                        Modifier.fillMaxWidth().padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        state.appendError?.let { Text(it.message, color = MaterialTheme.colorScheme.error) }
                        when {
                            state.isLoadingMore -> CircularProgressIndicator()
                            state.hasMore && state.hasFreshCursor ->
                                OutlinedButton(onClick = onLoadMore) { Text("Load more") }
                            state.isFromCache ->
                                Text("Reconnect and refresh before loading more.")
                            else -> Text("End of downloaded history", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
        }
        if (windowWidth == WindowWidth.EXPANDED) {
            AdaptivePaneDivider()
            Box(
                modifier = Modifier
                    .weight(0.6f)
                    .fillMaxHeight()
                    .testTag(AdaptiveInsightsTestTags.HISTORY_DETAIL_PANE),
            ) {
                val selected = selectedEvent
                val availability = selectedAvailability
                if (selected == null || availability == null) {
                    DetailPlaceholder()
                } else {
                    HistoryEventDetail(
                        event = selected,
                        availability = availability,
                        status = selectedStatus,
                        isCorrectionQueued = isSelectedCorrectionQueued,
                        onRefresh = { onRefresh(state.appliedFilters) },
                        isRefreshing = state.isRefreshing,
                        refreshError = state.error,
                        onCorrect = { editorOperationName = HistoryCorrectionOperation.REPLACE.name },
                        onVoid = { confirmOperationName = HistoryCorrectionOperation.VOID.name },
                        onRestore = { confirmOperationName = HistoryCorrectionOperation.RESTORE.name },
                        onCancelPending = { confirmCancellation = true },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }
}

@Composable
private fun MetricGrid(data: InsightsResponseDto) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MetricCard("Logs", data.overview.logCount.toString(), Modifier.weight(1f))
            MetricCard("Active days", data.overview.activeDayCount.toString(), Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MetricCard("Products used", data.overview.distinctProductCount.toString(), Modifier.weight(1f))
            MetricCard(
                "Days since last log",
                data.overview.daysSinceLastLog?.toString() ?: "—",
                Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun MetricCard(label: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier) {
        Column(Modifier.padding(14.dp)) {
            Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(label, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            content()
        }
    }
}

@Composable
private fun NativeBarChart(
    values: List<Pair<String, Int>>,
    description: String,
    valueLabel: (Int) -> String = { formatWholeNumber(it) },
    minimumCellWidth: androidx.compose.ui.unit.Dp = 48.dp,
) {
    if (values.isEmpty()) {
        Text("No data")
        return
    }
    val max = values.maxOf { it.second }.coerceAtLeast(1)
    var selected by remember(values) { mutableStateOf<Pair<String, Int>?>(null) }
    selected?.let { Text("${it.first}: ${valueLabel(it.second)}") }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val chartWidth = maxOf(maxWidth, minimumCellWidth * values.size)
        Box(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            Row(
                Modifier.width(chartWidth).heightIn(min = 130.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.Bottom,
            ) {
                values.forEach { (label, value) ->
                    Column(
                        Modifier
                            .width(minimumCellWidth)
                            .clickable { selected = label to value }
                            .semantics {
                                contentDescription = "$description, $label, ${valueLabel(value)}"
                            },
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            valueLabel(value),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.labelSmall,
                        )
                        Box(
                            Modifier
                                .width(24.dp)
                                .height((20 + 80 * value / max).dp)
                                .clip(RoundedCornerShape(topStart = 5.dp, topEnd = 5.dp))
                                .background(MaterialTheme.colorScheme.primary),
                        )
                        Text(
                            label,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HourHeatmap(values: Map<Int, Int>) {
    val max = values.values.maxOrNull()?.coerceAtLeast(1) ?: 1
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        (0 until 24).chunked(6).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                row.forEach { hour ->
                    val value = values[hour] ?: 0
                    val alpha = 0.15f + (0.85f * value / max)
                    Box(
                        Modifier
                            .weight(1f)
                            .height(42.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = alpha))
                            .semantics { contentDescription = "$hour:00, $value logs" },
                        contentAlignment = Alignment.Center,
                    ) { Text(hour.toString(), style = MaterialTheme.typography.labelSmall) }
                }
            }
        }
        val peak = values.entries
            .sortedWith(compareByDescending<Map.Entry<Int, Int>> { it.value }.thenBy { it.key })
            .firstOrNull()
        Text("Most active hour: ${peak?.key ?: 0}:00 (${peak?.value ?: 0} logs)")
    }
}

@Composable
private fun RangeChips(
    selected: InsightsRange,
    anchor: String,
    onSelect: (InsightsRange) -> Unit,
    onCustom: () -> Unit,
) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = selected is InsightsRange.Custom && selected == customRangeForDays(30, anchor),
            onClick = { onSelect(customRangeForDays(30, anchor)) },
            label = { Text("30 days") },
        )
        FilterChip(
            selected = selected is InsightsRange.Custom && selected == customRangeForDays(90, anchor),
            onClick = { onSelect(customRangeForDays(90, anchor)) },
            label = { Text("90 days") },
        )
        FilterChip(
            selected = selected is InsightsRange.Default,
            onClick = { onSelect(InsightsRange.Default) },
            label = { Text("180 days") },
        )
        FilterChip(
            selected = selected is InsightsRange.All,
            onClick = { onSelect(InsightsRange.All) },
            label = { Text("All") },
        )
        FilterChip(
            selected = selected is InsightsRange.Custom &&
                selected != customRangeForDays(30, anchor) &&
                selected != customRangeForDays(90, anchor),
            onClick = onCustom,
            label = { Text("Custom") },
        )
    }
}

@Composable
private fun CustomRangeDialog(onDismiss: () -> Unit, onApply: (InsightsRange.Custom) -> Unit) {
    var from by rememberSaveable { mutableStateOf("") }
    var to by rememberSaveable { mutableStateOf("") }
    val valid = Regex("""\d{4}-\d{2}-\d{2}""")
    val validSpan = valid.matches(from) && valid.matches(to) &&
        from <= to && dateSpanInclusive(from, to) in 1..3660
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Custom date range") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Use inclusive analytics dates in YYYY-MM-DD format.")
                OutlinedTextField(from, { from = it }, label = { Text("From") }, singleLine = true)
                OutlinedTextField(to, { to = it }, label = { Text("To") }, singleLine = true)
            }
        },
        confirmButton = {
            TextButton(
                enabled = validSpan,
                onClick = { onApply(InsightsRange.Custom(from, to)) },
            ) { Text("Apply") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HistoryFilterSheet(
    current: HistoryFilters,
    products: List<Product>,
    onDismiss: () -> Unit,
    onApply: (HistoryFilters) -> Unit,
) {
    var from by rememberSaveable { mutableStateOf(current.from.orEmpty()) }
    var to by rememberSaveable { mutableStateOf(current.to.orEmpty()) }
    var type by rememberSaveable { mutableStateOf(current.type.orEmpty()) }
    var selectedId by rememberSaveable {
        mutableStateOf(current.productUuid ?: current.productId.orEmpty())
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("History filters", style = MaterialTheme.typography.headlineSmall)
            OutlinedTextField(from, { from = it }, label = { Text("From YYYY-MM-DD") })
            OutlinedTextField(to, { to = it }, label = { Text("To YYYY-MM-DD") })
            OutlinedTextField(type, { type = it }, label = { Text("Product type") })
            Text("Product", style = MaterialTheme.typography.labelLarge)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                AssistChip(onClick = { selectedId = "" }, label = { Text("All") })
                products.forEach { product ->
                    FilterChip(
                        selected = selectedId == (product.productUuid ?: product.id),
                        onClick = { selectedId = product.productUuid ?: product.id },
                        label = { Text(product.name) },
                    )
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(
                    onClick = {
                        from = ""; to = ""; type = ""; selectedId = ""
                    },
                ) { Text("Clear all") }
                Button(
                    onClick = {
                        val product = products.firstOrNull {
                            (it.productUuid ?: it.id) == selectedId
                        }
                        onApply(
                            current.copy(
                                from = from.ifBlank { null },
                                to = to.ifBlank { null },
                                productUuid = product?.productUuid,
                                productId = if (product?.productUuid == null) product?.id else null,
                                type = type.ifBlank { null },
                            ),
                        )
                    },
                ) { Text("Apply") }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProductAnalyticsSheet(
    product: AnalyticsProductDto,
    onDismiss: () -> Unit,
    runway: ProductRunway? = null,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        ProductAnalyticsDetail(
            product = product,
            runway = runway,
            modifier = Modifier
                .fillMaxWidth()
                .testTag(InsightsRunwayTestTags.sheet(product.productId)),
        )
    }
}

@Composable
internal fun ProductAnalyticsDetail(
    product: AnalyticsProductDto,
    runway: ProductRunway? = null,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(product.name, style = MaterialTheme.typography.headlineSmall)
        Text("${product.status} · ${product.type} · ${product.productId}")
        Text("${product.range.logCount} logs in range · ${product.allTime.logCount} all time")
        Text("Last quantity: ${product.allTime.lastQuantity?.let(::formatDecimal) ?: "—"}")
        product.purchaseDate?.let {
            Text("Purchased $it${if (product.purchaseDateSource == "CREATED_AT_FALLBACK") " (estimated)" else ""}")
        }
        product.finalCostCents?.let { Text("Final cost: ${cad(it)}") }
        product.costPerLogToDateCents?.let { Text("Cost per log: ${cad(it)}") }
        product.costPerRecordedUnitToDateCents?.let { Text("Cost per recorded unit: ${cad(it)}") }
        product.grams?.let { Text("Grams: ${formatDecimal(it)}") }
        Text(formatThc(product.thcRaw, product.thcQuality))
        runway?.let { estimate ->
            HorizontalDivider()
            Text("Runway", style = MaterialTheme.typography.titleMedium)
            Text(runwaySummaryText(estimate))
        }
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun AdaptivePaneDivider() {
    Box(
        Modifier
            .fillMaxHeight()
            .width(1.dp)
            .background(MaterialTheme.colorScheme.outlineVariant),
    )
}

@Composable
private fun DetailPlaceholder() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .testTag(AdaptiveInsightsTestTags.DETAIL_PLACEHOLDER),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "Select an entry to see details",
            modifier = Modifier.padding(24.dp),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HistoryEventSheet(
    event: HistoryEventDto,
    availability: HistoryCorrectionAvailability,
    status: String?,
    isCorrectionQueued: Boolean,
    onRefresh: () -> Unit,
    isRefreshing: Boolean,
    refreshError: AnalyticsUiError?,
    onCorrect: () -> Unit,
    onVoid: () -> Unit,
    onRestore: () -> Unit,
    onCancelPending: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        HistoryEventDetail(
            event = event,
            availability = availability,
            status = status,
            isCorrectionQueued = isCorrectionQueued,
            onRefresh = onRefresh,
            isRefreshing = isRefreshing,
            refreshError = refreshError,
            onCorrect = onCorrect,
            onVoid = onVoid,
            onRestore = onRestore,
            onCancelPending = onCancelPending,
            modifier = Modifier
                .fillMaxWidth()
                .testTag(AdaptiveInsightsTestTags.HISTORY_SHEET),
        )
    }
}

@Composable
private fun HistoryEventDetail(
    event: HistoryEventDto,
    availability: HistoryCorrectionAvailability,
    status: String?,
    isCorrectionQueued: Boolean,
    onRefresh: () -> Unit,
    isRefreshing: Boolean,
    refreshError: AnalyticsUiError?,
    onCorrect: () -> Unit,
    onVoid: () -> Unit,
    onRestore: () -> Unit,
    onCancelPending: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(event.productName, style = MaterialTheme.typography.headlineSmall)
        Text("${event.localDate} ${event.localTime} · Toronto time")
        Text("Quantity: ${formatDecimal(event.quantity)}${event.weightCode?.let { " $it" }.orEmpty()}")
        Text("Type: ${event.productType}")
        Text("Source: ${event.source}")
        Text("Finished: ${if (event.finished) "Yes" else "No"}")
        Text("Correction state: ${event.lifecycleState}")
        Text("Revision: ${event.revision}")
        event.correctionHeadId?.let {
            Text("Current revision: $it", style = MaterialTheme.typography.bodySmall)
        }
        Text("Product ID: ${event.productId}", style = MaterialTheme.typography.bodySmall)
        Text("Event UUID: ${event.eventUuid}", style = MaterialTheme.typography.bodySmall)
        availability.reason?.let { reason ->
            NoticeCard("Editing unavailable", reason)
            if (reason.startsWith("Refresh History")) {
                RefreshHistoryAction(isRefreshing, refreshError, onRefresh)
            }
        }
        if (availability.canCorrect || availability.canVoid || availability.canRestore) {
            Text("Change this entry", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (availability.canCorrect) {
                    OutlinedButton(onClick = onCorrect) { Text("Correct") }
                }
                if (availability.canVoid) {
                    OutlinedButton(onClick = onVoid) { Text("Void") }
                }
                if (availability.canRestore) {
                    Button(onClick = onRestore) { Text("Restore") }
                }
            }
        }
        status?.let { message ->
            Text(message, color = MaterialTheme.colorScheme.primary)
            if (message.contains("Refresh History")) {
                RefreshHistoryAction(isRefreshing, refreshError, onRefresh)
            }
        }
        if (isCorrectionQueued) {
            TextButton(onClick = onCancelPending) { Text("Cancel pending change") }
        }
        Spacer(Modifier.height(20.dp))
    }
}

/**
 * The refresh affordance shown inside the History detail sheet. The sheet covers the list's
 * SnapshotNotice, so progress and failure have to be reported here or a reader sees nothing at all.
 */
@Composable
private fun RefreshHistoryAction(
    isRefreshing: Boolean,
    refreshError: AnalyticsUiError?,
    onRefresh: () -> Unit,
) {
    if (isRefreshing) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CircularProgressIndicator(Modifier.size(18.dp))
            Text("Refreshing History…")
        }
    } else {
        TextButton(onClick = onRefresh) { Text("Refresh History") }
    }
    refreshError?.let {
        Text("${it.message} (${it.code})", color = MaterialTheme.colorScheme.error)
    }
}

@Composable
private fun CorrectionEditorDialog(
    event: HistoryEventDto,
    products: List<Product>,
    onDismiss: () -> Unit,
    onQueue: (HistoryCorrectionDraft) -> Unit,
) {
    var date by rememberSaveable(event.eventUuid) { mutableStateOf(event.localDate) }
    var time by rememberSaveable(event.eventUuid) { mutableStateOf(event.localTime.take(5)) }
    var selectedProductId by rememberSaveable(event.eventUuid) { mutableStateOf(event.productId) }
    var quantity by rememberSaveable(event.eventUuid) { mutableStateOf(formatDecimal(event.quantity)) }
    var finished by rememberSaveable(event.eventUuid) { mutableStateOf(event.finished) }
    var reopenProduct by rememberSaveable(event.eventUuid) { mutableStateOf(false) }
    var reason by rememberSaveable(event.eventUuid) { mutableStateOf("") }
    val selectedProduct = products.firstOrNull { it.id == selectedProductId }
    val canReopenProduct = canOfferProductReopen(event, selectedProductId, finished)
    val draft = HistoryCorrectionDraft(
        targetEventId = event.eventUuid,
        expectedHeadId = event.correctionHeadId,
        operation = HistoryCorrectionOperation.REPLACE,
        replacementDate = date.trim(),
        replacementTime = time.trim(),
        replacementProductId = selectedProductId,
        replacementProductUuid = selectedProduct?.productUuid ?: if (selectedProductId == event.productId) {
            event.productUuid
        } else {
            null
        },
        replacementQuantity = quantity.toDoubleOrNull(),
        replacementFinished = finished,
        reopenProduct = canReopenProduct && reopenProduct,
        reason = reason.trim().ifBlank { null },
    )
    AlertDialog(
        modifier = Modifier.testTag(AdaptiveInsightsTestTags.CORRECTION_EDITOR),
        onDismissRequest = onDismiss,
        title = { Text("Correct entry") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("This creates a new correction. The original history entry is kept.")
                OutlinedTextField(date, { date = it }, label = { Text("Date (YYYY-MM-DD)") }, singleLine = true)
                OutlinedTextField(time, { time = it }, label = { Text("Time (HH:MM)") }, singleLine = true)
                OutlinedTextField(
                    value = quantity,
                    onValueChange = { quantity = it },
                    modifier = Modifier.testTag(AdaptiveInsightsTestTags.CORRECTION_QUANTITY),
                    label = { Text("Quantity") },
                    singleLine = true,
                )
                Text("Product", style = MaterialTheme.typography.labelLarge)
                Text("Selected: ${selectedProduct?.name ?: event.productName}", style = MaterialTheme.typography.bodySmall)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    products.filter { it.id == event.productId || !it.productUuid.isNullOrBlank() }.forEach { product ->
                        FilterChip(
                            selected = selectedProductId == product.id,
                            onClick = { selectedProductId = product.id },
                            label = { Text(product.name) },
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = finished, onCheckedChange = { finished = it })
                    Text("Mark product finished")
                }
                if (canReopenProduct) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = reopenProduct, onCheckedChange = { reopenProduct = it })
                        Text("Reopen this product")
                    }
                }
                OutlinedTextField(
                    value = reason,
                    onValueChange = {
                        if (it.length <= MAX_CONSUMPTION_CORRECTION_REASON_LENGTH) reason = it
                    },
                    modifier = Modifier.testTag(AdaptiveInsightsTestTags.CORRECTION_REASON),
                    label = { Text("Reason (optional)") },
                    supportingText = { Text("${reason.length}/$MAX_CONSUMPTION_CORRECTION_REASON_LENGTH") },
                )
            }
        },
        confirmButton = {
            Button(enabled = isCorrectionDraftValid(draft), onClick = { onQueue(draft) }) {
                Text("Save correction")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun CorrectionConfirmationDialog(
    event: HistoryEventDto,
    operation: HistoryCorrectionOperation,
    onDismiss: () -> Unit,
    onQueue: (HistoryCorrectionDraft) -> Unit,
) {
    var reason by rememberSaveable(event.eventUuid, operation.name) { mutableStateOf("") }
    var reopenProduct by rememberSaveable(event.eventUuid, operation.name) { mutableStateOf(false) }
    val label = if (operation == HistoryCorrectionOperation.VOID) "Void entry" else "Restore entry"
    val message = if (operation == HistoryCorrectionOperation.VOID) {
        "Void this entry? It will remain in history as an auditable void, not be deleted."
    } else {
        "Restore this voided entry? This creates a new auditable correction."
    }
    AlertDialog(
        modifier = Modifier.testTag(AdaptiveInsightsTestTags.CORRECTION_CONFIRMATION),
        onDismissRequest = onDismiss,
        title = { Text(label) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(message)
                OutlinedTextField(
                    value = reason,
                    onValueChange = {
                        if (it.length <= MAX_CONSUMPTION_CORRECTION_REASON_LENGTH) reason = it
                    },
                    modifier = Modifier.testTag(AdaptiveInsightsTestTags.CORRECTION_REASON),
                    label = { Text("Reason (optional)") },
                    supportingText = { Text("${reason.length}/$MAX_CONSUMPTION_CORRECTION_REASON_LENGTH") },
                )
                if (operation == HistoryCorrectionOperation.VOID && event.reopenEligible && event.finished) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = reopenProduct, onCheckedChange = { reopenProduct = it })
                        Text("Reopen this product")
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onQueue(
                        HistoryCorrectionDraft(
                            targetEventId = event.eventUuid,
                            expectedHeadId = event.correctionHeadId,
                            operation = operation,
                            reopenProduct = operation == HistoryCorrectionOperation.VOID && reopenProduct,
                            reason = reason.trim().ifBlank { null },
                        ),
                    )
                },
            ) { Text("Confirm ${if (operation == HistoryCorrectionOperation.VOID) "void" else "restore"}") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ProductAnalyticsRow(product: AnalyticsProductDto, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(product.name) },
        supportingContent = { Text("${product.type} · ${product.range.logCount} logs in range") },
        trailingContent = { product.costPerLogToDateCents?.let { Text(cad(it)) } },
        modifier = Modifier.clickable(onClick = onClick),
    )
}

@Composable
private fun HistoryRow(event: HistoryEventDto, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(event.productName) },
        supportingContent = {
            Text(
                "${event.localDate} ${event.localTime.take(5)} · " +
                    "${formatDecimal(event.quantity)}${event.weightCode?.let { " $it" }.orEmpty()} · ${event.productType}",
            )
        },
        trailingContent = { if (event.finished) Text("Finished") },
        modifier = Modifier.clickable(onClick = onClick),
    )
}

@Composable
private fun PendingBanner(count: Int, isSyncing: Boolean, onSync: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Text("$count unsynced action${if (count == 1) "" else "s"} not included")
            TextButton(onClick = onSync, enabled = !isSyncing) {
                Text(if (isSyncing) "Syncing…" else "Sync now")
            }
        }
    }
}

@Composable
private fun SnapshotNotice(
    fromCache: Boolean,
    stale: Boolean,
    updatedAt: Long?,
    error: AnalyticsUiError?,
    modifier: Modifier = Modifier,
) {
    if (!fromCache && !stale && error == null) return
    Column(modifier.padding(vertical = 4.dp)) {
        if (fromCache || stale) {
            Text(
                "Showing saved data${updatedAt?.let { " from ${formatTimestamp(it)}" }.orEmpty()}",
                color = MaterialTheme.colorScheme.tertiary,
            )
        }
        error?.let { Text("${it.message} (${it.code})", color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
private fun NoticeCard(title: String, body: String) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
        Column(Modifier.padding(12.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(body, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun QualityNotes(warnings: QualityWarningsDto) {
    val notes = listOf(
        "Estimated purchase dates" to warnings.estimatedPurchaseDateCount,
        "Unknown purchase dates" to warnings.unknownPurchaseDateCount,
        "Unknown personal costs" to warnings.unknownPersonalCostCount,
        "Unknown borrowed costs" to warnings.unknownBorrowedCostCount,
        "Ambiguous THC values" to warnings.ambiguousThcCount,
        "Invalid THC values" to warnings.invalidThcCount,
        "Invalid gram values" to warnings.invalidGramsCount,
        "Unknown statuses" to warnings.unknownStatusCount,
        "Unknown borrowed flags" to warnings.unknownBorrowedFlagCount,
        "Local date mismatches" to warnings.localDateMismatchCount,
        "Local time mismatches" to warnings.localTimeMismatchCount,
        "Unknown sources" to warnings.unknownSourceCount,
        "Invalid unreferenced purchases" to warnings.invalidUnreferencedPurchaseRowCount,
    )
    val nonzero = notes.filter { it.second > 0 }
    if (nonzero.isEmpty()) Text("No source-data warnings")
    else nonzero.forEach { Text("${it.first}: ${it.second}") }
}

@Composable
private fun LoadingAnalytics(message: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(Modifier.height(12.dp))
            Text(message)
        }
    }
}

@Composable
private fun AnalyticsErrorState(
    error: AnalyticsUiError?,
    emptyMessage: String = "Analytics is unavailable.",
    onRetry: () -> Unit,
) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(error?.message ?: emptyMessage)
            error?.let { Text(it.code, style = MaterialTheme.typography.bodySmall) }
            if (error?.retryable != false) {
                Spacer(Modifier.height(12.dp))
                Button(onClick = onRetry) { Text("Retry") }
            }
        }
    }
}

internal fun bucketActivity(days: List<DailyActivityDto>): List<Pair<String, Int>> {
    if (days.size <= 31) return days.map { it.date.substring(5) to it.logCount }
    if (days.size <= 366) {
        return days.groupBy { mondayFor(it.date) }
            .map { (week, values) -> week.substring(5) to values.sumOf(DailyActivityDto::logCount) }
    }
    return days.groupBy { it.date.substring(0, 7) }
        .map { (month, values) -> month to values.sumOf(DailyActivityDto::logCount) }
}

internal fun calculateStreaks(days: List<DailyActivityDto>): Pair<Int, Int> {
    var longest = 0
    var running = 0
    days.forEach {
        running = if (it.logCount > 0) running + 1 else 0
        longest = maxOf(longest, running)
    }
    var current = 0
    for (day in days.asReversed()) {
        if (day.logCount <= 0) break
        current++
    }
    return current to longest
}

private fun hasHistoryFilters(filters: HistoryFilters) =
    !filters.from.isNullOrBlank() ||
        !filters.to.isNullOrBlank() ||
        !filters.productUuid.isNullOrBlank() ||
        !filters.productId.isNullOrBlank() ||
        !filters.type.isNullOrBlank() ||
        !filters.query.isNullOrBlank()

private fun cad(cents: Long): String = formatCadCents(cents)

private fun analyticsTimeZoneLabel(timeZone: String): String =
    timeZone.substringAfterLast('/').replace('_', ' ').ifBlank { timeZone }

internal fun formatChartMonth(month: String): String =
    if (Regex("""\d{4}-\d{2}""").matches(month)) "${month.takeLast(2)}/${month.take(4)}" else month

internal fun formatWholeNumber(value: Int): String =
    NumberFormat.getIntegerInstance(Locale.CANADA).format(value)

internal fun formatThc(raw: Double?, quality: String): String = when (quality) {
    "RECORDED_PERCENT" -> "THC: ${raw?.let(::formatDecimal) ?: "unknown"}%"
    // Older cached analytics classified normal Sheets percentage values (for example,
    // 0.75 for 75%) as ambiguous. Keep those caches readable after the backend fix.
    "AMBIGUOUS_SCALE" -> if (raw != null && raw > 0.0 && raw <= 1.0) {
        "THC: ${formatDecimal(raw * 100.0)}%"
    } else {
        "THC: needs scale review"
    }
    "INVALID" -> "THC: invalid source value"
    else -> "THC: unknown"
}

private fun formatDecimal(value: Double): String =
    if (value % 1.0 == 0.0) value.toInt().toString()
    else "%.2f".format(Locale.CANADA, value).trimEnd('0').trimEnd('.')

private fun formatTimestamp(epochMillis: Long): String =
    SimpleDateFormat("MMM d, h:mm a", Locale.CANADA).apply {
        timeZone = TimeZone.getTimeZone("America/Toronto")
    }.format(Date(epochMillis))

private fun mondayFor(date: String): String {
    val formatter = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
        isLenient = false
    }
    val calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
        time = requireNotNull(formatter.parse(date))
        val offset = (get(Calendar.DAY_OF_WEEK) + 5) % 7
        add(Calendar.DAY_OF_MONTH, -offset)
    }
    return formatter.format(calendar.time)
}

private const val MAX_RUNWAY_ROWS = 5
private const val MAX_RUNWAY_DIAGNOSTICS = 5

private fun dateSpanInclusive(from: String, to: String): Int = runCatching {
    val formatter = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
        isLenient = false
    }
    val start = requireNotNull(formatter.parse(from)).time
    val end = requireNotNull(formatter.parse(to)).time
    ((end - start) / 86_400_000L).toInt() + 1
}.getOrDefault(0)
