package com.example.ui

import android.app.Application
import com.example.BuildConfig
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.BackgroundSyncEvent
import com.example.data.CannsheetGraph
import com.example.data.ConsumptionCorrectionOperation
import com.example.data.ConsumptionAction
import com.example.data.ConsumptionPreferencesRepository
import com.example.data.FinishAction
import com.example.data.HistoryFilters
import com.example.data.InsightsRange
import com.example.data.Product
import com.example.data.ProductInteraction
import com.example.data.ProductCatalogRefreshResult
import com.example.data.ProductStatus
import com.example.data.ProductTypeKey
import com.example.data.PurchaseAction
import com.example.data.PurchaseDefaultsState
import com.example.data.PurchaseSubmission
import com.example.data.PendingConsumptionCorrection
import com.example.data.SyncOutcome
import com.example.data.SyncPreferences
import com.example.data.sync.SyncScheduler
import com.example.data.productStatus
import com.example.domain.PenQuickLogState
import com.example.domain.buildPenQuickLogState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import java.math.BigDecimal
import java.net.SocketTimeoutException
import java.util.UUID

data class ConsumptionFormState(
    val selectedProductId: String? = null,
    val quantityText: String = "1",
)

data class RecentProduct(
    val product: Product,
    val lastQuantity: Double,
)

data class PurchaseFeedback(
    val message: String,
)

class CannsheetViewModel(application: Application) : AndroidViewModel(application) {
    private val graph = CannsheetGraph.get(application)
    private val repository = graph.repository
    private val consumptionPreferences = graph.consumptionPreferences

    private val analyticsCoordinator = AnalyticsCoordinator(
        repository = graph.analyticsRepository,
        scope = viewModelScope,
    )

    val insightsState: StateFlow<InsightsUiState> = analyticsCoordinator.insights
    val historyState: StateFlow<HistoryUiState> = analyticsCoordinator.history

    private val _gasUrl = MutableStateFlow(BuildConfig.GAS_URL)
    val gasUrl: StateFlow<String> = _gasUrl

