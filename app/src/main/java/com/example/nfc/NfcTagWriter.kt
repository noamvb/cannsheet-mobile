package com.example.nfc

import android.nfc.FormatException
import android.nfc.NdefMessage
import android.nfc.Tag
import android.nfc.TagLostException
import android.nfc.tech.Ndef
import android.nfc.tech.NdefFormatable
import java.io.IOException

/**
 * Stable identity used only to make a destructive overwrite a two-tap operation on the same tag.
 * The NFC UID is not an authentication secret and is never used as the quick-log tag ID.
 */
internal data class NfcPhysicalTagFingerprint(
    val uidHex: String,
    val messageHex: String?,
    val technologies: List<String>,
) {
    val canConfirmRetap: Boolean
        get() = uidHex.isNotEmpty() || messageHex != null
}

internal data class NfcInspectedTag(
    val message: NdefMessage?,
    val fingerprint: NfcPhysicalTagFingerprint,
    val writable: Boolean,
    val maxSizeBytes: Int?,
    val formatable: Boolean,
) {
    val hasNdefContent: Boolean
        get() = message != null
}

internal sealed interface NfcTagInspectionResult {
    data class Inspected(val tag: NfcInspectedTag) : NfcTagInspectionResult

    data object Unsupported : NfcTagInspectionResult

    data object TagLost : NfcTagInspectionResult

    data class Failed(val cause: Throwable) : NfcTagInspectionResult
}

internal sealed interface NfcTagWriteResult {
    /** The serialized message was written and read back exactly on the same connection. */
    data object WrittenAndVerified : NfcTagWriteResult

    /** The tag must be removed and presented again before the registry may be updated. */
    data object WrittenAwaitingRetap : NfcTagWriteResult

    data object ReadOnly : NfcTagWriteResult

    data class TooSmall(
        val requiredBytes: Int,
        val availableBytes: Int,
    ) : NfcTagWriteResult

    data object Unsupported : NfcTagWriteResult

    data object TagLost : NfcTagWriteResult

    data class Failed(val cause: Throwable) : NfcTagWriteResult
}

internal sealed interface NfcTagVerificationResult {
    data class Exact(val tag: NfcInspectedTag) : NfcTagVerificationResult

    data class Mismatch(val tag: NfcInspectedTag) : NfcTagVerificationResult

    data object Unsupported : NfcTagVerificationResult

    data object TagLost : NfcTagVerificationResult

    data class Failed(val cause: Throwable) : NfcTagVerificationResult
}

/**
 * Narrow synchronous NFC technology boundary. Reader-mode callbacks already run off the main
 * thread, so callers can inspect/write there and marshal only the resulting UI state to main.
 *
 * Writes never erase a tag and never make it read-only. A successful return proves only that the
 * platform write/format call completed; [verifyExact] on a later physical presentation is the
 * required durability/readback boundary.
 */
internal class NfcTagWriter {
    fun inspect(tag: Tag): NfcTagInspectionResult {
        val ndef = Ndef.get(tag)
        if (ndef != null) {
            return try {
                ndef.connect()
                val message = ndef.ndefMessage
                NfcTagInspectionResult.Inspected(
                    NfcInspectedTag(
                        message = message,
                        fingerprint = tag.fingerprint(message),
                        writable = ndef.isWritable,
                        maxSizeBytes = ndef.maxSize,
                        formatable = false,
                    ),
                )
            } catch (_: TagLostException) {
                NfcTagInspectionResult.TagLost
            } catch (error: IOException) {
                NfcTagInspectionResult.Failed(error)
            } catch (error: FormatException) {
                NfcTagInspectionResult.Failed(error)
            } catch (error: SecurityException) {
                NfcTagInspectionResult.Failed(error)
            } catch (error: IllegalStateException) {
                NfcTagInspectionResult.Failed(error)
            } finally {
                ndef.closeQuietly()
            }
        }

        if (NdefFormatable.get(tag) != null) {
            return NfcTagInspectionResult.Inspected(
                NfcInspectedTag(
                    message = null,
                    fingerprint = tag.fingerprint(message = null),
                    writable = true,
                    maxSizeBytes = null,
                    formatable = true,
                ),
            )
        }

        return NfcTagInspectionResult.Unsupported
    }

