package com.example.data.barcode

import java.util.Locale

/** Parsed contents of a GS1 element string. */
data class Gs1ScanResult(
    /** GTIN, always exactly 14 digits, left-padded with zeroes. */
    val gtin: String,
    /** GS1 AI (10) batch/lot, or null when absent. */
    val batch: String?,
    /** GS1 AI (13) packaging date as `yyyy-MM-dd`, or null when absent. */
    val packDate: String?,
)

object Gs1Barcode {
    /** Returns null when [raw] cannot be parsed into a valid GTIN. */
    fun parse(raw: String): Gs1ScanResult? {
        val input = stripSymbologyIdentifier(raw.trim())
        if (input.isEmpty()) return null

        if (input.all { it.isDigit() } && input.length in LINEAR_GTIN_LENGTHS) {
            val gtin = normalizeGtin(input) ?: return null
            return Gs1ScanResult(gtin = gtin, batch = null, packDate = null)
        }

        return if (input.startsWith('(')) {
            parseParenthesized(input)
        } else {
            parseElementString(input)
        }
    }

    /** Left-pads to 14 digits and validates the mod-10 check digit. Null if invalid. */
    fun normalizeGtin(digits: String): String? {
        if (digits.isEmpty() || digits.length > GTIN_LENGTH || !digits.all { it.isDigit() }) {
            return null
        }

        val padded = digits.padStart(GTIN_LENGTH, '0')
        var sum = 0
        for (index in GTIN_DATA_LAST_INDEX downTo 0) {
            val distanceFromRight = GTIN_DATA_LAST_INDEX - index
            val weight = if (distanceFromRight % 2 == 0) 3 else 1
            sum += padded[index].digitToInt() * weight
        }
        val expectedCheckDigit = (10 - sum % 10) % 10
        return padded.takeIf { it.last().digitToInt() == expectedCheckDigit }
    }

    private fun parseParenthesized(input: String): Gs1ScanResult? {
        val parsed = ParsedElements()
        var index = 0
        while (index < input.length) {
            if (!isParenthesizedAiAt(input, index)) return null

            val ai = input.substring(index + 1, index + 3)
            val valueStart = index + PARENTHESIZED_AI_LENGTH
            val nextAi = findNextParenthesizedAi(input, valueStart)
            val valueEnd = if (nextAi >= 0) nextAi else input.length
            val value = input.substring(valueStart, valueEnd)

            when {
                fixedLength(ai) != null -> {
                    if (value.length != fixedLength(ai) || !consumeFixed(ai, value, parsed)) {
                        return null
                    }
                }
                variableMaxLength(ai) != null -> {
                    if (!consumeVariable(ai, value, parsed)) return null
                }
                nextAi < 0 -> return parsed.toResult()
            }

            index = valueEnd
        }
        return parsed.toResult()
    }

    private fun parseElementString(input: String): Gs1ScanResult? {
        val parsed = ParsedElements()
        var index = 0
        while (index < input.length) {
            if (input[index] == GROUP_SEPARATOR) {
                index++
                continue
            }
            if (input.length - index < AI_LENGTH) return null

            val ai = input.substring(index, index + AI_LENGTH)
            if (!ai.all { it.isDigit() }) return null
            index += AI_LENGTH

            val fixedLength = fixedLength(ai)
            if (fixedLength != null) {
                if (input.length - index < fixedLength) return null
                val value = input.substring(index, index + fixedLength)
                if (!consumeFixed(ai, value, parsed)) return null
                index += fixedLength
                continue
            }

            val variableMaxLength = variableMaxLength(ai)
            if (variableMaxLength != null) {
                val separator = input.indexOf(GROUP_SEPARATOR, index)
                val valueEnd = if (separator >= 0) separator else input.length
                val value = input.substring(index, valueEnd)
                if (!consumeVariable(ai, value, parsed)) return null
                index = if (separator >= 0) separator + 1 else input.length
                continue
            }

            val separator = input.indexOf(GROUP_SEPARATOR, index)
            if (separator < 0) return parsed.toResult()
            index = separator + 1
        }
        return parsed.toResult()
    }

