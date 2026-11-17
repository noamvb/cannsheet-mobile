package com.example.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.data.AnalyticsProductDto
import com.example.data.HistoryEventDto
import com.example.data.HistoryFilters
import com.example.data.MAX_CONSUMPTION_CORRECTION_REASON_LENGTH
import com.example.data.Product
import com.example.domain.ProductRunway
import java.util.Date

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
internal fun ProductAnalyticsSheet(
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
internal fun AdaptivePaneDivider() {
    Box(
        Modifier
            .fillMaxHeight()
            .width(1.dp)
            .background(MaterialTheme.colorScheme.outlineVariant),
    )
}

@Composable
internal fun DetailPlaceholder() {
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
internal fun ProductAnalyticsRow(product: AnalyticsProductDto, onClick: () -> Unit) {
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
