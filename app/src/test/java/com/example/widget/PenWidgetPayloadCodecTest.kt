package com.example.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class PenWidgetPayloadCodecTest {
    @Test
    fun roundTripsPayload() {
        val payload = payload()

        assertEquals(payload, PenWidgetPayloadCodec.decode(PenWidgetPayloadCodec.encode(payload)))
    }

    @Test
    fun rejectsUnknownVersionMalformedJsonMissingFieldsAndNonFiniteUses() {
        val encoded = PenWidgetPayloadCodec.encode(payload())
        assertNull(PenWidgetPayloadCodec.decode(encoded.replace("\"version\":2", "\"version\":3")))
        assertNull(PenWidgetPayloadCodec.decode("not json"))
        assertNull(PenWidgetPayloadCodec.decode("{\"version\":2}"))
        assertNull(
            PenWidgetPayloadCodec.decode(
                encoded.replace("\"uses\":3.0", "\"uses\":NaN"),
            ),
        )
    }

    @Test
    fun migratesVersionOnePayloadWithStableEventIdentity() {
        val versionOne = """{"version":1,"commitId":"commit-v1","commitAtEpochMillis":7000,"productId":"pen-1","productUuid":null,"seconds":30,"secondsPerUse":10.0,"uses":3.0,"date":"2026-08-12","time":"12:00"}"""

        val first = PenWidgetPayloadCodec.decode(versionOne)
        val second = PenWidgetPayloadCodec.decode(versionOne)

        assertNotNull(first)
        assertEquals(PEN_WIDGET_PAYLOAD_VERSION, first?.version)
        assertEquals(2_000L, first?.submittedAtEpochMillis)
        assertEquals(first?.eventId, second?.eventId)
        assertNull(first?.claimId)
    }

    private fun payload() = PenWidgetCommitPayload(
        version = PEN_WIDGET_PAYLOAD_VERSION,
        commitId = "commit-1",
        eventId = "event-1",
        submittedAtEpochMillis = 0L,
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
