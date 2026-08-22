package com.example.nfc

import android.content.Intent
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import android.os.Build
import android.os.Parcelable
import com.example.BuildConfig
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.util.UUID

/** The authenticated-by-local-registry data carried by a Cannsheet quick-log NFC tag. */
data class NfcQuickLogTagData(
    val tagId: String,
    val uses: Int,
)

/**
 * Versioned NFC wire contract for a pen quick-log tag.
 *
 * A compatible message has exactly two records, in this order:
 * 1. `com.noamv.cannsheet.mobile:pen-quick-log`, with an 18-byte binary payload.
 * 2. An Android Application Record for the package that wrote the tag.
 *
 * Parsing is deliberately fail-closed. Unknown versions, extra records/messages, non-empty record
 * IDs, malformed UUIDs, unexpected packages, and quantities outside [MIN_USES]..[MAX_USES] are
 * incompatible rather than partially accepted.
 */
object NfcQuickLogContract {
    const val EXTERNAL_DOMAIN = "com.noamv.cannsheet.mobile"
    const val EXTERNAL_TYPE = "pen-quick-log"
    const val EXTERNAL_TYPE_NAME = "$EXTERNAL_DOMAIN:$EXTERNAL_TYPE"
    const val EXTERNAL_URI = "vnd.android.nfc://ext/$EXTERNAL_TYPE_NAME"

    const val PAYLOAD_VERSION = 1
    const val PAYLOAD_SIZE_BYTES = 18
    const val MIN_USES = 1
    const val MAX_USES = 10

    private val externalTypeBytes = EXTERNAL_TYPE_NAME.toByteArray(StandardCharsets.US_ASCII)
    private val androidApplicationRecordTypeBytes =
        "android.com:pkg".toByteArray(StandardCharsets.US_ASCII)

    /** Creates new writer data. Writers always mint a random UUIDv4. */
    fun newTagData(uses: Int): NfcQuickLogTagData {
        require(isValidUses(uses)) { "NFC quick-log uses must be between $MIN_USES and $MAX_USES." }
        return NfcQuickLogTagData(
            tagId = UUID.randomUUID().toString(),
            uses = uses,
        )
    }

    /**
     * Builds the exact two-record message written to a tag.
     *
     * [packageName] should be the writer context's runtime package name. Its default is the current
     * variant's generated application ID, keeping ordinary production calls concise while allowing
     * sandbox builds to pass `context.packageName` explicitly.
     */
    fun messageFor(
        tagId: String,
        uses: Int,
        packageName: String = BuildConfig.APPLICATION_ID,
    ): NdefMessage {
        require(packageName.isNotBlank()) { "An application package name is required." }
        val payload = encodePayload(NfcQuickLogTagData(tagId = tagId, uses = uses))
        return NdefMessage(
            arrayOf(
                NdefRecord.createExternal(EXTERNAL_DOMAIN, EXTERNAL_TYPE, payload),
                NdefRecord.createApplicationRecord(packageName),
            ),
        )
    }

    /** Parses a directly inspected NDEF message, as used by the tag-writing/readback flow. */
    fun parse(
        message: NdefMessage,
        packageName: String = BuildConfig.APPLICATION_ID,
    ): NfcQuickLogTagData? = runCatching {
        parseRawMessage(
            records = message.records.map { it.toRawRecord() },
            packageName = packageName,
        )
    }.getOrNull()

