package com.example.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Surface
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.AnalyticsProductDto
import com.example.data.InsightsRange
import com.example.data.QualityWarningsDto
import com.example.domain.ProductRunway
import com.example.domain.RunwayPace
import com.example.ui.theme.tabular

@Composable
@OptIn(ExperimentalMaterial3Api::class)
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
        TopAppBar(title = { Text("Insights") })
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
                // The unmasked value: null means the queue depth is unknown, which must
                // suppress the summary just as a non-zero count does.
                pendingActionCount = runwayPresentation.pendingActionCount,
                includeTopBar = false,
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
@OptIn(ExperimentalMaterial3Api::class)
internal fun InsightsContent(
    state: InsightsUiState,
    pendingCount: Int,
    isSyncing: Boolean,
    onSync: () -> Unit,
    onRefresh: (InsightsRange) -> Unit,
    runwayState: RunwayEstimateState =
        RunwayEstimateState.Suppressed(RunwaySuppressionReason.NO_SNAPSHOT),
    windowWidth: WindowWidth = WindowWidth.COMPACT,
    // Distinct from pendingCount: null means the queue depth is unknown, which must
    // suppress the generated summary exactly as a non-zero count does. Defaulted from
    // pendingCount so existing callers and tests are unaffected.
    pendingActionCount: Int? = pendingCount,
    includeTopBar: Boolean = true,
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
    // Called here, above the LazyColumn, so the generation coroutine survives the card
    // being scrolled off-screen and back. LazyColumn disposes an off-screen item's
    // composition entirely; driving this from inside the item would restart generation
    // every time it re-entered the viewport.
    val narrativeState = rememberNarrativeState(state, pendingActionCount)

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
            if (includeTopBar) TopAppBar(title = { Text("Insights") })
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column {
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
                onSelect = onRefresh,
                onCustom = { showCustom = true },
            )
        }
        item {
            InsightNarrativeCard(narrative = narrativeState)
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
            HeadlineFigure(data, state.displayedRange)
        }
        item { MetricGrid(data) }
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
                compareBy<Pair<AnalyticsProductDto, ProductRunway>> {
                    it.second.pace !is RunwayPace.Ready
                }
                    .thenBy {
                        (it.second.pace as? RunwayPace.Ready)?.estimatedDaysRemaining
                            ?: Double.POSITIVE_INFINITY
                    }
                    .thenBy { it.second.estimatedRemainingToTypicalUses }
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
                Row(
                    modifier = Modifier.fillMaxWidth().testTag(InsightsRunwayTestTags.row(product.productId)),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(product.name, fontWeight = FontWeight.SemiBold)
                        Text(runwaySummaryText(runway), style = MaterialTheme.typography.bodySmall)
                    }
                    Text(
                        "${formatRunwayNumber(runway.estimatedRemainingToTypicalUses)} uses",
                        modifier = Modifier.testTag("${InsightsRunwayTestTags.row(product.productId)}-figure"),
                        style = MaterialTheme.typography.bodyMedium.tabular(),
                        textAlign = androidx.compose.ui.text.style.TextAlign.End,
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
internal fun PendingBanner(count: Int, isSyncing: Boolean, onSync: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.medium) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Text("$count unsynced action${if (count == 1) "" else "s"} not included")
            TextButton(onClick = onSync, enabled = !isSyncing) {
                Text(if (isSyncing) "Syncing…" else "Sync now")
            }
        }
    }
}

@Composable
internal fun SnapshotNotice(
    fromCache: Boolean,
    stale: Boolean,
    updatedAt: Long?,
    error: AnalyticsUiError?,
    modifier: Modifier = Modifier,
) {
    val freshness = snapshotFreshnessText(fromCache, stale, updatedAt?.let { formatTimestamp(it) })
    if (freshness == null && error == null) return
    Column(modifier.padding(vertical = 4.dp)) {
        if (freshness != null) {
            if (fromCache || stale) {
                Text(freshness, color = MaterialTheme.colorScheme.tertiary)
            } else {
                // A successful network response can still be a backend cache
                // hit carrying an older generatedAt, so state the age instead
                // of leaving the figures undated.
                Text(
                    freshness,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        error?.let { Text("${it.message} (${it.code})", color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
internal fun NoticeCard(title: String, body: String) {
    Surface(color = MaterialTheme.colorScheme.tertiaryContainer, shape = MaterialTheme.shapes.medium) {
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
internal fun LoadingAnalytics(message: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(Modifier.height(12.dp))
            Text(message)
        }
    }
}

@Composable
internal fun AnalyticsErrorState(
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
