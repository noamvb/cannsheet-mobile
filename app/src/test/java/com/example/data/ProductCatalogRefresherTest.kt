package com.example.data

import java.io.IOException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.runBlocking

class ProductCatalogRefresherTest {
    @Test
    fun validResponseUsesExistingProductAndInteractionMapping() = runBlocking {
        val gateway = FakeProductCatalogGateway()
        val refresher = refresher(
            response = """
                {
                  "environment":"PRODUCTION",
                  "products":[{
                    "id":"product-1",
                    "name":"Test",
                    "type":"Flower",
                    "status":1,
                    "lastLoggedAtEpochMillis":1234,
                    "lastQuantity":2.5,
                    "totalUses":7.5
                  }]
                }
            """.trimIndent(),
            gateway = gateway,
        )

        val result = refresher.refresh(ENDPOINT)

        assertEquals(ProductCatalogRefreshResult.Updated, result)
        assertEquals(1, gateway.products.size)
        assertEquals(7.5, gateway.products.single().totalUses)
        assertEquals(ProductInteraction("product-1", 1234, 2.5), gateway.interactions.single())
    }

    @Test
    fun htmlAndEnvironmentMismatchNeverReplaceCatalog() = runBlocking {
        val htmlGateway = FakeProductCatalogGateway()
        assertEquals(
            ProductCatalogRefreshResult.HtmlResponse,
            refresher("<html>bad deployment</html>", htmlGateway).refresh(ENDPOINT),
        )
        assertTrue(htmlGateway.products.isEmpty())

        val mismatchGateway = FakeProductCatalogGateway()
        assertEquals(
            ProductCatalogRefreshResult.EnvironmentMismatch,
            refresher("""{"environment":"SANDBOX","products":[]}""", mismatchGateway).refresh(ENDPOINT),
        )
        assertTrue(mismatchGateway.products.isEmpty())
    }

    @Test
    fun transportFailureIsReturnedWithoutReplacingCatalog() = runBlocking {
        val gateway = FakeProductCatalogGateway()
        val api = object : GasApiService {
            override suspend fun getProducts(url: String): ResponseBody = throw IOException("offline")
            override suspend fun getAnalytics(url: String): ResponseBody = error("unused")
            override suspend fun syncData(url: String, payload: SyncPayload): ResponseBody = error("unused")
        }
        val result = ProductCatalogRefresher(
            api = api,
            moshi = com.squareup.moshi.Moshi.Builder().build(),
            gateway = gateway,
            expectedEnvironment = "PRODUCTION",
        ).refresh(ENDPOINT)

        assertTrue(result is ProductCatalogRefreshResult.Failure)
        assertTrue(gateway.products.isEmpty())
    }

    private fun refresher(response: String, gateway: FakeProductCatalogGateway) =
        ProductCatalogRefresher(
            api = object : GasApiService {
                override suspend fun getProducts(url: String): ResponseBody =
                    response.toResponseBody("application/json".toMediaType())

                override suspend fun getAnalytics(url: String): ResponseBody = error("unused")
                override suspend fun syncData(url: String, payload: SyncPayload): ResponseBody = error("unused")
            },
            moshi = com.squareup.moshi.Moshi.Builder().build(),
            gateway = gateway,
            expectedEnvironment = "PRODUCTION",
        )

    private class FakeProductCatalogGateway : ProductCatalogGateway {
        var products: List<Product> = emptyList()
        var interactions: List<ProductInteraction> = emptyList()

        override suspend fun refreshProducts(
            products: List<Product>,
            remoteInteractions: List<ProductInteraction>,
        ) {
            this.products = products
            interactions = remoteInteractions
        }
    }

    private companion object {
        const val ENDPOINT = "https://example.test/catalog"
    }
}
