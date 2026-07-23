package com.example.data

import androidx.room.withTransaction
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

class CannsheetRepository(private val database: AppDatabase) {
    private val dao = database.cannsheetDao()

    val allProducts: Flow<List<Product>> = dao.getAllProducts()
    val productInteractions: Flow<List<ProductInteraction>> = dao.getAllProductInteractions()

    val pendingActionCount: Flow<Int> = combine(
        dao.getPendingPurchasesCount(),
        dao.getPendingConsumptionsCount(),
        dao.getPendingFinishActionsCount(),
    ) { purchases, consumptions, finishes ->
        purchases + consumptions + finishes
    }

    suspend fun refreshProducts(
        products: List<Product>,
        remoteInteractions: List<ProductInteraction> = emptyList(),
    ) {
        dao.replaceProductsAndMergeInteractions(products, remoteInteractions)
    }

    suspend fun addPurchase(action: PurchaseAction) {
        database.withTransaction {
            dao.insertPurchase(action)
            dao.insertProducts(
                listOf(
                    Product(
                        id = action.tempId,
                        name = action.name,
                        type = action.type,
                        status = ProductStatus.UNOPENED.code,
                        productUuid = action.productUuid,
                    ),
                ),
            )
        }
    }

    suspend fun addConsumption(
        action: ConsumptionAction,
        loggedAtEpochMillis: Long = System.currentTimeMillis(),
    ) {
        dao.recordConsumption(
            action = action,
            interaction = ProductInteraction(
                productId = action.productId,
                lastLoggedAtEpochMillis = loggedAtEpochMillis,
                lastQuantity = action.uses,
            ),
        )
    }

    suspend fun addFinishAction(action: FinishAction) {
        dao.recordFinishAction(action)
    }

    suspend fun getPendingPurchases(): List<PurchaseAction> = dao.getPendingPurchases()

    suspend fun getPendingConsumptions(): List<ConsumptionAction> = dao.getPendingConsumptions()

    suspend fun getPendingFinishActions(): List<FinishAction> = dao.getPendingFinishActions()

    suspend fun getOrCreateSyncRequestId(): String = database.withTransaction {
        dao.getSyncRequestState()?.requestId ?: UUID.randomUUID().toString().also { requestId ->
            dao.upsertSyncRequestState(
                SyncRequestState(
                    requestId = requestId,
                    createdAtEpochMillis = System.currentTimeMillis(),
                ),
            )
        }
    }

    suspend fun applyAcknowledgements(plan: SyncAcknowledgementPlan) {
        database.withTransaction {
            plan.purchaseRemaps.forEach { remap ->
                dao.remapPendingConsumptions(
                    oldProductId = remap.tempId,
                    newProductId = remap.legacyProductId,
                    productUuid = remap.productUuid,
                )
                dao.remapPendingFinishActions(
                    oldProductId = remap.tempId,
                    newProductId = remap.legacyProductId,
                    productUuid = remap.productUuid,
                )
                dao.remapProductInteraction(remap.tempId, remap.legacyProductId)
                dao.deleteProduct(remap.tempId)
            }
            if (plan.acknowledgedPurchaseActionIds.isNotEmpty()) {
                dao.deletePurchasesByActionIds(plan.acknowledgedPurchaseActionIds.toList())
            }
            if (plan.acknowledgedConsumptionEventIds.isNotEmpty()) {
                dao.deleteConsumptionsByEventIds(plan.acknowledgedConsumptionEventIds.toList())
            }
            if (plan.acknowledgedFinishActionIds.isNotEmpty()) {
                dao.deleteFinishActionsByActionIds(plan.acknowledgedFinishActionIds.toList())
            }
            if (
                dao.getPendingPurchasesCountNow() == 0 &&
                dao.getPendingConsumptionsCountNow() == 0 &&
                dao.getPendingFinishActionsCountNow() == 0
            ) {
                dao.clearSyncRequestState()
            }
        }
    }
}
