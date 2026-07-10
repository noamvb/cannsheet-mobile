package com.example.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

class CannsheetRepository(private val dao: CannsheetDao) {
    val allProducts: Flow<List<Product>> = dao.getAllProducts()

    val pendingActionCount: Flow<Int> = combine(
        dao.getPendingPurchasesCount(),
        dao.getPendingConsumptionsCount()
    ) { purchases, consumptions ->
        purchases + consumptions
    }

    suspend fun refreshProducts(products: List<Product>) {
        dao.deleteAllProducts()
        dao.insertProducts(products)
    }

    suspend fun addPurchase(action: PurchaseAction) {
        dao.insertPurchase(action)
        // Also add it as a temporary product in the cache so it's immediately available
        val tempProduct = Product(
            id = action.tempId,
            name = action.name,
            type = action.type,
            status = 2
        )
        dao.insertProducts(listOf(tempProduct))
    }

    suspend fun addConsumption(action: ConsumptionAction) {
        dao.insertConsumption(action)
    }

    suspend fun getPendingPurchases() = dao.getPendingPurchases()
    suspend fun clearPendingPurchases() = dao.clearPendingPurchases()

    suspend fun getPendingConsumptions() = dao.getPendingConsumptions()
    suspend fun clearPendingConsumptions() = dao.clearPendingConsumptions()
}
