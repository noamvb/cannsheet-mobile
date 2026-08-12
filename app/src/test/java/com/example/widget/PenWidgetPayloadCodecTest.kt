package com.example.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PenWidgetPayloadCodecTest {
    @Test
    fun roundTripsPayload() {
        val payload = payload()

        assertEquals(payload, PenWidgetPayloadCodec.decode(PenWidgetPayloadCodec.encode(payload)))
    }

    @Test
    fun rejectsWrongVersionMalformedJsonMissingFieldsAndNonFiniteUses() {
        val encoded = PenWidgetPayloadCodec.encode(payload())
        assertNull(PenWidgetPayloadCodec.decode(encoded.replace("\"version\":1", "\"version\":2")))
        assertNull(PenWidgetPayloadCodec.decode("not json"))
        assertNull(PenWidgetPayloadCodec.decode("{\"version\":1}"))
        assertNull(
            PenWidgetPayloadCodec.decode(
                encoded.replace("\"uses\":3.0", "\"uses\":NaN"),
            ),
        )
    }

    private fun payload() = PenWidgetCommitPayload(
        version = PEN_WIDGET_PAYLOAD_VERSION,
        commitId = "commit-1",
        commitAtEpochMillis = 1_000L,
        productId = "pen-1",
        productUuid = "uuid-1",
        seconds = 30,
        secondsPerUse = 10.0,
        uses = 3.0,
        date = "2026-08-12",
        time = "12:00",
    )
}
