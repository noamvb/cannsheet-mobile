package com.example.data

import com.squareup.moshi.Moshi
import kotlinx.coroutines.CancellationException

interface ProductCatalogGateway {
    suspend fun refreshProducts(
        products: List<Product>,
        remoteInteractions: List<ProductInteraction> = emptyList(),
    )
}

sealed interface ProductCatalogRefreshResult {
    data object Updated : ProductCatalogRefreshResult

    data object HtmlResponse : ProductCatalogRefreshResult

    data object NullResponse : ProductCatalogRefreshResult

    data object EnvironmentMismatch : ProductCatalogRefreshResult

    data class Failure(val error: Exception) : ProductCatalogRefreshResult
}

class ProductCatalogRefresher(
    private val api: GasApiService,
    private val moshi: Moshi,
    private val gateway: ProductCatalogGateway,
    private val expectedEnvironment: String,
    private val recordTaxRate: suspend (Double?) -> Unit = {},
) {
    suspend fun refresh(endpoint: String): ProductCatalogRefreshResult {
        return try {
            val rawResponse = api.getProducts(endpoint).string()
            if (rawResponse.trimStart().startsWith("<")) {
                return ProductCatalogRefreshResult.HtmlResponse
            }

            val response = moshi.adapter(GasProductResponse::class.java)
                .lenient()
                .fromJson(rawResponse)
                ?: return ProductCatalogRefreshResult.NullResponse
            if (!environmentMatches(expectedEnvironment, response.environment)) {
                return ProductCatalogRefreshResult.EnvironmentMismatch
            }

            // The rate is an auxiliary preference for a preview, so a DataStore
            // failure must not stop an otherwise valid catalog response from
            // reaching Room. It still runs only after the environment check, so a
            // rejected response can never move the stored rate.
            try {
                recordTaxRate(response.taxRate)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                // Deliberately ignored: the catalog refresh is the caller's contract.
            }
            val products = response.products.map(GasProduct::toProductEntity)
            val remoteInteractions = response.products.mapNotNull { product ->
                val timestamp = product.lastLoggedAtEpochMillis
                val quantity = product.lastQuantity
                if (timestamp != null && quantity != null && quantity.isFinite() && quantity > 0.0) {
                    ProductInteraction(product.id, timestamp, quantity)
                } else {
                    null
                }
            }
            gateway.refreshProducts(products, remoteInteractions)
            ProductCatalogRefreshResult.Updated
        } catch (error: Exception) {
            ProductCatalogRefreshResult.Failure(error)
        }
    }
}
