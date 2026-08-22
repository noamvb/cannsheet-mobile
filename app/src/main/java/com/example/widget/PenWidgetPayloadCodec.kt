package com.example.widget

import com.squareup.moshi.Moshi
import com.squareup.moshi.JsonClass
import java.nio.charset.StandardCharsets
import java.util.UUID

object PenWidgetPayloadCodec {
    private val moshi = Moshi.Builder().build()
    private val adapter = moshi.adapter(PenWidgetCommitPayload::class.java)
    private val versionTwoAdapter = moshi.adapter(PenWidgetCommitPayloadV2::class.java)
    private val versionOneAdapter = moshi.adapter(PenWidgetCommitPayloadV1::class.java)

    fun encode(payload: PenWidgetCommitPayload): String {
        require(isValid(payload)) { "Only valid deferred pen payload version 3 can be encoded." }
        return adapter.toJson(payload)
    }

    fun decode(json: String?): PenWidgetCommitPayload? {
        if (json == null) return null
        val current = runCatching { adapter.fromJson(json) }.getOrNull()
        if (current != null && isValid(current)) return current

        val versionTwo = runCatching { versionTwoAdapter.fromJson(json) }.getOrNull()
        if (versionTwo != null && isValidVersionTwo(versionTwo)) {
            return versionTwo.toCurrentPayload()
        }

        val versionOne = runCatching { versionOneAdapter.fromJson(json) }.getOrNull()
            ?.takeIf(::isValidVersionOne)
            ?: return null
        return versionOne.toCurrentPayload()
    }

    private fun isValid(payload: PenWidgetCommitPayload): Boolean =
        payload.version == PEN_WIDGET_PAYLOAD_VERSION &&
            payload.commitId.isNotBlank() &&
            payload.eventId.isNotBlank() &&
            payload.submittedAtEpochMillis >= 0L &&
            payload.commitAtEpochMillis >= 0L &&
            payload.commitAtEpochMillis >= payload.submittedAtEpochMillis &&
            (payload.claimId == null) == (payload.claimedAtEpochMillis == null) &&
            payload.claimId?.isNotBlank() != false &&
            payload.claimedAtEpochMillis?.let { it >= 0L } != false &&
            payload.productId.isNotBlank() &&
            isValidInput(payload) &&
            payload.uses.isFinite() &&
            payload.uses > 0.0 &&
            payload.date.isNotBlank() &&
            payload.time.isNotBlank()

    private fun isValidInput(payload: PenWidgetCommitPayload): Boolean = when (payload.inputKind) {
        DeferredPenInputKind.DURATION_SECONDS ->
            payload.seconds?.let { seconds ->
                seconds in 1..MAX_SECONDS && payload.restoreDraftSeconds == seconds
            } == true &&
                payload.secondsPerUse?.let { it.isFinite() && it > 0.0 } == true &&
                payload.restoreDraftSeconds?.let { it in 1..MAX_SECONDS } == true

        DeferredPenInputKind.DIRECT_USES ->
            payload.seconds == null &&
                payload.secondsPerUse == null &&
                payload.restoreDraftSeconds == null
    }

    private fun isValidVersionTwo(payload: PenWidgetCommitPayloadV2): Boolean =
        payload.version == 2 &&
            payload.commitId.isNotBlank() &&
            payload.eventId.isNotBlank() &&
            payload.submittedAtEpochMillis >= 0L &&
            payload.commitAtEpochMillis >= payload.submittedAtEpochMillis &&
            (payload.claimId == null) == (payload.claimedAtEpochMillis == null) &&
            payload.claimId?.isNotBlank() != false &&
            payload.claimedAtEpochMillis?.let { it >= 0L } != false &&
            payload.productId.isNotBlank() &&
            payload.seconds in 1..MAX_SECONDS &&
            payload.secondsPerUse.isFinite() &&
            payload.secondsPerUse > 0.0 &&
            payload.uses.isFinite() &&
            payload.uses > 0.0 &&
            payload.date.isNotBlank() &&
            payload.time.isNotBlank()

    private fun isValidVersionOne(payload: PenWidgetCommitPayloadV1): Boolean =
        payload.version == 1 &&
            payload.commitId.isNotBlank() &&
            payload.commitAtEpochMillis >= 0L &&
            payload.productId.isNotBlank() &&
            payload.seconds in 1..MAX_SECONDS &&
            payload.secondsPerUse.isFinite() &&
            payload.secondsPerUse > 0.0 &&
            payload.uses.isFinite() &&
            payload.uses > 0.0 &&
            payload.date.isNotBlank() &&
            payload.time.isNotBlank()

    private fun PenWidgetCommitPayloadV1.toCurrentPayload(): PenWidgetCommitPayload =
        PenWidgetCommitPayload(
            version = PEN_WIDGET_PAYLOAD_VERSION,
            commitId = commitId,
            eventId = UUID.nameUUIDFromBytes(
                "cannsheet-pen-widget-v1:$commitId".toByteArray(StandardCharsets.UTF_8),
            ).toString(),
            submittedAtEpochMillis = (commitAtEpochMillis - UNDO_WINDOW_MILLIS).coerceAtLeast(0L),
            commitAtEpochMillis = commitAtEpochMillis,
            productId = productId,
            productUuid = productUuid,
            inputKind = DeferredPenInputKind.DURATION_SECONDS,
            seconds = seconds,
            secondsPerUse = secondsPerUse,
            restoreDraftSeconds = seconds,
            uses = uses,
            date = date,
            time = time,
        )

    private fun PenWidgetCommitPayloadV2.toCurrentPayload(): PenWidgetCommitPayload =
        PenWidgetCommitPayload(
            version = PEN_WIDGET_PAYLOAD_VERSION,
            commitId = commitId,
            eventId = eventId,
            submittedAtEpochMillis = submittedAtEpochMillis,
            commitAtEpochMillis = commitAtEpochMillis,
            claimId = claimId,
            claimedAtEpochMillis = claimedAtEpochMillis,
            productId = productId,
            productUuid = productUuid,
            inputKind = DeferredPenInputKind.DURATION_SECONDS,
            seconds = seconds,
            secondsPerUse = secondsPerUse,
            restoreDraftSeconds = seconds,
            uses = uses,
            date = date,
            time = time,
        )
}

@JsonClass(generateAdapter = true)
internal data class PenWidgetCommitPayloadV2(
    val version: Int,
    val commitId: String,
    val eventId: String,
    val submittedAtEpochMillis: Long,
    val commitAtEpochMillis: Long,
    val claimId: String? = null,
    val claimedAtEpochMillis: Long? = null,
    val productId: String,
    val productUuid: String?,
    val seconds: Int,
    val secondsPerUse: Double,
    val uses: Double,
    val date: String,
    val time: String,
)

@JsonClass(generateAdapter = true)
internal data class PenWidgetCommitPayloadV1(
    val version: Int,
    val commitId: String,
    val commitAtEpochMillis: Long,
    val productId: String,
    val productUuid: String?,
    val seconds: Int,
    val secondsPerUse: Double,
    val uses: Double,
    val date: String,
    val time: String,
)
