package com.example.data.barcode

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class Gs1BarcodeTest {

    @Test
    fun testParsesParenthesizedElementString() {
        assertEquals(EXPECTED_RESULT, Gs1Barcode.parse(PARENTHESIZED))
    }

    @Test
    fun testParsesGroupSeparatorElementString() {
        val raw = "0100840773004481\u001D13260708\u001D1026070000162\u001D"

        assertEquals(EXPECTED_RESULT, Gs1Barcode.parse(raw))
    }

    @Test
    fun testParsesBareConcatenatedElementString() {
        assertEquals(EXPECTED_RESULT, Gs1Barcode.parse(BARE_CONCATENATED))
    }

    @Test
    fun testStripsSymbologyIdentifier() {
        assertEquals(EXPECTED_RESULT, Gs1Barcode.parse("]d2$BARE_CONCATENATED"))
    }

    @Test
    fun testNormalizesBareUpcA() {
        assertEquals(
            Gs1ScanResult(gtin = GTIN, batch = null, packDate = null),
            Gs1Barcode.parse("840773004481"),
        )
    }

    @Test
    fun testNormalizesBareEan13() {
        assertEquals(
            Gs1ScanResult(gtin = GTIN, batch = null, packDate = null),
            Gs1Barcode.parse("0840773004481"),
        )
    }

    @Test
    fun testRejectsCorruptedCheckDigit() {
        assertNull(Gs1Barcode.parse("840773004482"))
    }

    @Test
    fun testRejectsEmptyVariableValue() {
        assertNull(Gs1Barcode.parse("(01)00840773004481(10)"))
    }

    @Test
    fun testRejectsTruncatedFixedField() {
        assertNull(Gs1Barcode.parse("0100840773"))
    }

    @Test
    fun testRejectsNonDigitGtin() {
        assertNull(Gs1Barcode.parse("(01)ABCDEFGHIJKLMN"))
    }

    @Test
    fun testRejectsBlankInput() {
        assertNull(Gs1Barcode.parse(""))
        assertNull(Gs1Barcode.parse("   "))
    }

    @Test
    fun testSkipsTerminatedUnknownAiAfterGtin() {
        val raw = "010084077300448191private\u001D"

        assertEquals(
            Gs1ScanResult(gtin = GTIN, batch = null, packDate = null),
            Gs1Barcode.parse(raw),
        )
    }

    @Test
    fun testStopsAtUnterminatedUnknownAiAfterGtin() {
        val raw = "010084077300448191private"

        assertEquals(
            Gs1ScanResult(gtin = GTIN, batch = null, packDate = null),
            Gs1Barcode.parse(raw),
        )
    }

    @Test
    fun testRejectsImpossibleMonth() {
        assertNull(Gs1Barcode.parse("(01)00840773004481(13)261308(10)X"))
    }

    @Test
    fun testNormalizesUnspecifiedDayToFirstOfMonth() {
        assertEquals(
            Gs1ScanResult(gtin = GTIN, batch = "ABC", packDate = "2026-07-01"),
            Gs1Barcode.parse("(01)00840773004481(13)260700(10)ABC"),
        )
    }

    @Test
    fun testNormalizesEquivalentGtinFormsIdentically() {
        assertEquals(GTIN, Gs1Barcode.normalizeGtin("840773004481"))
        assertEquals(GTIN, Gs1Barcode.normalizeGtin("0840773004481"))
        assertEquals(GTIN, Gs1Barcode.normalizeGtin("00840773004481"))
    }

    @Test
    fun testRejectsImpossibleCalendarDay() {
        assertNull(Gs1Barcode.parse("(01)00840773004481(13)260231"))
    }

    @Test
    fun testUsesContainedGtinWhenPrimaryGtinIsAbsent() {
        assertEquals(
            Gs1ScanResult(gtin = GTIN, batch = "LOT", packDate = null),
            Gs1Barcode.parse("(02)00840773004481(10)LOT"),
        )
    }

    /**
     * The pack date is data, not display text. A device whose default locale uses
     * non-ASCII digits must still produce an ASCII `yyyy-MM-dd` string.
     */
    @Test
    fun testPackDateUsesAsciiDigitsUnderANonAsciiDigitLocale() {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("ar-SA-u-nu-arab"))
            assertEquals("2026-07-08", Gs1Barcode.parse(PARENTHESIZED)?.packDate)
        } finally {
            Locale.setDefault(original)
        }
    }

    private companion object {
        const val GTIN = "00840773004481"
        const val PARENTHESIZED = "(01)00840773004481(13)260708(10)26070000162"
        const val BARE_CONCATENATED = "0100840773004481132607081026070000162"
        val EXPECTED_RESULT = Gs1ScanResult(
            gtin = GTIN,
            batch = "26070000162",
            packDate = "2026-07-08",
        )
    }
}
