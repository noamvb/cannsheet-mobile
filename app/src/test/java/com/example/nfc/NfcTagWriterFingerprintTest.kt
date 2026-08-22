package com.example.nfc

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NfcTagWriterFingerprintTest {
    @Test
    fun stableUidMessageAndTechnologyFingerprintIsRequiredForOverwriteRetap() {
        val first = NfcPhysicalTagFingerprint(
            uidHex = "0102",
            messageHex = "aabb",
            technologies = listOf("android.nfc.tech.Ndef"),
        )
        val same = first.copy()
        val differentUid = first.copy(uidHex = "0304")
        val differentMessage = first.copy(messageHex = "ccdd")
        val differentTechnology = first.copy(technologies = listOf("android.nfc.tech.NdefFormatable"))

        assertTrue(samePhysicalTag(first, same))
        assertFalse(samePhysicalTag(first, differentUid))
        assertFalse(samePhysicalTag(first, differentMessage))
        assertFalse(samePhysicalTag(first, differentTechnology))
    }

    @Test
    fun messageDigestCanBeTheBestEffortIdentityWhenUidIsUnavailable() {
        val first = NfcPhysicalTagFingerprint(
            uidHex = "",
            messageHex = "aabb",
            technologies = listOf("android.nfc.tech.Ndef"),
        )
        assertTrue(samePhysicalTag(first, first.copy()))
        assertFalse(
            samePhysicalTag(
                first,
                first.copy(messageHex = null),
            ),
        )
    }

    @Test
    fun blankTagWithNoUidHasNoRetapIdentityClaim() {
        val blank = NfcPhysicalTagFingerprint(
            uidHex = "",
            messageHex = null,
            technologies = listOf("android.nfc.tech.NdefFormatable"),
        )
        assertFalse(blank.canConfirmRetap)
    }
}