    fun write(tag: Tag, message: NdefMessage): NfcTagWriteResult {
        val ndef = Ndef.get(tag)
        if (ndef != null) {
            return writeNdef(ndef, message)
        }

        val formatable = NdefFormatable.get(tag) ?: return NfcTagWriteResult.Unsupported
        return try {
            formatable.connect()
            // format(), unlike formatReadOnly(), preserves the owner's ability to rewrite the tag.
            formatable.format(message)
            NfcTagWriteResult.WrittenAwaitingRetap
        } catch (_: TagLostException) {
            NfcTagWriteResult.TagLost
        } catch (error: IOException) {
            NfcTagWriteResult.Failed(error)
        } catch (error: FormatException) {
            NfcTagWriteResult.Failed(error)
        } catch (error: SecurityException) {
            NfcTagWriteResult.Failed(error)
        } catch (error: IllegalStateException) {
            NfcTagWriteResult.Failed(error)
        } finally {
            formatable.closeQuietly()
        }
    }

    fun verifyExact(tag: Tag, expected: NdefMessage): NfcTagVerificationResult =
        when (val inspection = inspect(tag)) {
            is NfcTagInspectionResult.Inspected -> {
                val actualBytes = inspection.tag.message?.toByteArray()
                if (actualBytes != null && actualBytes.contentEquals(expected.toByteArray())) {
                    NfcTagVerificationResult.Exact(inspection.tag)
                } else {
                    NfcTagVerificationResult.Mismatch(inspection.tag)
                }
            }

            NfcTagInspectionResult.Unsupported -> NfcTagVerificationResult.Unsupported
            NfcTagInspectionResult.TagLost -> NfcTagVerificationResult.TagLost
            is NfcTagInspectionResult.Failed ->
                NfcTagVerificationResult.Failed(inspection.cause)
        }

    private fun writeNdef(ndef: Ndef, message: NdefMessage): NfcTagWriteResult = try {
        ndef.connect()
        if (!ndef.isWritable) {
            NfcTagWriteResult.ReadOnly
        } else {
            val requiredBytes = message.toByteArray().size
            val availableBytes = ndef.maxSize
            if (requiredBytes > availableBytes) {
                NfcTagWriteResult.TooSmall(requiredBytes, availableBytes)
            } else {
                ndef.writeNdefMessage(message)
                val readback = ndef.ndefMessage?.toByteArray()
                if (readback?.contentEquals(message.toByteArray()) == true) {
                    NfcTagWriteResult.WrittenAndVerified
                } else {
                    NfcTagWriteResult.Failed(IOException("NDEF readback did not match"))
                }
            }
        }
    } catch (_: TagLostException) {
        NfcTagWriteResult.TagLost
    } catch (error: IOException) {
        NfcTagWriteResult.Failed(error)
    } catch (error: FormatException) {
        NfcTagWriteResult.Failed(error)
    } catch (error: SecurityException) {
        NfcTagWriteResult.Failed(error)
    } catch (error: IllegalStateException) {
        NfcTagWriteResult.Failed(error)
    } finally {
        ndef.closeQuietly()
    }
}

internal fun samePhysicalTag(
    expected: NfcPhysicalTagFingerprint,
    actual: NfcPhysicalTagFingerprint,
): Boolean = expected.canConfirmRetap &&
    expected.uidHex == actual.uidHex &&
    expected.messageHex == actual.messageHex &&
    expected.technologies == actual.technologies

private fun Tag.fingerprint(message: NdefMessage?): NfcPhysicalTagFingerprint =
    NfcPhysicalTagFingerprint(
        uidHex = id?.toHex().orEmpty(),
        messageHex = message?.toByteArray()?.toHex(),
        technologies = techList.orEmpty().sorted(),
    )

private fun ByteArray.toHex(): String = joinToString(separator = "") { byte ->
    "%02x".format(byte.toInt() and 0xff)
}

private fun AutoCloseable.closeQuietly() {
    runCatching { close() }
}