    private fun consumeFixed(ai: String, value: String, parsed: ParsedElements): Boolean {
        if (!value.all { it.isDigit() }) return false
        return when (ai) {
            "01" -> parsed.captureGtin(value, isPrimary = true)
            "02" -> parsed.captureGtin(value, isPrimary = false)
            "11", "12", "15", "16", "17" -> parseDate(value) != null
            "13" -> {
                val date = parseDate(value) ?: return false
                parsed.packDate = date
                true
            }
            else -> true
        }
    }

    private fun consumeVariable(ai: String, value: String, parsed: ParsedElements): Boolean {
        val maximumLength = variableMaxLength(ai) ?: return false
        if (value.isEmpty() || value.length > maximumLength) return false
        if (ai == "30" && !value.all { it.isDigit() }) return false
        if (ai == "10") parsed.batch = value
        return true
    }

    private fun fixedLength(ai: String): Int? = when (ai) {
        "00" -> 18
        "01", "02" -> 14
        "11", "12", "13", "15", "16", "17" -> 6
        "20" -> 2
        else -> null
    }

    private fun variableMaxLength(ai: String): Int? = when (ai) {
        "10", "21" -> 20
        "30" -> 8
        else -> null
    }

    private fun parseDate(value: String): String? {
        if (value.length != DATE_LENGTH || !value.all { it.isDigit() }) return null

        val year = 2000 + value.substring(0, 2).toInt()
        val month = value.substring(2, 4).toInt()
        val encodedDay = value.substring(4, 6).toInt()
        if (month !in 1..12) return null

        val day = if (encodedDay == 0) 1 else encodedDay
        if (day !in 1..daysInMonth(year, month)) return null
        return String.format(Locale.US, "%04d-%02d-%02d", year, month, day)
    }

    private fun daysInMonth(year: Int, month: Int): Int = when (month) {
        4, 6, 9, 11 -> 30
        2 -> if (isLeapYear(year)) 29 else 28
        else -> 31
    }

    private fun isLeapYear(year: Int): Boolean =
        year % 400 == 0 || (year % 4 == 0 && year % 100 != 0)

    private fun stripSymbologyIdentifier(raw: String): String =
        if (SYMBOL_IDENTIFIERS.any { raw.startsWith(it) }) raw.drop(SYMBOL_IDENTIFIER_LENGTH) else raw

    private fun isParenthesizedAiAt(input: String, index: Int): Boolean =
        index + PARENTHESIZED_AI_LENGTH <= input.length &&
            input[index] == '(' &&
            input[index + 1].isDigit() &&
            input[index + 2].isDigit() &&
            input[index + 3] == ')'

    private fun findNextParenthesizedAi(input: String, startIndex: Int): Int {
        var index = startIndex
        while (index < input.length) {
            if (isParenthesizedAiAt(input, index)) return index
            index++
        }
        return -1
    }

    private class ParsedElements {
        private var gtin: String? = null
        private var hasPrimaryGtin = false
        var batch: String? = null
        var packDate: String? = null

        fun captureGtin(value: String, isPrimary: Boolean): Boolean {
            val normalized = normalizeGtin(value) ?: return false
            if (isPrimary || !hasPrimaryGtin && gtin == null) {
                gtin = normalized
                hasPrimaryGtin = isPrimary
            }
            return true
        }

        fun toResult(): Gs1ScanResult? = gtin?.let {
            Gs1ScanResult(gtin = it, batch = batch, packDate = packDate)
        }
    }

    private val LINEAR_GTIN_LENGTHS = setOf(8, 12, 13, 14)
    private val SYMBOL_IDENTIFIERS = setOf("]d2", "]C1", "]e0", "]d1")
    private const val GROUP_SEPARATOR = '\u001D'
    private const val AI_LENGTH = 2
    private const val PARENTHESIZED_AI_LENGTH = 4
    private const val SYMBOL_IDENTIFIER_LENGTH = 3
    private const val DATE_LENGTH = 6
    private const val GTIN_LENGTH = 14
    private const val GTIN_DATA_LAST_INDEX = 12
}