    /**
     * Parses only an exact platform `ACTION_NDEF_DISCOVERED` delivery for this external type.
     * The intent must contain exactly one NDEF message with the exact two-record contract.
     */
    fun parse(
        intent: Intent,
        packageName: String = BuildConfig.APPLICATION_ID,
    ): NfcQuickLogTagData? = runCatching {
        val parcelables = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableArrayExtra(
                NfcAdapter.EXTRA_NDEF_MESSAGES,
                Parcelable::class.java,
            )
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES)
        }
            ?: return null
        if (parcelables.size != 1) return null
        val message = parcelables.single() as? NdefMessage ?: return null

        parseRawDiscoveredTag(
            action = intent.action,
            dataString = intent.dataString,
            messages = listOf(message.records.map { it.toRawRecord() }),
            packageName = packageName,
        )
    }.getOrNull()

    internal fun encodePayload(tag: NfcQuickLogTagData): ByteArray {
        val uuid = canonicalRfc4122UuidOrNull(tag.tagId)
        requireNotNull(uuid) { "NFC quick-log tag ID must be a canonical RFC-4122 UUID." }
        require(isValidUses(tag.uses)) {
            "NFC quick-log uses must be between $MIN_USES and $MAX_USES."
        }

        return ByteBuffer.allocate(PAYLOAD_SIZE_BYTES)
            .order(ByteOrder.BIG_ENDIAN)
            .put(PAYLOAD_VERSION.toByte())
            .putLong(uuid.mostSignificantBits)
            .putLong(uuid.leastSignificantBits)
            .put(tag.uses.toByte())
            .array()
    }

    internal fun decodePayload(payload: ByteArray): NfcQuickLogTagData? = runCatching {
        if (payload.size != PAYLOAD_SIZE_BYTES) return null
        val buffer = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
        val version = buffer.get().toInt() and 0xff
        if (version != PAYLOAD_VERSION) return null

        val uuid = UUID(buffer.long, buffer.long)
        if (uuid.variant() != RFC_4122_VARIANT) return null

        val uses = buffer.get().toInt() and 0xff
        if (!isValidUses(uses)) return null
        NfcQuickLogTagData(tagId = uuid.toString(), uses = uses)
    }.getOrNull()

    internal fun isCanonicalRfc4122Uuid(value: String): Boolean =
        canonicalRfc4122UuidOrNull(value) != null

    internal fun isValidUses(uses: Int): Boolean = uses in MIN_USES..MAX_USES

    internal fun parseRawDiscoveredTag(
        action: String?,
        dataString: String?,
        messages: List<List<NfcQuickLogRawRecord>>,
        packageName: String,
    ): NfcQuickLogTagData? {
        if (action != ACTION_NDEF_DISCOVERED) return null
        if (dataString != EXTERNAL_URI) return null
        if (messages.size != 1) return null
        return parseRawMessage(messages.single(), packageName)
    }

    internal fun parseRawMessage(
        records: List<NfcQuickLogRawRecord>,
        packageName: String,
    ): NfcQuickLogTagData? {
        if (packageName.isBlank()) return null
        if (records.size != 2) return null

        val quickLogRecord = records[0]
        if (quickLogRecord.tnf != TNF_EXTERNAL_TYPE) return null
        if (!quickLogRecord.type.contentEquals(externalTypeBytes)) return null
        if (quickLogRecord.id.isNotEmpty()) return null
        val tag = decodePayload(quickLogRecord.payload) ?: return null

        val applicationRecord = records[1]
        if (applicationRecord.tnf != TNF_EXTERNAL_TYPE) return null
        if (!applicationRecord.type.contentEquals(androidApplicationRecordTypeBytes)) return null
        if (applicationRecord.id.isNotEmpty()) return null
        if (!applicationRecord.payload.contentEquals(packageName.toByteArray(StandardCharsets.UTF_8))) {
            return null
        }

        return tag
    }

    private fun canonicalRfc4122UuidOrNull(value: String): UUID? = runCatching {
        UUID.fromString(value).takeIf { uuid ->
            uuid.variant() == RFC_4122_VARIANT && uuid.toString() == value
        }
    }.getOrNull()

    private fun NdefRecord.toRawRecord() = NfcQuickLogRawRecord(
        tnf = tnf,
        type = type,
        id = id,
        payload = payload,
    )

    private const val ACTION_NDEF_DISCOVERED = "android.nfc.action.NDEF_DISCOVERED"
    private const val TNF_EXTERNAL_TYPE: Short = 0x04
    private const val RFC_4122_VARIANT = 2
}

/** Pure representation used to keep the strict parser exhaustively JVM-testable. */
internal class NfcQuickLogRawRecord(
    val tnf: Short,
    val type: ByteArray,
    val id: ByteArray,
    val payload: ByteArray,
)
