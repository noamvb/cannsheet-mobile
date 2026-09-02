package com.example.data

import com.squareup.moshi.Moshi

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

            recordTaxRate(response.taxRate)
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
