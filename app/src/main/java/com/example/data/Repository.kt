package com.example.data

import androidx.room.withTransaction
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

class CannsheetRepository(private val database: AppDatabase) {
    private val dao = database.cannsheetDao()

    val allProducts: Flow<List<Product>> = dao.getAllProducts()
    val productInteractions: Flow<List<ProductInteraction>> = dao.getAllProductInteractions()
    val pendingProductUses: Flow<List<PendingProductUses>> = dao.getPendingProductUses()
    val pendingConsumptionCorrections: Flow<List<PendingConsumptionCorrection>> =
        dao.getPendingConsumptionCorrectionsFlow()
    val pendingCorrectionTargetEventIds: Flow<Set<String>> =
        pendingConsumptionCorrections.map { corrections ->
            corrections.mapTo(linkedSetOf(), PendingConsumptionCorrection::targetEventId)
        }

    val pendingActionCount: Flow<Int> = combine(
        dao.getPendingPurchasesCount(),
        dao.getPendingConsumptionsCount(),
        dao.getPendingFinishActionsCount(),
        dao.getPendingConsumptionCorrectionsCount(),
    ) { purchases, consumptions, finishes, corrections ->
        purchases + consumptions + finishes + corrections
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

    suspend fun addBorrowedConsumption(
        purchase: PurchaseAction,
        consumption: ConsumptionAction,
        loggedAtEpochMillis: Long = System.currentTimeMillis(),
    ) {
        require(purchase.borrowed == 1) { "Borrowed consumption requires a borrowed purchase" }
        require(consumption.productId == purchase.tempId) {
            "Borrowed purchase and consumption must use the same temporary product ID"
        }

        database.withTransaction {
            dao.insertPurchase(purchase)
            dao.insertProducts(
                listOf(
                    Product(
                        id = purchase.tempId,
                        name = purchase.name,
                        type = purchase.type,
                        status = ProductStatus.UNOPENED.code,
                        productUuid = purchase.productUuid,
                    ),
                ),
            )
            dao.recordConsumption(
                action = consumption,
                interaction = ProductInteraction(
                    productId = consumption.productId,
                    lastLoggedAtEpochMillis = loggedAtEpochMillis,
                    lastQuantity = consumption.uses,
                ),
            )
        }
    }

    suspend fun addFinishAction(action: FinishAction) {
        dao.recordFinishAction(action)
    }

    suspend fun enqueueConsumptionCorrection(action: PendingConsumptionCorrection) {
        action.requireValid()
        dao.insertPendingConsumptionCorrection(action)
    }

    suspend fun getPendingPurchases(): List<PurchaseAction> = dao.getPendingPurchases()

    suspend fun getPendingConsumptions(): List<ConsumptionAction> = dao.getPendingConsumptions()

    suspend fun getPendingFinishActions(): List<FinishAction> = dao.getPendingFinishActions()

    suspend fun getPendingConsumptionCorrections(): List<PendingConsumptionCorrection> =
        dao.getPendingConsumptionCorrections()

    suspend fun getPendingConsumptionCorrection(targetEventId: String): PendingConsumptionCorrection? =
        dao.getPendingConsumptionCorrection(targetEventId)

    suspend fun cancelPendingConsumptionCorrection(targetEventId: String): Boolean =
        dao.cancelPendingConsumptionCorrection(targetEventId) > 0

    suspend fun getOrCreateSyncRequestId(snapshot: QueuedSyncSnapshot): String = database.withTransaction {
        val fingerprint = snapshot.payloadFingerprint()
        val current = dao.getSyncRequestState()
        if (current?.payloadFingerprint == fingerprint) {
            current.requestId
        } else {
            UUID.randomUUID().toString().also { requestId ->
                dao.upsertSyncRequestState(
                    SyncRequestState(
                        requestId = requestId,
                        createdAtEpochMillis = System.currentTimeMillis(),
                        payloadFingerprint = fingerprint,
                    ),
                )
            }
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
            plan.acknowledgedConsumptionCorrections.forEach { acknowledgement ->
                dao.deletePendingConsumptionCorrection(
                    actionId = acknowledgement.actionId,
                    targetEventId = acknowledgement.targetEventId,
                )
            }
            if (plan.requestWasAudited) {
                // A successful server response audited this exact payload. If any items remain
                // after a partial response, their changed payload must use a new request ID.
                // Transport failures never produce a plan, so their exact snapshot keeps its ID.
                dao.clearSyncRequestState()
            }
        }
    }
}

fun PendingConsumptionCorrection.toSyncConsumptionCorrection(): SyncConsumptionCorrection {
    requireValid()
    return SyncConsumptionCorrection(
        actionId = actionId,
        targetEventId = targetEventId,
        expectedCorrectionHeadId = expectedCorrectionHeadId,
        operation = operation,
        reopenProduct = reopenProduct,
        reason = reason,
        replacement = if (operation == ConsumptionCorrectionOperation.REPLACE) {
            SyncConsumptionCorrectionReplacement(
                date = requireNotNull(replacementDate),
                time = requireNotNull(replacementTime),
                productUuid = requireNotNull(replacementProductUuid),
                productId = requireNotNull(replacementProductId),
                uses = requireNotNull(replacementUses),
                weightCode = requireNotNull(replacementWeightCode),
                finished = requireNotNull(replacementFinished),
            )
        } else {
            null
        },
    )
}

private fun PendingConsumptionCorrection.requireValid() {
    require(actionId.isUuid()) { "Correction actionId must be a UUID" }
    require(targetEventId.isUuid()) { "Correction targetEventId must be a UUID" }
    require(expectedCorrectionHeadId.isEmpty() || expectedCorrectionHeadId.isUuid()) {
        "Expected correction head must be empty or a UUID"
    }
    require(reason == null || reason.length <= MAX_CONSUMPTION_CORRECTION_REASON_LENGTH) {
        "Correction reason exceeds $MAX_CONSUMPTION_CORRECTION_REASON_LENGTH characters"
    }

    val replacementValues = listOf(
        replacementDate,
        replacementTime,
        replacementProductUuid,
        replacementProductId,
        replacementUses,
        replacementWeightCode,
        replacementFinished,
    )
    if (operation == ConsumptionCorrectionOperation.REPLACE) {
        require(replacementValues.all { it != null }) {
            "REPLACE correction requires a complete replacement snapshot"
        }
        require(requireNotNull(replacementProductUuid).isUuid()) {
            "Replacement productUuid must be a UUID"
        }
        require(requireNotNull(replacementDate).isNotBlank()) { "Replacement date is required" }
        require(requireNotNull(replacementTime).isNotBlank()) { "Replacement time is required" }
        require(requireNotNull(replacementProductId).isNotBlank()) { "Replacement productId is required" }
        val uses = requireNotNull(replacementUses)
        require(uses.isFinite() && uses > 0) {
            "Replacement uses must be greater than zero"
        }
    } else {
        require(replacementValues.all { it == null }) {
            "$operation correction must not contain replacement values"
        }
    }
}

private fun String.isUuid(): Boolean = runCatching {
    UUID.fromString(this).toString().equals(this, ignoreCase = true)
}.getOrDefault(false)
