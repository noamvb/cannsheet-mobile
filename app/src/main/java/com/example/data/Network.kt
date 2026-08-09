package com.example.data

import com.squareup.moshi.JsonClass
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Url

const val MAX_CONSUMPTION_CORRECTION_REASON_LENGTH = 200

enum class ConsumptionCorrectionOperation {
    REPLACE,
    VOID,
    RESTORE,
}

@JsonClass(generateAdapter = true)
data class GasProductResponse(
    val products: List<GasProduct>,
    val apiVersion: Int? = null,
    val environment: String? = null,
    val correctionVersion: Int? = null,
    val correctionWritesEnabled: Boolean? = null,
)

@JsonClass(generateAdapter = true)
data class GasProduct(
    val id: String,
    val name: String,
    val type: String,
    val status: Int,
    val cost: Double? = 0.0,
    val thc: Double? = 0.0,
    val grams: Double? = 0.0,
    val productUuid: String? = null,
    val lastLoggedAtEpochMillis: Long? = null,
    val lastQuantity: Double? = null,
    val totalUses: Double? = null,
)

@JsonClass(generateAdapter = true)
data class SyncPayload(
    val apiVersion: Int = 2,
    val requestId: String,
    val environment: String,
    val purchases: List<SyncPurchase>,
    val consumptions: List<SyncConsumption>,
    val finishActions: List<SyncFinishAction> = emptyList(),
    val consumptionCorrections: List<SyncConsumptionCorrection> = emptyList(),
)

@JsonClass(generateAdapter = true)
data class SyncPurchase(
    val actionId: String,
    val tempId: String,
    val date: String,
    val type: String,
    val name: String,
    val cost: Double?,
    val thc: Double?,
    val grams: Double?,
    val borrowed: Int,
    val postTax: Boolean,
    val productUuid: String? = null,
)

@JsonClass(generateAdapter = true)
data class SyncConsumption(
    val eventId: String,
    val date: String,
    val time: String,
    val productId: String,
    val uses: Double,
    val isFinished: Boolean,
    val productUuid: String? = null,
    val weightCode: String? = null,
)

@JsonClass(generateAdapter = true)
data class SyncFinishAction(
    val actionId: String,
    val date: String,
    val time: String,
    val productId: String,
    val productUuid: String? = null,
)

@JsonClass(generateAdapter = true)
data class SyncConsumptionCorrection(
    val actionId: String,
    val targetEventId: String,
    val expectedCorrectionHeadId: String,
    val operation: ConsumptionCorrectionOperation,
    val reopenProduct: Boolean,
    val reason: String? = null,
    val replacement: SyncConsumptionCorrectionReplacement? = null,
)

@JsonClass(generateAdapter = true)
data class SyncConsumptionCorrectionReplacement(
    val date: String,
    val time: String,
    val productUuid: String,
    val productId: String,
    val uses: Double,
    val weightCode: String,
    val finished: Boolean,
)

@JsonClass(generateAdapter = true)
data class AcknowledgedPurchase(
    val actionId: String,
    val tempId: String? = null,
    val productUuid: String? = null,
    val legacyProductId: String? = null,
    val status: String,
)

@JsonClass(generateAdapter = true)
data class RejectedPurchase(
    val actionId: String? = null,
    val errorCode: String,
    val message: String,
)

@JsonClass(generateAdapter = true)
data class AcknowledgedConsumption(
    val eventId: String,
    val status: String,
)

@JsonClass(generateAdapter = true)
data class RejectedConsumption(
    val eventId: String? = null,
    val errorCode: String,
    val message: String,
)

@JsonClass(generateAdapter = true)
data class AcknowledgedFinishAction(
    val actionId: String,
    val status: String,
)

@JsonClass(generateAdapter = true)
data class RejectedFinishAction(
    val actionId: String? = null,
    val errorCode: String,
    val message: String,
)

@JsonClass(generateAdapter = true)
data class AcknowledgedConsumptionCorrection(
    val actionId: String,
    val targetEventId: String,
    val revision: Int,
    val status: String,
)

@JsonClass(generateAdapter = true)
data class RejectedConsumptionCorrection(
    val actionId: String? = null,
    val targetEventId: String? = null,
    val errorCode: String,
    val message: String,
    val currentHeadId: String? = null,
)

@JsonClass(generateAdapter = true)
data class SyncResponse(
    val success: Boolean,
    val message: String? = null,
    val productIdMap: Map<String, String> = emptyMap(),
    val apiVersion: Int? = null,
    val requestId: String? = null,
    val allAccepted: Boolean? = null,
    val errorCode: String? = null,
    val environment: String? = null,
    val acknowledgedPurchases: List<AcknowledgedPurchase> = emptyList(),
    val rejectedPurchases: List<RejectedPurchase> = emptyList(),
    val acknowledgedConsumptions: List<AcknowledgedConsumption> = emptyList(),
    val rejectedConsumptions: List<RejectedConsumption> = emptyList(),
    val acknowledgedFinishActions: List<AcknowledgedFinishAction>? = null,
    val rejectedFinishActions: List<RejectedFinishAction>? = null,
    val correctionVersion: Int? = null,
    val correctionWritesEnabled: Boolean? = null,
    val acknowledgedConsumptionCorrections: List<AcknowledgedConsumptionCorrection>? = null,
    val rejectedConsumptionCorrections: List<RejectedConsumptionCorrection>? = null,
)

internal fun environmentMatches(expected: String, actual: String?): Boolean = actual == expected

interface GasApiService {
    @GET
    suspend fun getProducts(@Url url: String): okhttp3.ResponseBody

    @GET
    suspend fun getAnalytics(@Url url: String): okhttp3.ResponseBody

    @POST
    suspend fun syncData(@Url url: String, @Body payload: SyncPayload): okhttp3.ResponseBody
}
