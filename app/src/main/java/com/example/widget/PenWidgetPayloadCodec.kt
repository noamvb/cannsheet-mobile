package com.example.widget

import com.squareup.moshi.Moshi

object PenWidgetPayloadCodec {
    private val adapter = Moshi.Builder()
        .build()
        .adapter(PenWidgetCommitPayload::class.java)

    fun encode(payload: PenWidgetCommitPayload): String = adapter.toJson(payload)

    fun decode(json: String?): PenWidgetCommitPayload? = runCatching {
        json?.let(adapter::fromJson)
    }.getOrNull()?.takeIf(::isValid)

    private fun isValid(payload: PenWidgetCommitPayload): Boolean =
        payload.version == PEN_WIDGET_PAYLOAD_VERSION &&
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
}
