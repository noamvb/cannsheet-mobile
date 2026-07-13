package com.example.ui

import android.app.Application
import com.example.BuildConfig
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import com.example.data.AppDatabase
import com.example.data.CannsheetRepository
import com.example.data.ConsumptionAction
import com.example.data.ConsumptionPreferencesRepository
import com.example.data.GasApiService
import com.example.data.GasProductResponse
import com.example.data.Product
import com.example.data.ProductInteraction
import com.example.data.ProductStatus
import com.example.data.PurchaseAction
import com.example.data.QueuedSyncSnapshot
import com.example.data.SyncConsumption
import com.example.data.SyncPayload
import com.example.data.SyncPurchase
import com.example.data.SyncResponse
import com.example.data.buildAcknowledgementPlan
import com.example.data.productStatus
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.math.BigDecimal
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import java.util.UUID

data class ConsumptionFormState(
    val selectedProductId: String? = null,
    val quantityText: String = "1",
)

data class RecentProduct(
    val product: Product,
    val lastQuantity: Double,
)

class CannsheetViewModel(application: Application) : AndroidViewModel(application) {
    private val db = Room.databaseBuilder(
        application,
        AppDatabase::class.java,
        "cannsheet_db",
    ).addMigrations(
        AppDatabase.MIGRATION_2_3,
        AppDatabase.MIGRATION_3_4,
        AppDatabase.MIGRATION_4_5,
    ).build()

