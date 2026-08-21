package com.example.nfc

import java.nio.charset.StandardCharsets
import java.util.UUID
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class NfcQuickLogContractTest {
    @Test
    fun newTagDataMintsRandomRfc4122UuidV4AndValidatesUses() {
        val first = NfcQuickLogContract.newTagData(1)
        val second = NfcQuickLogContract.newTagData(10)

        val firstUuid = UUID.fromString(first.tagId)
        assertEquals(4, firstUuid.version())
        assertEquals(2, firstUuid.variant())
        assertEquals(firstUuid.toString(), first.tagId)
        assertEquals(1, first.uses)
        assertTrue(first.tagId != second.tagId)
        assertThrows(IllegalArgumentException::class.java) {
            NfcQuickLogContract.newTagData(0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            NfcQuickLogContract.newTagData(11)
        }
    }

    @Test
    fun payloadHasExactVersionUuidNetworkByteOrderAndUnsignedUses() {
        val payload = NfcQuickLogContract.encodePayload(
            NfcQuickLogTagData(
                tagId = TAG_ID,
                uses = 10,
            ),
        )

        assertArrayEquals(
            byteArrayOf(
                0x01,
                0x00, 0x11, 0x22, 0x33,
                0x44, 0x55,
                0x46, 0x77,
                0x88.toByte(), 0x99.toByte(),
                0xaa.toByte(), 0xbb.toByte(), 0xcc.toByte(), 0xdd.toByte(),
                0xee.toByte(), 0xff.toByte(),
                0x0a,
            ),
            payload,
        )
        assertEquals(
            NfcQuickLogTagData(TAG_ID, 10),
            NfcQuickLogContract.decodePayload(payload),
        )
    }

    @Test
    fun decoderAcceptsFutureCompatibleRfc4122UuidVersions() {
        val payload = NfcQuickLogContract.encodePayload(
            NfcQuickLogTagData(
                tagId = VERSION_ONE_TAG_ID,
                uses = 3,
            ),
        )

        assertEquals(
            NfcQuickLogTagData(VERSION_ONE_TAG_ID, 3),
            NfcQuickLogContract.decodePayload(payload),
        )
    }

    @Test
    fun payloadCodecRejectsNonCanonicalIdsInvalidVariantVersionLengthAndUses() {
        assertFalse(NfcQuickLogContract.isCanonicalRfc4122Uuid(TAG_ID.uppercase()))
        assertFalse(NfcQuickLogContract.isCanonicalRfc4122Uuid("1-1-1-1-1"))
        assertFalse(NfcQuickLogContract.isCanonicalRfc4122Uuid("00112233-4455-4677-0899-aabbccddeeff"))
        assertThrows(IllegalArgumentException::class.java) {
            NfcQuickLogContract.encodePayload(NfcQuickLogTagData(TAG_ID.uppercase(), 1))
        }
        assertThrows(IllegalArgumentException::class.java) {
            NfcQuickLogContract.encodePayload(NfcQuickLogTagData(TAG_ID, 0))
        }

        val valid = NfcQuickLogContract.encodePayload(NfcQuickLogTagData(TAG_ID, 1))
        assertNull(NfcQuickLogContract.decodePayload(valid.copyOf(17)))
        assertNull(NfcQuickLogContract.decodePayload(valid + byteArrayOf(0)))
        assertNull(NfcQuickLogContract.decodePayload(valid.copyOf().apply { this[0] = 2 }))
        assertNull(NfcQuickLogContract.decodePayload(valid.copyOf().apply { this[9] = 0x08 }))
        assertNull(NfcQuickLogContract.decodePayload(valid.copyOf().apply { this[17] = 0 }))
        assertNull(NfcQuickLogContract.decodePayload(valid.copyOf().apply { this[17] = 11 }))
    }

    @Test
    fun rawMessageParserAcceptsOnlyExactTwoRecordContract() {
        val records = compatibleRecords()

        assertEquals(
            NfcQuickLogTagData(TAG_ID, 10),
            NfcQuickLogContract.parseRawMessage(records, PACKAGE_NAME),
        )
        assertEquals(
            NfcQuickLogTagData(TAG_ID, 10),
            NfcQuickLogContract.parseRawDiscoveredTag(
                action = ACTION_NDEF_DISCOVERED,
                dataString = NfcQuickLogContract.EXTERNAL_URI,
                messages = listOf(records),
                packageName = PACKAGE_NAME,
            ),
        )
    }

    @Test
    fun discoveredParserRejectsWrongActionUriOrMessageCount() {
        val records = compatibleRecords()

        assertNull(
            NfcQuickLogContract.parseRawDiscoveredTag(
                action = "android.nfc.action.TAG_DISCOVERED",
                dataString = NfcQuickLogContract.EXTERNAL_URI,
                messages = listOf(records),
                packageName = PACKAGE_NAME,
            ),
        )
        assertNull(
            NfcQuickLogContract.parseRawDiscoveredTag(
                action = ACTION_NDEF_DISCOVERED,
                dataString = NfcQuickLogContract.EXTERNAL_URI.uppercase(),
                messages = listOf(records),
                packageName = PACKAGE_NAME,
            ),
        )
        assertNull(
            NfcQuickLogContract.parseRawDiscoveredTag(
                action = ACTION_NDEF_DISCOVERED,
                dataString = NfcQuickLogContract.EXTERNAL_URI,
                messages = emptyList(),
                packageName = PACKAGE_NAME,
            ),
        )
        assertNull(
            NfcQuickLogContract.parseRawDiscoveredTag(
                action = ACTION_NDEF_DISCOVERED,
                dataString = NfcQuickLogContract.EXTERNAL_URI,
                messages = listOf(records, records),
                packageName = PACKAGE_NAME,
            ),
        )
    }

    @Test
    fun messageParserRejectsMissingExtraOrReorderedRecords() {
        val records = compatibleRecords()

        assertNull(NfcQuickLogContract.parseRawMessage(emptyList(), PACKAGE_NAME))
        assertNull(NfcQuickLogContract.parseRawMessage(records.take(1), PACKAGE_NAME))
        assertNull(NfcQuickLogContract.parseRawMessage(records + records.first(), PACKAGE_NAME))
        assertNull(NfcQuickLogContract.parseRawMessage(records.reversed(), PACKAGE_NAME))
    }

    @Test
    fun messageParserRejectsEveryExternalRecordMismatch() {
        val records = compatibleRecords()
        val external = records[0]

        assertNull(
            NfcQuickLogContract.parseRawMessage(
                listOf(external.copy(tnf = 0x01), records[1]),
                PACKAGE_NAME,
            ),
        )
        assertNull(
            NfcQuickLogContract.parseRawMessage(
                listOf(external.copy(type = "other:type".ascii()), records[1]),
                PACKAGE_NAME,
            ),
        )
        assertNull(
            NfcQuickLogContract.parseRawMessage(
                listOf(external.copy(id = byteArrayOf(1)), records[1]),
                PACKAGE_NAME,
            ),
        )
        assertNull(
            NfcQuickLogContract.parseRawMessage(
                listOf(external.copy(payload = external.payload.copyOf(17)), records[1]),
                PACKAGE_NAME,
            ),
        )
    }

    @Test
    fun messageParserRejectsEveryApplicationRecordMismatch() {
        val records = compatibleRecords()
        val aar = records[1]

        assertNull(
            NfcQuickLogContract.parseRawMessage(
                listOf(records[0], aar.copy(tnf = 0x01)),
                PACKAGE_NAME,
            ),
        )
        assertNull(
            NfcQuickLogContract.parseRawMessage(
                listOf(records[0], aar.copy(type = "android.com:other".ascii())),
                PACKAGE_NAME,
            ),
        )
        assertNull(
            NfcQuickLogContract.parseRawMessage(
                listOf(records[0], aar.copy(id = byteArrayOf(1))),
                PACKAGE_NAME,
            ),
        )
        assertNull(
            NfcQuickLogContract.parseRawMessage(
                listOf(records[0], aar.copy(payload = "other.package".utf8())),
                PACKAGE_NAME,
            ),
        )
        assertNull(NfcQuickLogContract.parseRawMessage(records, ""))
        assertNull(NfcQuickLogContract.parseRawMessage(records, "$PACKAGE_NAME.sandbox"))
    }

    private fun compatibleRecords(): List<NfcQuickLogRawRecord> = listOf(
        NfcQuickLogRawRecord(
            tnf = 0x04,
            type = NfcQuickLogContract.EXTERNAL_TYPE_NAME.ascii(),
            id = byteArrayOf(),
            payload = NfcQuickLogContract.encodePayload(NfcQuickLogTagData(TAG_ID, 10)),
        ),
        NfcQuickLogRawRecord(
            tnf = 0x04,
            type = "android.com:pkg".ascii(),
            id = byteArrayOf(),
            payload = PACKAGE_NAME.utf8(),
        ),
    )

    private fun NfcQuickLogRawRecord.copy(
        tnf: Short = this.tnf,
        type: ByteArray = this.type,
        id: ByteArray = this.id,
        payload: ByteArray = this.payload,
    ) = NfcQuickLogRawRecord(tnf, type, id, payload)

    private fun String.ascii(): ByteArray = toByteArray(StandardCharsets.US_ASCII)
    private fun String.utf8(): ByteArray = toByteArray(StandardCharsets.UTF_8)

    companion object {
        private const val ACTION_NDEF_DISCOVERED = "android.nfc.action.NDEF_DISCOVERED"
        private const val PACKAGE_NAME = "com.noamv.cannsheet.mobile"
        private const val TAG_ID = "00112233-4455-4677-8899-aabbccddeeff"
        private const val VERSION_ONE_TAG_ID = "00112233-4455-1677-8899-aabbccddeeff"
    }
}
