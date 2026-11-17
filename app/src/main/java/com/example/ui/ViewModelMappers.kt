package com.example.ui

import com.example.data.Product
import com.example.data.ProductInteraction
import com.example.data.ProductCatalogRefreshResult
import com.example.data.ProductStatus
import com.example.data.SyncOutcome
import com.example.data.productStatus
import kotlinx.coroutines.flow.update
import java.net.SocketTimeoutException

internal fun syncStatusMessage(outcome: SyncOutcome): String = when (outcome) {
    is SyncOutcome.NothingToSync -> "Nothing to sync"
    is SyncOutcome.HtmlResponse ->
        "Error: HTML received. Ensure URL is correct and deployed for 'Anyone'."
    is SyncOutcome.EnvironmentMismatch ->
        "Configuration error: sync response environment does not match this app"
    is SyncOutcome.RequestIdMismatch ->
        "Sync response could not be matched safely. Your entries are still pending."
    is SyncOutcome.Failed -> {
        val code = outcome.errorCode?.takeUnless { it.isBlank() }
        val message = outcome.message?.trim()?.takeUnless { it.isEmpty() }
        when {
            code != null && message != null -> "Sync failed: $code - $message"
            code != null -> "Sync failed: $code"
            message != null -> "Sync failed: $message"
            else -> "Sync failed: Unknown error"
        }
    }
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