    private val repository = CannsheetRepository(db)
    private val consumptionPreferences = ConsumptionPreferencesRepository(application)

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .addInterceptor(HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC })
        .build()

    private val moshi = com.squareup.moshi.Moshi.Builder().build()

    private val apiService = Retrofit.Builder()
        .baseUrl("https://example.com/")
        .client(client)
        .addConverterFactory(MoshiConverterFactory.create(moshi))
        .build()
        .create(GasApiService::class.java)

    private val _gasUrl = MutableStateFlow(BuildConfig.GAS_URL)
    val gasUrl: StateFlow<String> = _gasUrl

    val allProducts: StateFlow<List<Product>> = repository.allProducts
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val productInteractions: StateFlow<List<ProductInteraction>> = repository.productInteractions
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val quantityPresets: StateFlow<List<Double>> = consumptionPreferences.quantityPresets
        .stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            ConsumptionPreferencesRepository.DEFAULT_QUANTITY_PRESETS,
        )

    val includeUnopened: StateFlow<Boolean> = consumptionPreferences.includeUnopened
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

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

    private val _consumptionFormState = MutableStateFlow(ConsumptionFormState())
    val consumptionFormState: StateFlow<ConsumptionFormState> = _consumptionFormState

    val pendingActionCount: StateFlow<Int> = repository.pendingActionCount
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    private val _syncStatus = MutableStateFlow<String?>(null)
    val syncStatus: StateFlow<String?> = _syncStatus

    private val _submissionTimer = MutableStateFlow(5)
    val submissionTimer: StateFlow<Int> = _submissionTimer

    private val _pendingCountdown = MutableStateFlow(0)
    val pendingCountdown: StateFlow<Int> = _pendingCountdown

    private var countdownJob: Job? = null
    private var pendingPurchaseAction: (() -> Unit)? = null
    private var pendingConsumptionAction: (() -> Unit)? = null
    private val syncMutex = Mutex()

    init {
        fetchProducts()
    }

    fun setSubmissionTimer(seconds: Int) {
        _submissionTimer.value = seconds.coerceIn(0, 5)
    }

    fun updateQuantityPresets(presets: List<Double>) {
        viewModelScope.launch {
            runCatching { consumptionPreferences.setQuantityPresets(presets) }
                .onFailure { _syncStatus.value = it.message ?: "Could not save quantity presets" }
        }
    }

    fun setIncludeUnopened(include: Boolean) {
        viewModelScope.launch {
            consumptionPreferences.setIncludeUnopened(include)
        }
    }

    fun selectConsumptionProduct(productId: String) {
        val rememberedQuantity = productInteractions.value
            .firstOrNull { it.productId == productId }
            ?.lastQuantity
            ?.takeIf { it.isFinite() && it > 0.0 }
        val suggestedQuantity = rememberedQuantity ?: defaultQuantity()
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

    fun fetchProducts() {
        val url = _gasUrl.value
        if (url.isBlank()) {
            _syncStatus.value = "GAS URL not set."
            return
        }
        viewModelScope.launch {
            _syncStatus.value = "Fetching products..."
            try {
                val rawString = apiService.getProducts(url).string()
                if (rawString.trimStart().startsWith("<")) {
                    _syncStatus.value = "Error: HTML received. Ensure URL is correct and deployed for 'Anyone'."
                    return@launch
                }

                val response = moshi.adapter(GasProductResponse::class.java)
                    .lenient()
                    .fromJson(rawString)
                if (response == null) {
                    _syncStatus.value = "Error: Null response"
                    return@launch
                }
                if (response.environment != BuildConfig.APP_ENVIRONMENT) {
                    _syncStatus.value = "Configuration error: server environment does not match this app"
                    return@launch
                }

                val entities = response.products.map { product ->
                    Product(
                        id = product.id,
                        name = product.name,
                        type = product.type,
                        status = product.status,
                        cost = product.cost ?: 0.0,
                        thc = product.thc ?: 0.0,
                        grams = product.grams ?: 0.0,
                        productUuid = product.productUuid,
                    )
                }
                val remoteInteractions = response.products.mapNotNull { product ->
                    val timestamp = product.lastLoggedAtEpochMillis
                    val quantity = product.lastQuantity
                    if (timestamp != null && quantity != null && quantity.isFinite() && quantity > 0.0) {
                        ProductInteraction(product.id, timestamp, quantity)
                    } else {
                        null
                    }
                }
                repository.refreshProducts(entities, remoteInteractions)
                _syncStatus.value = "Products updated"
            } catch (error: Exception) {
                _syncStatus.value = "Error fetching products: ${error.message}"
            }
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
        countdownJob?.cancel()
        pendingConsumptionAction = null
        pendingPurchaseAction = {
            addPurchase(date, type, name, cost, thc, grams, borrowed, postTax)
        }
        startCountdown()
    }

    fun queueConsumption(
        date: String,
        time: String,
        productId: String,
        uses: Double,
        isFinished: Boolean,
    ) {
        countdownJob?.cancel()
        pendingPurchaseAction = null
        pendingConsumptionAction = {
            addConsumption(date, time, productId, uses, isFinished)
        }
        startCountdown()
    }

    private fun startCountdown() {
        countdownJob = viewModelScope.launch {
            for (second in _submissionTimer.value downTo 1) {
                _pendingCountdown.value = second
                delay(1_000)
            }
            _pendingCountdown.value = 0
            pendingPurchaseAction?.invoke()
            pendingConsumptionAction?.invoke()
            pendingPurchaseAction = null
            pendingConsumptionAction = null
        }
    }

    fun cancelPendingAction() {
        countdownJob?.cancel()
        _pendingCountdown.value = 0
        pendingPurchaseAction = null
        pendingConsumptionAction = null
        _syncStatus.value = "Action cancelled"
    }

    fun forceSubmitNow() {
        countdownJob?.cancel()
        _pendingCountdown.value = 0
        pendingPurchaseAction?.invoke()
        pendingConsumptionAction?.invoke()
        pendingPurchaseAction = null
        pendingConsumptionAction = null
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
        viewModelScope.launch {
            val action = PurchaseAction(
                tempId = "temp_${UUID.randomUUID()}",
                actionId = UUID.randomUUID().toString(),
                date = date,
                type = type,
                name = name,
                cost = cost,
                thc = thc,
                grams = grams,
                borrowed = if (borrowed) 1 else 0,
                postTax = postTax,
            )
            repository.addPurchase(action)
            _syncStatus.value = "Purchase saved offline"
            syncQueue()
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
            val action = ConsumptionAction(
                eventId = UUID.randomUUID().toString(),
                date = date,
                time = time,
                productId = productId,
                uses = uses,
                isFinished = isFinished,
                productUuid = allProducts.value.firstOrNull { it.id == productId }?.productUuid,
            )
            repository.addConsumption(action, System.currentTimeMillis())
            if (isFinished && _consumptionFormState.value.selectedProductId == productId) {
                clearConsumptionSelection()
            }
            _syncStatus.value = "Consumption saved offline"
            syncQueue()
        }
    }

    fun syncQueue() {
        val url = _gasUrl.value
        if (url.isBlank()) {
            _syncStatus.value = "GAS URL not set."
            return
        }
        viewModelScope.launch {
            syncMutex.withLock {
                _syncStatus.value = "Syncing..."
                try {
                    val pendingPurchases = repository.getPendingPurchases()
                    val pendingConsumptions = repository.getPendingConsumptions()
                    if (pendingPurchases.isEmpty() && pendingConsumptions.isEmpty()) {
                        _syncStatus.value = "Nothing to sync"
                        return@withLock
                    }

                    // These exact UUIDs are the immutable request snapshot. Anything
                    // queued after this point is intentionally left for the next sync.
                    val snapshot = QueuedSyncSnapshot(
                        purchaseActionIds = pendingPurchases.mapTo(linkedSetOf(), PurchaseAction::actionId),
                        consumptionEventIds = pendingConsumptions.mapTo(linkedSetOf(), ConsumptionAction::eventId),
                        purchaseActionIdByTempId = pendingPurchases.associate { it.tempId to it.actionId },
                    )
                    val requestId = repository.getOrCreateSyncRequestId()
                    val purchases = pendingPurchases.map { action ->
                        SyncPurchase(
                            actionId = action.actionId,
                            tempId = action.tempId,
                            date = action.date,
                            type = action.type,
                            name = action.name,
                            cost = action.cost,
                            thc = action.thc,
                            grams = action.grams,
                            borrowed = action.borrowed,
                            postTax = action.postTax,
                            productUuid = action.productUuid,
                        )
                    }
                    val consumptions = pendingConsumptions.map { action ->
                        SyncConsumption(
                            eventId = action.eventId,
                            date = action.date,
                            time = action.time,
                            productId = action.productId,
                            uses = action.uses,
                            isFinished = action.isFinished,
                            productUuid = action.productUuid,
                        )
                    }

                    val rawString = apiService.syncData(
                        url,
                        SyncPayload(
                            requestId = requestId,
                            environment = BuildConfig.APP_ENVIRONMENT,
                            purchases = purchases,
                            consumptions = consumptions,
                        ),
                    ).string()
                    if (rawString.trimStart().startsWith("<")) {
                        _syncStatus.value = "Error: HTML received. Ensure URL is correct and deployed for 'Anyone'."
                        return@withLock
                    }

                    val response = moshi.adapter(SyncResponse::class.java).lenient().fromJson(rawString)
                    if (response?.success != true) {
                        _syncStatus.value = "Sync failed: ${response?.errorCode ?: response?.message ?: "Unknown error"}"
                        return@withLock
                    }
                    if (response.environment != BuildConfig.APP_ENVIRONMENT) {
                        _syncStatus.value = "Configuration error: sync response environment does not match this app"
                        return@withLock
                    }

                    val plan = buildAcknowledgementPlan(snapshot, response)
                    repository.applyAcknowledgements(plan)
                    val idMappings = plan.purchaseRemaps.associate { it.tempId to it.legacyProductId }
                    val selectedId = _consumptionFormState.value.selectedProductId
                    val remappedId = selectedId?.let(idMappings::get)
                    if (remappedId != null) {
                        _consumptionFormState.update { it.copy(selectedProductId = remappedId) }
                    }

                    _syncStatus.value = when {
                        plan.hasRejections ->
                            "Sync partial: ${plan.rejectedPurchaseCount + plan.rejectedConsumptionCount} item(s) need attention"
                        plan.hasAcknowledgements -> "Sync successful"
                        else -> "Sync completed without acknowledgements"
                    }
                    if (plan.hasAcknowledgements) fetchProducts()
                } catch (error: Exception) {
                    // No queue rows are removed on network, parsing, or request failure.
                    _syncStatus.value = syncFailureStatus(error)
                }
            }
        }
    }

    fun clearSyncStatus() {
        _syncStatus.value = null
    }

    private fun defaultQuantity(): Double =
        quantityPresets.value.firstOrNull { it == 1.0 }
            ?: quantityPresets.value.firstOrNull()
            ?: 1.0

    private fun formatQuantity(quantity: Double): String =
        BigDecimal.valueOf(quantity).stripTrailingZeros().toPlainString()

    private companion object {
        const val RECENT_PRODUCT_LIMIT = 6
    }
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
