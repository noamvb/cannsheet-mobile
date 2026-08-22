package com.example.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class PenWidgetPayloadCodecTest {
    @Test
    fun roundTripsPayload() {
        val payload = payload()

        assertEquals(payload, PenWidgetPayloadCodec.decode(PenWidgetPayloadCodec.encode(payload)))

        val direct = payload().copy(
            inputKind = DeferredPenInputKind.DIRECT_USES,
            seconds = null,
            secondsPerUse = null,
            restoreDraftSeconds = null,
        )
        assertEquals(direct, PenWidgetPayloadCodec.decode(PenWidgetPayloadCodec.encode(direct)))
    }

    @Test
    fun rejectsUnknownVersionMalformedJsonMissingFieldsAndNonFiniteUses() {
        val encoded = PenWidgetPayloadCodec.encode(payload())
        assertNull(PenWidgetPayloadCodec.decode(encoded.replace("\"version\":3", "\"version\":4")))
        assertNull(PenWidgetPayloadCodec.decode("not json"))
        assertNull(PenWidgetPayloadCodec.decode("{\"version\":3}"))
        assertNull(
            PenWidgetPayloadCodec.decode(
                encoded.replace("\"uses\":3.0", "\"uses\":NaN"),
            ),
        )
        assertThrows(IllegalArgumentException::class.java) {
            PenWidgetPayloadCodec.encode(payload().copy(version = 2))
        }
    }

    @Test
    fun rejectsInputKindsWithFieldsFromTheOtherShape() {
        val duration = PenWidgetPayloadCodec.encode(payload())
        assertNull(
            PenWidgetPayloadCodec.decode(
                duration.replace("DURATION_SECONDS", "DIRECT_USES"),
            ),
        )

        val direct = PenWidgetPayloadCodec.encode(
            payload().copy(
                inputKind = DeferredPenInputKind.DIRECT_USES,
                seconds = null,
                secondsPerUse = null,
                restoreDraftSeconds = null,
            ),
        )
        assertNull(
            PenWidgetPayloadCodec.decode(
                direct.replace("\"uses\":3.0", "\"seconds\":30,\"uses\":3.0"),
            ),
        )
    }

    @Test
    fun rejectsDurationPayloadWhoseUndoDraftDiffersFromSeconds() {
        assertThrows(IllegalArgumentException::class.java) {
            PenWidgetPayloadCodec.encode(payload().copy(restoreDraftSeconds = 20))
        }
    }

    @Test
    fun migratesVersionTwoDurationPayloadWithoutChangingIdentityOrClaim() {
        val versionTwo = """
            {"version":2,"commitId":"commit-v2","eventId":"event-v2",
            "submittedAtEpochMillis":1000,"commitAtEpochMillis":7500,
            "claimId":"owner:claim","claimedAtEpochMillis":8000,
            "productId":"pen-1","productUuid":"uuid-1","seconds":30,
            "secondsPerUse":10.0,"uses":3.0,"date":"2026-08-12","time":"12:00"}
        """.trimIndent().replace("\n", "")

        val migrated = requireNotNull(PenWidgetPayloadCodec.decode(versionTwo))

        assertEquals(PEN_WIDGET_PAYLOAD_VERSION, migrated.version)
        assertEquals("commit-v2", migrated.commitId)
        assertEquals("event-v2", migrated.eventId)
        assertEquals(1_000L, migrated.submittedAtEpochMillis)
        assertEquals(7_500L, migrated.commitAtEpochMillis)
        assertEquals("owner:claim", migrated.claimId)
        assertEquals(8_000L, migrated.claimedAtEpochMillis)
        assertEquals("pen-1", migrated.productId)
        assertEquals("uuid-1", migrated.productUuid)
        assertEquals(DeferredPenInputKind.DURATION_SECONDS, migrated.inputKind)
        assertEquals(30, migrated.seconds)
        assertEquals(10.0, requireNotNull(migrated.secondsPerUse), 0.0)
        assertEquals(30, migrated.restoreDraftSeconds)
        assertEquals(3.0, migrated.uses, 0.0)
        assertEquals("2026-08-12", migrated.date)
        assertEquals("12:00", migrated.time)
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
        assertEquals(DeferredPenInputKind.DURATION_SECONDS, first?.inputKind)
        assertEquals(30, first?.restoreDraftSeconds)
    }

    private fun payload() = PenWidgetCommitPayload(
        version = PEN_WIDGET_PAYLOAD_VERSION,
        commitId = "commit-1",
        eventId = "event-1",
        submittedAtEpochMillis = 0L,
        commitAtEpochMillis = 1_000L,
        productId = "pen-1",
        productUuid = "uuid-1",
        inputKind = DeferredPenInputKind.DURATION_SECONDS,
        seconds = 30,
        secondsPerUse = 10.0,
        restoreDraftSeconds = 30,
        uses = 3.0,
        date = "2026-08-12",
        time = "12:00",
    )
}
