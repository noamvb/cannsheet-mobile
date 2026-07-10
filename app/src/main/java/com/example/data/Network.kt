package com.example.data

import com.squareup.moshi.JsonClass
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Url

@JsonClass(generateAdapter = true)
data class GasProductResponse(
    val products: List<GasProduct>
)

@JsonClass(generateAdapter = true)
data class GasProduct(
    val id: String,
    val name: String,
    val type: String,
    val status: Int,
    val cost: Double? = 0.0,
    val thc: Double? = 0.0,
    val grams: Double? = 0.0
)

@JsonClass(generateAdapter = true)
data class SyncPayload(
    val purchases: List<SyncPurchase>,
    val consumptions: List<SyncConsumption>
)

@JsonClass(generateAdapter = true)
data class SyncPurchase(
    val tempId: String,
    val date: String,
    val type: String,
    val name: String,
    val cost: Double,
    val thc: Double,
    val grams: Double,
    val borrowed: Int,
    val postTax: Boolean
)

@JsonClass(generateAdapter = true)
data class SyncConsumption(
    val date: String,
    val time: String,
    val productId: String,
    val uses: Double,
    val isFinished: Boolean
)

@JsonClass(generateAdapter = true)
data class SyncResponse(
    val success: Boolean,
    val message: String?
)

interface GasApiService {
    @GET
    suspend fun getProducts(@Url url: String): okhttp3.ResponseBody

    @POST
    suspend fun syncData(@Url url: String, @Body payload: SyncPayload): okhttp3.ResponseBody
}
