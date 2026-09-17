package com.example.data

import com.squareup.moshi.Moshi
import org.junit.Assert.assertEquals
import org.junit.Test

class NetworkClientStateTest {
    @Test
    fun clearedLoadedPenEncodesAnExplicitJsonNull() {
        val moshi = Moshi.Builder().add(SyncClientStateJsonAdapterFactory).build()

        val json = moshi.adapter(SyncClientState::class.java).toJson(SyncClientState(null, 1))

        assertEquals(
            """{"loadedPenProductId":null,"loadedPenUpdatedAtEpochMillis":1}""",
            json,
        )
    }

    @Test
    fun decodesAcknowledgedClientStateFromALiteralResponse() {
        val moshi = Moshi.Builder().build()
        val json = """
            {"success":true,"apiVersion":2,"requestId":"<id>","environment":"SANDBOX",
            "acknowledgedConsumptions":[],
            "acknowledgedClientState":{"loadedPenUpdatedAtEpochMillis":1789616287000,"status":"committed"}}
        """.trimIndent().replace("\n", "")

        val response = moshi.adapter(SyncResponse::class.java).fromJson(json)

        assertEquals(
            AcknowledgedClientState(1789616287000, "committed", null, null),
            response?.acknowledgedClientState,
        )
    }

    @Test
    fun payloadWithoutPendingPenOmitsClientStateEntirely() {
        val moshi = Moshi.Builder().add(SyncClientStateJsonAdapterFactory).build()
        val payload = SyncPayload(
            requestId = "r",
            environment = "PRODUCTION",
            purchases = emptyList(),
            consumptions = emptyList(),
        )

        val json = moshi.adapter(SyncPayload::class.java).toJson(payload)

        assertEquals(
            """{"apiVersion":2,"requestId":"r","environment":"PRODUCTION","purchases":[],"consumptions":[],"finishActions":[],"consumptionCorrections":[]}""",
            json,
        )
    }

    @Test
    fun payloadWithPendingPenEncodesTheObjectWithItsNullField() {
        val moshi = Moshi.Builder().add(SyncClientStateJsonAdapterFactory).build()
        val payload = SyncPayload(
            requestId = "r",
            environment = "PRODUCTION",
            purchases = emptyList(),
            consumptions = emptyList(),
            clientState = SyncClientState(null, 1),
        )

        val json = moshi.adapter(SyncPayload::class.java).toJson(payload)

        assertEquals(
            """{"apiVersion":2,"requestId":"r","environment":"PRODUCTION","purchases":[],"consumptions":[],"finishActions":[],"consumptionCorrections":[],"clientState":{"loadedPenProductId":null,"loadedPenUpdatedAtEpochMillis":1}}""",
            json,
        )
    }

    @Test
    fun payloadWithLoadedPenEncodesTheProductId() {
        val moshi = Moshi.Builder().add(SyncClientStateJsonAdapterFactory).build()
        val payload = SyncPayload(
            requestId = "r",
            environment = "PRODUCTION",
            purchases = emptyList(),
            consumptions = emptyList(),
            clientState = SyncClientState("*P115", 1789616287000),
        )

        val json = moshi.adapter(SyncPayload::class.java).toJson(payload)

        assertEquals(
            """{"apiVersion":2,"requestId":"r","environment":"PRODUCTION","purchases":[],"consumptions":[],"finishActions":[],"consumptionCorrections":[],"clientState":{"loadedPenProductId":"*P115","loadedPenUpdatedAtEpochMillis":1789616287000}}""",
            json,
        )
    }
}