    val allProducts: StateFlow<List<Product>> = repository.allProducts
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val purchaseDefaultsState: StateFlow<PurchaseDefaultsState> =
        graph.purchaseDefaultsRepository.state.stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            PurchaseDefaultsState.NotLoaded,
        )

    private val _purchaseFeedback = MutableStateFlow<PurchaseFeedback?>(null)
    val purchaseFeedback: StateFlow<PurchaseFeedback?> = _purchaseFeedback

    val productInteractions: StateFlow<List<ProductInteraction>> = repository.productInteractions
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val quantityPresets: StateFlow<List<Double>> = consumptionPreferences.quantityPresets
        .stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            ConsumptionPreferencesRepository.DEFAULT_QUANTITY_PRESETS,
        )

    val quantityPresetOverrides: StateFlow<Map<ProductTypeKey, List<Double>>> =
        consumptionPreferences.quantityPresetOverrides
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    val secondsPerUseOverrides: StateFlow<Map<ProductTypeKey, Double>> =
        consumptionPreferences.secondsPerUseOverrides
            .stateIn(
                viewModelScope,
                SharingStarted.Eagerly,
                ConsumptionPreferencesRepository.DEFAULT_SECONDS_PER_USE_OVERRIDES,
            )

    val loadedPenProductId: StateFlow<String?> = consumptionPreferences.loadedPenProductId
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val includeUnopened: StateFlow<Boolean> = consumptionPreferences.includeUnopened
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val backgroundSyncPreferences: StateFlow<SyncPreferences> = graph.syncPreferences.preferences
        .stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            SyncPreferences(),
        )

    val recentProducts: StateFlow<List<RecentProduct>> = combine(
        allProducts,
        productInteractions,
        includeUnopened,
    ) { products, interactions, shouldIncludeUnopened ->
        buildRecentProducts(
            products = products,
            interactions = interactions,
            includeUnopened = shouldIncludeUnopened,
            limit = RECENT_PRODUCT_LIMIT,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val pendingUsesByProduct: StateFlow<Map<String, Double>> = repository.pendingProductUses
        .map { totals ->
            totals.asSequence()
                .filter { it.pendingUses.isFinite() && it.pendingUses > 0.0 }
                .associate { it.productId to it.pendingUses }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    private data class PenPresetInputs(
        val globalPresets: List<Double>,
        val presetOverrides: Map<ProductTypeKey, List<Double>>,
        val secondsPerUseOverrides: Map<ProductTypeKey, Double>,
    )

    private val penPresetInputs: Flow<PenPresetInputs> = combine(
        quantityPresets,
        quantityPresetOverrides,
        secondsPerUseOverrides,
        ::PenPresetInputs,
    )

    val penQuickLogState: StateFlow<PenQuickLogState> = combine(
        allProducts,
        productInteractions,
        loadedPenProductId,
        pendingUsesByProduct,
        penPresetInputs,
    ) { products, interactions, explicitProductId, pending, inputs ->
        buildPenQuickLogState(
            products = products,
            interactions = interactions,
            explicitProductId = explicitProductId,
            globalPresets = inputs.globalPresets,
            presetOverrides = inputs.presetOverrides,
            secondsPerUseOverrides = inputs.secondsPerUseOverrides,
            pendingUsesByProduct = pending,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, PenQuickLogState.Unavailable)

    private val _consumptionFormState = MutableStateFlow(ConsumptionFormState())
    val consumptionFormState: StateFlow<ConsumptionFormState> = _consumptionFormState

    val productTypeOptions: StateFlow<List<String>> = allProducts
        .map { products -> ProductTypes.options(products.map(Product::type)) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, ProductTypes.options(emptyList()))

    val effectiveQuantityPresets: StateFlow<List<Double>> = combine(
        allProducts,
        consumptionFormState,
        quantityPresets,
        quantityPresetOverrides,
    ) { products, form, globalPresets, overrides ->
        val type = products.firstOrNull { it.id == form.selectedProductId }?.type
        ConsumptionPreferencesRepository.effectiveQuantityPresets(
            globalPresets,
            overrides,
            type,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        ConsumptionPreferencesRepository.DEFAULT_QUANTITY_PRESETS,
    )

    val effectiveSecondsPerUse: StateFlow<Double?> = combine(
        allProducts,
        consumptionFormState,
        secondsPerUseOverrides,
    ) { products, form, overrides ->
        val type = products.firstOrNull { it.id == form.selectedProductId }?.type
        ConsumptionPreferencesRepository.effectiveSecondsPerUse(overrides, type)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val pendingActionCount: StateFlow<Int> = repository.pendingActionCount
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    private val pendingCorrectionTargetIds: StateFlow<Set<String>> = repository.pendingCorrectionTargetEventIds
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    private val _historyCorrectionStatus = MutableStateFlow<HistoryCorrectionFeedback?>(null)
    val historyCorrectionState: StateFlow<HistoryCorrectionUiState> = combine(
        pendingCorrectionTargetIds,
        _historyCorrectionStatus,
    ) { targetIds, feedback ->
        HistoryCorrectionUiState(
            queuedTargetIds = targetIds,
            status = feedback?.message,
            statusTargetEventId = feedback?.targetEventId,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, HistoryCorrectionUiState())

    private val _syncStatus = MutableStateFlow<String?>(null)
    val syncStatus: StateFlow<String?> = _syncStatus

    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing

    private val _submissionTimer = MutableStateFlow(5)
    val submissionTimer: StateFlow<Int> = _submissionTimer

    private val _pendingCountdown = MutableStateFlow(0)
    val pendingCountdown: StateFlow<Int> = _pendingCountdown

    private var countdownJob: Job? = null
    private val pendingSubmission = PendingSubmission()
    private val syncMutex = graph.syncMutex

    init {
        viewModelScope.launch {
            graph.backgroundSyncEvents.collect(::applyBackgroundSyncEvent)
        }
        viewModelScope.launch {
            penQuickLogState
                .drop(1)
                .distinctUntilChanged()
                .collect { graph.widgetRefresher.refreshAll() }
        }
        fetchProducts()
    }

    fun setSubmissionTimer(seconds: Int) {
        _submissionTimer.value = seconds.coerceIn(0, 5)
    }

    suspend fun updateQuantityPresets(presets: List<Double>): Result<Unit> =
        runCatching { consumptionPreferences.setQuantityPresets(presets) }
            .onFailure { _syncStatus.value = it.message ?: "Could not save quantity presets" }

    suspend fun updateQuantityPresetsForType(
        type: String,
        presets: List<Double>,
    ): Result<Unit> = runCatching {
        consumptionPreferences.setQuantityPresetsForType(ProductTypeKey(type), presets)
    }.onFailure { _syncStatus.value = it.message ?: "Could not save quantity presets" }

    fun clearQuantityPresetsForType(type: String) {
        viewModelScope.launch {
            runCatching {
                consumptionPreferences.clearQuantityPresetsForType(ProductTypeKey(type))
            }.onFailure { _syncStatus.value = it.message ?: "Could not reset quantity presets" }
        }
    }

    suspend fun updateSecondsPerUseForType(
        type: String,
        secondsPerUse: Double,
    ): Result<Unit> = runCatching {
        consumptionPreferences.setSecondsPerUseForType(ProductTypeKey(type), secondsPerUse)
    }.onFailure { _syncStatus.value = it.message ?: "Could not save seconds per use" }

    fun clearSecondsPerUseForType(type: String) {
        viewModelScope.launch {
            runCatching {
                consumptionPreferences.clearSecondsPerUseForType(ProductTypeKey(type))
            }.onFailure { _syncStatus.value = it.message ?: "Could not reset seconds per use" }
        }
    }

    fun setIncludeUnopened(include: Boolean) {
        viewModelScope.launch {
            consumptionPreferences.setIncludeUnopened(include)
        }
    }

    fun setBackgroundSyncEnabled(enabled: Boolean) {
        viewModelScope.launch {
            runCatching { graph.syncPreferences.setEnabled(enabled) }
                .onFailure { error ->
                    _syncStatus.value = error.message ?: "Could not update background sync"
                }
        }
    }

    fun selectConsumptionProduct(productId: String) {
        val rememberedQuantity = productInteractions.value
            .firstOrNull { it.productId == productId }
            ?.lastQuantity
            ?.takeIf { it.isFinite() && it > 0.0 }
        val suggestedQuantity = rememberedQuantity ?: defaultQuantity(
            allProducts.value.firstOrNull { it.id == productId }?.type,
        )
        _consumptionFormState.value = ConsumptionFormState(
            selectedProductId = productId,
            quantityText = formatQuantity(suggestedQuantity),
        )
    }

    fun updateConsumptionQuantity(value: String) {
        _consumptionFormState.update { it.copy(quantityText = value) }
    }

    fun clearConsumptionSelection() {
        _consumptionFormState.value = ConsumptionFormState(
            quantityText = formatQuantity(defaultQuantity()),
        )
    }

    fun quickLogPen(uses: Double) {
        val loaded = penQuickLogState.value as? PenQuickLogState.Loaded ?: return
        if (!uses.isFinite() || uses <= 0.0) return
        val at = currentSubmissionDateTime()
        queueConsumption(at.date, at.time, loaded.product.id, uses, isFinished = false)
    }

    fun setLoadedPenProduct(productId: String) {
        viewModelScope.launch {
            runCatching {
                consumptionPreferences.setLoadedPenProductId(productId)
            }.onFailure { _syncStatus.value = it.message ?: "Could not save the loaded pen cart" }
        }
    }

    fun fetchProducts() {
        val url = _gasUrl.value
        if (url.isBlank()) {
            _syncStatus.value = "GAS URL not set."
            return
        }
        viewModelScope.launch {
            _syncStatus.value = "Fetching products..."
            _syncStatus.value = productCatalogStatus(graph.catalogRefresher.refresh(url))
        }
    }

    fun queuePurchase(submission: PurchaseSubmission) {
        queueSubmission {
            submitPurchase(submission)
        }
    }

    fun queuePurchase(
        date: String,
        type: String,
        name: String,
        cost: Double,
        thc: Double,
        grams: Double,
        borrowed: Boolean,
        postTax: Boolean,
    ) {
        queuePurchase(
            PurchaseSubmission(
                date = date,
                type = type,
                name = name,
                cost = cost,
                thc = thc,
                grams = grams,
                borrowed = borrowed,
                postTax = postTax,
                saveAsDefault = false,
            ),
        )
    }

    fun queueConsumption(
        date: String,
        time: String,
        productId: String,
        uses: Double,
        isFinished: Boolean,
    ) {
        queueSubmission {
            addConsumption(date, time, productId, uses, isFinished)
        }
    }

    fun queueBorrowedConsumption(
        date: String,
        time: String,
        type: String,
        name: String,
        uses: Double,
    ) {
        queueSubmission {
            addBorrowedConsumption(
                date = date,
                time = time,
                type = type.trim(),
                name = name.trim(),
                uses = uses,
            )
        }
    }

    fun queueFinishProduct(productId: String) {
        val product = allProducts.value.firstOrNull { it.id == productId }
        if (product == null || !product.productStatus.isSelectable) {
            _syncStatus.value = "This product can no longer be finished"
            return
        }
        val submissionDateTime = currentSubmissionDateTime()
        queueSubmission {
            addFinishProduct(
                date = submissionDateTime.date,
                time = submissionDateTime.time,
                productId = product.id,
                productUuid = product.productUuid,
            )
        }
    }

    private fun queueSubmission(action: () -> Unit) {
        countdownJob?.cancel()
        countdownJob = null
        _pendingCountdown.value = 0
        pendingSubmission.replaceWith(action)?.invoke()
        startCountdown()
    }

    private fun startCountdown() {
        countdownJob = viewModelScope.launch {
            for (second in _submissionTimer.value downTo 1) {
                _pendingCountdown.value = second
                delay(1_000)
            }
            _pendingCountdown.value = 0
            pendingSubmission.take()?.invoke()
        }
    }

    fun cancelPendingAction() {
        countdownJob?.cancel()
        countdownJob = null
        _pendingCountdown.value = 0
        pendingSubmission.take()
        _syncStatus.value = "Action cancelled"
    }

    fun forceSubmitNow() {
        countdownJob?.cancel()
        countdownJob = null
        _pendingCountdown.value = 0
        pendingSubmission.take()?.invoke()
    }

    fun addPurchase(
        date: String,
        type: String,
        name: String,
        cost: Double,
        thc: Double,
        grams: Double,
        borrowed: Boolean,
        postTax: Boolean,
    ) {
        submitPurchase(
            PurchaseSubmission(
                date = date,
                type = type,
                name = name,
                cost = cost,
                thc = thc,
                grams = grams,
                borrowed = borrowed,
                postTax = postTax,
                saveAsDefault = false,
            ),
        )
    }

    private fun submitPurchase(submission: PurchaseSubmission) {
        viewModelScope.launch {
            val action = PurchaseAction(
                tempId = "temp_${UUID.randomUUID()}",
                actionId = UUID.randomUUID().toString(),
                date = submission.date,
                type = submission.type,
                name = submission.name,
                cost = submission.cost,
                thc = submission.thc,
                grams = submission.grams,
                borrowed = if (submission.borrowed) 1 else 0,
                postTax = submission.postTax,
            )
            when (
                val result = persistPurchaseAndDefault(
                    saveAsDefault = submission.saveAsDefault,
                    persistPurchase = { repository.addPurchase(action) },
                    persistDefault = { graph.purchaseDefaultsRepository.saveDefault(submission) },
                )
            ) {
                is PurchasePersistenceResult.PurchaseFailed -> {
                    _purchaseFeedback.value = PurchaseFeedback("Purchase could not be saved.")
                    return@launch
                }

                PurchasePersistenceResult.PurchaseSaved -> {
                    _syncStatus.value = "Purchase saved offline"
                }

                PurchasePersistenceResult.PurchaseAndDefaultSaved -> {
                    _purchaseFeedback.value =
                        PurchaseFeedback("Purchase saved and future defaults updated.")
                    _syncStatus.value = "Purchase saved offline"
                }

                is PurchasePersistenceResult.PurchaseSavedDefaultFailed -> {
                    _purchaseFeedback.value = PurchaseFeedback(
                        "Purchase saved, but future defaults could not be updated.",
                    )
                    _syncStatus.value = "Purchase saved offline"
                }
            }
            syncQueue()
            SyncScheduler.enqueueImmediate(getApplication())
        }
    }

    fun addConsumption(
        date: String,
        time: String,
        productId: String,
        uses: Double,
        isFinished: Boolean,
    ) {
        viewModelScope.launch {
            val product = allProducts.value.firstOrNull { it.id == productId }
            graph.consumptionLogger.log(
                date = date,
                time = time,
                productId = productId,
                productUuid = product?.productUuid,
                productType = product?.type,
                uses = uses,
                isFinished = isFinished,
            )
            if (isFinished && _consumptionFormState.value.selectedProductId == productId) {
                clearConsumptionSelection()
            }
            _syncStatus.value = "Consumption saved offline"
            syncQueue()
            SyncScheduler.enqueueImmediate(getApplication())
        }
    }

    private fun addBorrowedConsumption(
        date: String,
        time: String,
        type: String,
        name: String,
        uses: Double,
    ) {
        viewModelScope.launch {
            val tempId = "temp_${UUID.randomUUID()}"
            val purchase = PurchaseAction(
                tempId = tempId,
                actionId = UUID.randomUUID().toString(),
                date = date,
                type = type,
                name = name,
                cost = null,
                thc = null,
                grams = null,
                borrowed = 1,
                postTax = false,
            )
            val consumption = ConsumptionAction(
                eventId = UUID.randomUUID().toString(),
                date = date,
                time = time,
                productId = tempId,
                uses = uses,
                isFinished = false,
            )
            repository.addBorrowedConsumption(
                purchase = purchase,
                consumption = consumption,
                loggedAtEpochMillis = System.currentTimeMillis(),
            )
            _consumptionFormState.value = ConsumptionFormState(
                selectedProductId = tempId,
                quantityText = formatQuantity(uses),
            )
            _syncStatus.value = "Borrowed consumption saved offline"
            syncQueue()
            SyncScheduler.enqueueImmediate(getApplication())
        }
    }

    private fun addFinishProduct(
        date: String,
        time: String,
        productId: String,
        productUuid: String?,
    ) {
        viewModelScope.launch {
            repository.addFinishAction(
                FinishAction(
                    actionId = UUID.randomUUID().toString(),
                    date = date,
                    time = time,
                    productId = productId,
                    productUuid = productUuid,
                ),
            )
            val finishedType = allProducts.value.firstOrNull { it.id == productId }
                ?.type
                ?.let(ProductTypes::normalize)
            if (finishedType == ProductTypes.PEN && loadedPenProductId.value == productId) {
                consumptionPreferences.clearLoadedPenProductId()
            }
            if (_consumptionFormState.value.selectedProductId == productId) {
                clearConsumptionSelection()
            }
            _syncStatus.value = "Product marked finished offline"
            syncQueue()
            SyncScheduler.enqueueImmediate(getApplication())
        }
    }

    fun queueHistoryCorrection(draft: HistoryCorrectionDraft) {
        viewModelScope.launch {
            val event = historyState.value.events.firstOrNull { it.eventUuid == draft.targetEventId }
            if (event == null) {
                updateHistoryCorrectionStatus(draft.targetEventId, "Refresh History before editing this entry.")
                return@launch
            }
            val availability = historyCorrectionAvailability(
                state = historyState.value,
                event = event,
                queuedTargetIds = pendingCorrectionTargetIds.value,
            )
            if (availability.reason != null) {
                updateHistoryCorrectionStatus(draft.targetEventId, availability.reason)
                return@launch
            }
            val operationAllowed = when (draft.operation) {
                HistoryCorrectionOperation.REPLACE -> availability.canCorrect
                HistoryCorrectionOperation.VOID -> availability.canVoid
                HistoryCorrectionOperation.RESTORE -> availability.canRestore
            }
            if (!operationAllowed) {
                updateHistoryCorrectionStatus(draft.targetEventId, "Refresh History before making this change.")
                return@launch
            }
            if (draft.expectedHeadId != event.correctionHeadId) {
                updateHistoryCorrectionStatus(
                    draft.targetEventId,
                    "This entry changed while you were editing it. Reopen it from refreshed History before trying again.",
                )
                return@launch
            }
            if (!isCorrectionDraftValid(draft)) {
                updateHistoryCorrectionStatus(draft.targetEventId, "Check the correction details before saving it.")
                return@launch
            }
            if (repository.getPendingConsumptionCorrection(draft.targetEventId) != null) {
                updateHistoryCorrectionStatus(draft.targetEventId,
                    "A correction for this entry is already queued. Wait for it to sync before making another change."
                )
                return@launch
            }

            val action = PendingConsumptionCorrection(
                targetEventId = draft.targetEventId,
                actionId = UUID.randomUUID().toString(),
                expectedCorrectionHeadId = draft.expectedHeadId.orEmpty(),
                operation = when (draft.operation) {
                    HistoryCorrectionOperation.REPLACE -> ConsumptionCorrectionOperation.REPLACE
                    HistoryCorrectionOperation.VOID -> ConsumptionCorrectionOperation.VOID
                    HistoryCorrectionOperation.RESTORE -> ConsumptionCorrectionOperation.RESTORE
                },
                reopenProduct = draft.reopenProduct,
                reason = draft.reason,
                replacementDate = draft.replacementDate,
                replacementTime = draft.replacementTime,
                replacementProductUuid = draft.replacementProductUuid,
                replacementProductId = draft.replacementProductId,
                replacementUses = draft.replacementQuantity,
                replacementWeightCode = if (draft.operation == HistoryCorrectionOperation.REPLACE) {
                    event.weightCode.orEmpty()
                } else {
                    null
                },
                replacementFinished = draft.replacementFinished,
            )
            val enqueueFailure = runCatching { repository.enqueueConsumptionCorrection(action) }.exceptionOrNull()
            if (enqueueFailure != null) {
                updateHistoryCorrectionStatus(
                    draft.targetEventId,
                    "A correction for this entry is already queued. Wait for it to sync before making another change.",
                )
                return@launch
            }
            updateHistoryCorrectionStatus(draft.targetEventId,
                "Correction saved on this phone. It will sync when a connection is available."
            )
            syncQueue()
            SyncScheduler.enqueueImmediate(getApplication())
        }
    }

    private fun updateHistoryCorrectionStatus(targetEventId: String, message: String) {
        _historyCorrectionStatus.value = HistoryCorrectionFeedback(targetEventId, message)
    }

    fun cancelHistoryCorrection(targetEventId: String) {
        viewModelScope.launch {
            syncMutex.withLock {
                if (repository.cancelPendingConsumptionCorrection(targetEventId)) {
                    updateHistoryCorrectionStatus(
                        targetEventId,
                        "Pending correction cancelled on this phone. The original history entry was not changed.",
                    )
                } else {
                    updateHistoryCorrectionStatus(
                        targetEventId,
                        "This correction is no longer pending. Refresh History to see its latest state.",
                    )
                }
            }
        }
    }

    fun syncQueue() {
        val url = _gasUrl.value
        if (url.isBlank()) {
            _syncStatus.value = "GAS URL not set."
            return
        }
        viewModelScope.launch {
            graph.syncEngine.sync(
                endpoint = url,
                onLockAcquired = {
                    _isSyncing.value = true
                    _syncStatus.value = "Syncing..."
                },
                onOutcome = ::applySyncOutcome,
                onLockReleasing = { _isSyncing.value = false },
            )
        }
    }

    private fun applySyncOutcome(outcome: SyncOutcome) {
        when (outcome) {
            is SyncOutcome.Applied -> {
                applyPurchaseRemaps(outcome)
                _syncStatus.value = syncStatusMessage(outcome)
                applyCorrectionFeedback(outcome)
                if (outcome.plan.hasAcknowledgements) {
                    analyticsCoordinator.markStale()
                    if (!outcome.plan.finishCapabilityMissing) {
                        fetchProducts()
                    }
                }
            }

            is SyncOutcome.Failed -> {
                _syncStatus.value = syncStatusMessage(outcome)
                outcome.pendingCorrectionsAtSnapshot.firstOrNull()?.let { correction ->
                    updateHistoryCorrectionStatus(
                        correction.targetEventId,
                        correctionRejectionMessage(outcome.errorCode.orEmpty(), outcome.message) +
                            " Your saved correction is still pending.",
                    )
                }
            }

            is SyncOutcome.TransportError -> {
                _syncStatus.value = syncStatusMessage(outcome)
                // Preserve the existing behavior: transport feedback reads the
                // live StateFlow, rather than the engine's queue snapshot.
                pendingCorrectionTargetIds.value.firstOrNull()?.let { targetEventId ->
                    updateHistoryCorrectionStatus(
                        targetEventId,
                        "Could not reach the server. Your saved correction is still pending and will retry safely.",
                    )
                }
            }

            else -> _syncStatus.value = syncStatusMessage(outcome)
        }
    }

    private fun applyBackgroundSyncEvent(event: BackgroundSyncEvent) {
        applyPurchaseRemaps(event.outcome)
        applyCorrectionFeedback(event.outcome)
        if (event.outcome.plan.hasAcknowledgements) {
            analyticsCoordinator.markStale()
        }
    }

    private fun applyPurchaseRemaps(outcome: SyncOutcome.Applied) {
        val idMappings = outcome.plan.purchaseRemaps.associate { it.tempId to it.legacyProductId }
        val selectedId = _consumptionFormState.value.selectedProductId
        val remappedId = selectedId?.let(idMappings::get)
        if (remappedId != null) {
            _consumptionFormState.update { it.copy(selectedProductId = remappedId) }
        }
    }

    private fun applyCorrectionFeedback(outcome: SyncOutcome.Applied) {
        val pendingCorrections = outcome.pendingCorrectionsAtSnapshot
        outcome.plan.rejectedConsumptionCorrections.firstOrNull()?.let { rejection ->
            val targetEventId = rejection.targetEventId ?: pendingCorrections.firstOrNull {
                it.actionId == rejection.actionId
            }?.targetEventId
            if (targetEventId != null) {
                updateHistoryCorrectionStatus(
                    targetEventId,
                    correctionRejectionMessage(rejection.errorCode, rejection.message) +
                        " Your saved correction is still pending.",
                )
            }
        }
        if (outcome.plan.correctionCapabilityMissing) {
            pendingCorrections.firstOrNull()?.targetEventId?.let { targetEventId ->
                updateHistoryCorrectionStatus(
                    targetEventId,
                    "History corrections are not enabled on this server yet. Your saved correction is still pending.",
                )
            }
        }
        outcome.plan.acknowledgedConsumptionCorrections.firstOrNull()?.let { acknowledgement ->
            updateHistoryCorrectionStatus(
                acknowledgement.targetEventId,
                "Correction accepted by the server. History is refreshing.",
            )
        }
    }

    fun onInsightsVisible() = analyticsCoordinator.onVisible()

    fun onInsightsHidden() = analyticsCoordinator.onHidden()

    fun onHistoryVisible() = analyticsCoordinator.onHistoryVisible()

    fun onOverviewVisible() = analyticsCoordinator.onOverviewVisible()

    fun refreshInsights(range: InsightsRange = insightsState.value.displayedRange) =
        analyticsCoordinator.refreshInsights(range)

    fun refreshHistory(filters: HistoryFilters = historyState.value.appliedFilters) =
        analyticsCoordinator.refreshHistory(filters)

    fun refreshHistoryIfNotCurrent() = analyticsCoordinator.refreshHistoryIfNotCurrent()

    fun loadMoreHistory() = analyticsCoordinator.loadMoreHistory()

    fun clearSyncStatus() {
        _syncStatus.value = null
    }

    fun clearPurchaseFeedback() {
        _purchaseFeedback.value = null
    }

    private fun defaultQuantity(productType: String? = null): Double {
        val presets = ConsumptionPreferencesRepository.effectiveQuantityPresets(
            globalPresets = quantityPresets.value,
            overrides = quantityPresetOverrides.value,
            productType = productType,
        )
        return presets.firstOrNull { it == 1.0 }
            ?: presets.firstOrNull()
            ?: 1.0
    }

    private fun formatQuantity(quantity: Double): String =
        BigDecimal.valueOf(quantity).stripTrailingZeros().toPlainString()

    private companion object {
        const val RECENT_PRODUCT_LIMIT = 6
    }
}

internal fun syncStatusMessage(outcome: SyncOutcome): String = when (outcome) {
    is SyncOutcome.NothingToSync -> "Nothing to sync"
    is SyncOutcome.HtmlResponse ->
        "Error: HTML received. Ensure URL is correct and deployed for 'Anyone'."
    is SyncOutcome.EnvironmentMismatch ->
        "Configuration error: sync response environment does not match this app"
    is SyncOutcome.RequestIdMismatch ->
        "Sync response could not be matched safely. Your entries are still pending."
    is SyncOutcome.Failed ->
        "Sync failed: ${outcome.errorCode ?: outcome.message ?: "Unknown error"}"
    is SyncOutcome.TransportError -> syncFailureStatus(outcome.error)
    is SyncOutcome.Applied -> when {
        outcome.plan.correctionCapabilityMissing ->
            "Sync failed: backend update required for history corrections"
        outcome.plan.finishCapabilityMissing ->
            "Sync failed: backend update required for finish actions"
        outcome.plan.hasRejections ->
            "Sync partial: ${
                outcome.plan.rejectedPurchaseCount +
                    outcome.plan.rejectedConsumptionCount +
                    outcome.plan.rejectedFinishActionCount +
                    outcome.plan.rejectedConsumptionCorrections.size
            } item(s) need attention"
        outcome.plan.hasAcknowledgements -> "Sync successful"
        else -> "Sync completed without acknowledgements"
    }
}

internal fun productCatalogStatus(result: ProductCatalogRefreshResult): String = when (result) {
    ProductCatalogRefreshResult.Updated -> "Products updated"
    ProductCatalogRefreshResult.HtmlResponse ->
        "Error: HTML received. Ensure URL is correct and deployed for 'Anyone'."
    ProductCatalogRefreshResult.NullResponse -> "Error: Null response"
    ProductCatalogRefreshResult.EnvironmentMismatch ->
        "Configuration error: server environment does not match this app"
    is ProductCatalogRefreshResult.Failure -> "Error fetching products: ${result.error.message}"
}

internal fun syncFailureStatus(error: Exception): String =
    if (error is SocketTimeoutException) {
        "Server confirmation is taking longer than expected. Your entry is still pending and will retry safely."
    } else {
        "Sync error: ${error.message ?: "Unknown error"}"
    }

internal fun buildRecentProducts(
    products: List<Product>,
    interactions: List<ProductInteraction>,
    includeUnopened: Boolean,
    limit: Int = 6,
): List<RecentProduct> {
    val productsById = products.associateBy(Product::id)
    return interactions.asSequence()
        .sortedByDescending(ProductInteraction::lastLoggedAtEpochMillis)
        .mapNotNull { interaction ->
            val product = productsById[interaction.productId] ?: return@mapNotNull null
            val eligible = when (product.productStatus) {
                ProductStatus.ACTIVE -> true
                ProductStatus.UNOPENED -> includeUnopened
                else -> false
            }
            if (eligible) RecentProduct(product, interaction.lastQuantity) else null
        }
        .take(limit.coerceAtLeast(0))
        .toList()
}
