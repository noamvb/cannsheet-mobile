package com.example.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import com.example.data.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.util.UUID

class CannsheetViewModel(application: Application) : AndroidViewModel(application) {
    private val db = Room.databaseBuilder(
        application,
        AppDatabase::class.java,
        "cannsheet_db"
    ).fallbackToDestructiveMigration().build()

    private val repository = CannsheetRepository(db.cannsheetDao())

    private val client = OkHttpClient.Builder()
        .addInterceptor(HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BODY })
        .build()

    private val moshi = com.squareup.moshi.Moshi.Builder().build()

    private val apiService = Retrofit.Builder()
        .baseUrl("https://example.com/") // Base URL is ignored because we use @Url
        .client(client)
        .addConverterFactory(MoshiConverterFactory.create(moshi))
        .build()
        .create(GasApiService::class.java)

    // Using a fake repository for settings for simplicity, in a real app would use DataStore
    private val _gasUrl = MutableStateFlow("https://script.google.com/macros/s/AKfycbzLYtnvt8-P15dL4vkzsMdLOkjTqD6svjlGOckI3mvJshs4KQIVKcOF2wincPEYOYIP0A/exec")
    val gasUrl: StateFlow<String> = _gasUrl

    val allProducts: StateFlow<List<Product>> = repository.allProducts
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val pendingActionCount: StateFlow<Int> = repository.pendingActionCount
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    private val _syncStatus = MutableStateFlow<String?>(null)
    val syncStatus: StateFlow<String?> = _syncStatus

    private val _showStatus2 = MutableStateFlow(false)
    val showStatus2: StateFlow<Boolean> = _showStatus2

    fun setGasUrl(url: String) {
        _gasUrl.value = url
        // Here we could save to DataStore
    }

    fun toggleShowStatus2() {
        _showStatus2.value = !_showStatus2.value
    }

    fun fetchProducts() {
        val url = _gasUrl.value
        if (url.isEmpty()) {
            _syncStatus.value = "GAS URL not set."
            return
        }
        viewModelScope.launch {
            _syncStatus.value = "Fetching products..."
            try {
                val responseBody = apiService.getProducts(url)
                val rawString = responseBody.string()

                if (rawString.trimStart().startsWith("<")) {
                    _syncStatus.value = "Error: HTML received. Ensure URL is correct and deployed for 'Anyone'."
                    return@launch
                }

                val adapter = moshi.adapter(GasProductResponse::class.java).lenient()
                val response = adapter.fromJson(rawString)

                if (response != null) {
                    val entities = response.products.map { p ->
                        Product(
                            id = p.id,
                            name = p.name,
                            type = p.type,
                            status = p.status,
                            cost = p.cost ?: 0.0,
                            thc = p.thc ?: 0.0,
                            grams = p.grams ?: 0.0
                        )
                    }
                    repository.refreshProducts(entities)
                    _syncStatus.value = "Products updated"
                } else {
                    _syncStatus.value = "Error: Null response"
                }
            } catch (e: Exception) {
                _syncStatus.value = "Error fetching products: ${e.message}"
            }
        }
    }

    private val _submissionTimer = MutableStateFlow(5)
    val submissionTimer: StateFlow<Int> = _submissionTimer

    fun setSubmissionTimer(seconds: Int) {
        _submissionTimer.value = seconds
    }

    private val _pendingCountdown = MutableStateFlow(0)
    val pendingCountdown: StateFlow<Int> = _pendingCountdown

    private var countdownJob: kotlinx.coroutines.Job? = null
    private var pendingPurchaseAction: (() -> Unit)? = null
    private var pendingConsumptionAction: (() -> Unit)? = null

    fun queuePurchase(date: String, type: String, name: String, cost: Double, thc: Double, grams: Double, borrowed: Boolean, postTax: Boolean) {
        countdownJob?.cancel()
        pendingConsumptionAction = null
        pendingPurchaseAction = {
            addPurchase(date, type, name, cost, thc, grams, borrowed, postTax)
        }
        startCountdown()
    }

    fun queueConsumption(date: String, time: String, productId: String, uses: Double, isFinished: Boolean) {
        countdownJob?.cancel()
        pendingPurchaseAction = null
        pendingConsumptionAction = {
            addConsumption(date, time, productId, uses, isFinished)
        }
        startCountdown()
    }

    private fun startCountdown() {
        countdownJob = viewModelScope.launch {
            val totalSeconds = _submissionTimer.value
            for (i in totalSeconds downTo 1) {
                _pendingCountdown.value = i
                kotlinx.coroutines.delay(1000)
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

    fun addPurchase(date: String, type: String, name: String, cost: Double, thc: Double, grams: Double, borrowed: Boolean, postTax: Boolean) {
        viewModelScope.launch {
            val tempId = "temp_${UUID.randomUUID().toString().take(8)}"
            val action = PurchaseAction(
                tempId = tempId,
                date = date,
                type = type,
                name = name,
                cost = cost,
                thc = thc,
                grams = grams,
                borrowed = if (borrowed) 1 else 0,
                postTax = postTax
            )
            repository.addPurchase(action)
            _syncStatus.value = "Purchase saved offline"
            syncQueue()
        }
    }

    fun addConsumption(date: String, time: String, productId: String, uses: Double, isFinished: Boolean) {
        viewModelScope.launch {
            val action = ConsumptionAction(
                date = date,
                time = time,
                productId = productId,
                uses = uses,
                isFinished = isFinished
            )
            repository.addConsumption(action)
            _syncStatus.value = "Consumption saved offline"
            syncQueue()
        }
    }

    fun syncQueue() {
        val url = _gasUrl.value
        if (url.isEmpty()) {
            _syncStatus.value = "GAS URL not set."
            return
        }
        viewModelScope.launch {
            _syncStatus.value = "Syncing..."
            try {
                val purchases = repository.getPendingPurchases().map {
                    SyncPurchase(it.tempId, it.date, it.type, it.name, it.cost, it.thc, it.grams, it.borrowed, it.postTax)
                }
                val consumptions = repository.getPendingConsumptions().map {
                    SyncConsumption(it.date, it.time, it.productId, it.uses, it.isFinished)
                }

                if (purchases.isEmpty() && consumptions.isEmpty()) {
                    _syncStatus.value = "Nothing to sync"
                    return@launch
                }

                val payload = SyncPayload(purchases, consumptions)
                val responseBody = apiService.syncData(url, payload)
                val rawString = responseBody.string()

                if (rawString.trimStart().startsWith("<")) {
                    _syncStatus.value = "Error: HTML received. Ensure URL is correct and deployed for 'Anyone'."
                    return@launch
                }

                val adapter = moshi.adapter(SyncResponse::class.java).lenient()
                val response = adapter.fromJson(rawString)

                if (response?.success == true) {
                    repository.clearPendingPurchases()
                    repository.clearPendingConsumptions()
                    _syncStatus.value = "Sync successful"
                    fetchProducts() // Refresh IDs
                } else {
                    _syncStatus.value = "Sync failed: ${response?.message ?: "Unknown error"}"
                }
            } catch (e: Exception) {
                _syncStatus.value = "Sync error: ${e.message}"
            }
        }
    }

    fun clearSyncStatus() {
        _syncStatus.value = null
    }
}
