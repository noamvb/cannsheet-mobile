package com.example.ui

import com.noamv.localllm.contract.InsightRequest
import java.math.BigDecimal

/**
 * Fail-closed validation for the legacy version-one Insights narrative.
 *
 * Prompt safety flags are instructions to a probabilistic model, not enforcement. This
 * validator is the client-owned boundary before generated text can become visible or enter
 * the screen-memory cache. Version two adds sentence-to-fact citations; version one can at
 * least reject unsupported numbers and unsafe language deterministically.
 */
internal object CannsheetNarrativeValidator {
    enum class Rejection {
        EMPTY,
        TOO_LONG,
        CONTROL_OR_BIDI,
        PROMPT_OR_REFUSAL,
        HEALTH_OR_CAUSAL,
        PROJECTION,
        UNGROUNDED_NUMBER,
        UNSAFE_NUMERIC_SYNTAX,
        UNSAFE_LANGUAGE,
    }

    sealed interface Verdict {
        data class Accept(val text: String) : Verdict
        data class Reject(val reason: Rejection, val detail: String? = null) : Verdict
    }

    fun validate(raw: String, request: InsightRequest): Verdict {
        scanCodePoints(raw)?.let { rejection -> return Verdict.Reject(rejection) }
        if (raw.length > MAX_RAW_CHARACTERS) return Verdict.Reject(Rejection.TOO_LONG)

        val text = raw.replace(WHITESPACE, " ").trim()
        if (text.isEmpty()) return Verdict.Reject(Rejection.EMPTY)
        if (text.split(' ').count { it.isNotEmpty() } > request.maxWords) {
            return Verdict.Reject(Rejection.TOO_LONG)
        }

        val lower = text.lowercase()
            .replace('\u2018', '\'')
            .replace('\u2019', '\'')
        if (FORBIDDEN_NUMERIC_SYNTAX.any { it.containsMatchIn(lower) }) {
            return Verdict.Reject(Rejection.UNSAFE_NUMERIC_SYNTAX)
        }
        val unsupportedCurrency = currencyNumbers(lower) - currencyNumbers(request)
        if (unsupportedCurrency.isNotEmpty()) {
            return Verdict.Reject(
                Rejection.UNSAFE_NUMERIC_SYNTAX,
                unsupportedCurrency.sorted().joinToString(","),
            )
        }
        (PROMPT_OR_REFUSAL_MARKERS.firstOrNull(lower::contains))?.let { marker ->
            return Verdict.Reject(Rejection.PROMPT_OR_REFUSAL, marker)
        }
        if (request.safety.forbidHealthClaims) {
            (HEALTH_OR_CAUSAL_MARKERS.firstOrNull(lower::contains))?.let { marker ->
                return Verdict.Reject(Rejection.HEALTH_OR_CAUSAL, marker)
            }
        }
        (PROJECTION_MARKERS.firstOrNull(lower::contains))?.let { marker ->
            return Verdict.Reject(Rejection.PROJECTION, marker)
        }

        if (request.safety.forbidNewNumbers) {
            val grounded = groundedNumbers(request)
            val unsupported = mentionedNumbers(text) - grounded
            if (unsupported.isNotEmpty()) {
                return Verdict.Reject(
                    Rejection.UNGROUNDED_NUMBER,
                    unsupported.sorted().joinToString(","),
                )
            }
        }

        val allowedWords = SAFE_OUTPUT_WORDS + NUMBER_WORDS
        val outputWords = WORD.findAll(lower).map { it.value }.toList()
        if (outputWords.isEmpty()) return Verdict.Reject(Rejection.UNSAFE_LANGUAGE)
        val unsupportedWords = outputWords.asSequence()
            .filterNot(allowedWords::contains)
            .toSortedSet()
        if (unsupportedWords.isNotEmpty()) {
            return Verdict.Reject(
                Rejection.UNSAFE_LANGUAGE,
                unsupportedWords.joinToString(","),
            )
        }
        return Verdict.Accept(text)
    }

    fun canAppend(currentLength: Int, additionalLength: Int): Boolean =
        currentLength in 0..MAX_RAW_CHARACTERS &&
            additionalLength >= 0 &&
            additionalLength <= MAX_RAW_CHARACTERS - currentLength

    private fun groundedNumbers(request: InsightRequest): Set<String> = buildList {
        request.period?.let { period ->
            add(period.label)
        }
        request.facts.forEach { fact ->
            add(fact.label)
            add(fact.value)
            fact.note?.let(::add)
        }
    }.flatMap { value -> NUMERAL.findAll(value).map { normalise(it.value) }.toList() }.toSet()

    private fun currencyNumbers(text: String): Set<String> = CURRENCY_NUMERAL.findAll(text)
        .map { match -> normalise(match.groupValues[1]) }
        .toSet()

    private fun currencyNumbers(request: InsightRequest): Set<String> = request.facts
        .flatMap { fact -> listOfNotNull(fact.label, fact.value, fact.note) }
        .flatMap(::currencyNumbers)
        .toSet()

    private fun mentionedNumbers(text: String): Set<String> = buildSet {
        NUMERAL.findAll(text).forEach { match -> add(normalise(match.value)) }
        val words = WORD.findAll(text.lowercase().replace('-', ' ')).map { it.value }.toList()
        var index = 0
        while (index < words.size) {
            val parsed = parseNumberWords(words, index)
            if (parsed == null) {
                index += 1
            } else {
                add(parsed.first.toString())
                index = parsed.second
            }
        }
    }

    /** Returns the parsed value and exclusive end index for one English number phrase. */
    private fun parseNumberWords(words: List<String>, start: Int): Pair<Long, Int>? {
        var index = start
        var total = 0L
        var current = 0L
        var consumedNumber = false
        while (index < words.size) {
            val word = words[index]
            when {
                word in SMALL_NUMBERS -> {
                    current += SMALL_NUMBERS.getValue(word)
                    consumedNumber = true
                }
                word in TENS -> {
                    current += TENS.getValue(word)
                    consumedNumber = true
                }
                word in ORDINALS -> {
                    current += ORDINALS.getValue(word)
                    consumedNumber = true
                }
                word == "hundred" && consumedNumber -> current = current.coerceAtLeast(1L) * 100L
                word == "thousand" && consumedNumber -> {
                    total += current.coerceAtLeast(1L) * 1_000L
                    current = 0L
                }
                word == "and" && consumedNumber && words.getOrNull(index + 1).isNumberWord() -> {
                    index += 1
                    continue
                }
                else -> break
            }
            index += 1
        }
        return if (consumedNumber) total + current to index else null
    }

    private fun String?.isNumberWord(): Boolean =
        this != null && (this in SMALL_NUMBERS || this in TENS || this in ORDINALS)

    private fun normalise(raw: String): String = runCatching {
        BigDecimal(raw.replace(",", "")).stripTrailingZeros().toPlainString()
    }.getOrDefault(raw)

    /** English-only v1 output rejects hidden formatting and every non-ASCII numeric script. */
    private fun scanCodePoints(raw: String): Rejection? {
        var offset = 0
        while (offset < raw.length) {
            val codePoint = Character.codePointAt(raw, offset)
            val type = Character.getType(codePoint)
            if ((type == Character.CONTROL.toInt() && codePoint !in SAFE_WHITESPACE) ||
                type == Character.FORMAT.toInt()
            ) {
                return Rejection.CONTROL_OR_BIDI
            }
            if (type in UNICODE_NUMBER_TYPES && codePoint !in ASCII_DIGITS) {
                return Rejection.UNGROUNDED_NUMBER
            }
            if (codePoint > ASCII_MAX && codePoint !in SAFE_TYPOGRAPHIC_PUNCTUATION) {
                return Rejection.UNSAFE_LANGUAGE
            }
            offset += Character.charCount(codePoint)
        }
        return null
    }

    private const val MAX_RAW_CHARACTERS = 2_000
    private val WHITESPACE = Regex("\\s+")
    private val NUMERAL = Regex(
        """(?:[+-](?=\d))?\d[\d,]*(?:\.\d+)?""",
    )
    private val CURRENCY_NUMERAL = Regex("""\$\s*([+-]?\d[\d,]*(?:\.\d+)?)""")
    private val FORBIDDEN_NUMERIC_SYNTAX = listOf(
        Regex("""(?:[a-z]\d|\d[a-z])"""),
        Regex("""\d{4}-\d{2}-\d{2}"""),
        Regex("""\d\s*[%/:]\s*\d?"""),
        Regex("""\d\s*[+*\-/]\s*\d"""),
        Regex("""\d[\d,.]*\s+to\s+[+-]?\d"""),
        Regex("""between\s+[+-]?\d[\d,.]*\s+and\s+[+-]?\d"""),
    )
    private val WORD = Regex("""[a-z]+""")
    private val SAFE_WHITESPACE = setOf('\n'.code, '\r'.code, '\t'.code)
    private const val ASCII_MAX = 0x7F
    private val ASCII_DIGITS = '0'.code..'9'.code
    private val UNICODE_NUMBER_TYPES = setOf(
        Character.DECIMAL_DIGIT_NUMBER.toInt(),
        Character.LETTER_NUMBER.toInt(),
        Character.OTHER_NUMBER.toInt(),
    )
    private val SAFE_TYPOGRAPHIC_PUNCTUATION = setOf(0x2018, 0x2019)

    private val PROMPT_OR_REFUSAL_MARKERS = listOf(
        "as an ai", "language model", "i cannot", "i can't", "i am unable", "i'm unable",
        "ai assistant", "an ai", "i am ai", "i'm ai",
        "system prompt", "system instruction", "facts:", "subject:", "period:",
        "insightrequest", "insighttask", "safety policy", "forbidnewnumbers",
        "summarise these", "summarize these",
    )
    private val HEALTH_OR_CAUSAL_MARKERS = listOf(
        "diagnos", "disease", "disorder", "syndrome", "medical", "medicine", "treat",
        "cure", "therapy", "therapeutic", "healthy", "unhealthy", "safe to", "unsafe",
        "anxiety", "depression", "see a doctor", "consult a", "you should", "recommend",
        "caused", "causes", "because of", "led to", "results in", "improves your",
        "improve", "reduces your", "increases your", "reliev", "pain", "sleep",
    )
    private val PROJECTION_MARKERS = listOf(
        "runway", "days remaining", "days left", "will last", "will spend", "forecast",
        "projected", "projection", "at this rate", "on track", "expected to", "likely to",
        "next month", "future spending", "run out", "remaining", "supply", "could last",
        "last for", "enough for", "soon", "future", "will ",
    )

    private val SAFE_OUTPUT_WORDS = setOf(
        "a", "across", "activity", "afternoon", "all", "also", "an", "and", "any", "are",
        "as", "at", "based", "both", "but", "by", "cannabis", "completeness", "consumption", "cost",
        "costs", "currently", "data", "day", "days", "distinct", "during", "each", "edible",
        "entries", "entry", "evening", "flower", "for", "frequent", "frequently", "friday",
        "from", "had", "has", "have", "in", "incomplete", "is", "it", "joint", "keef",
        "last", "logged", "monday", "morning", "most", "night", "of", "on", "open",
        "or", "over", "own", "pen", "product", "products", "purchase", "purchases", "range",
        "recorded", "records", "saturday", "shatter", "shows", "since", "spend", "spending",
        "sunday", "that", "the", "there", "these", "this", "those", "thursday", "tie", "tied",
        "time", "to", "total", "tuesday", "type", "unopened", "used", "was", "wednesday", "weekday",
        "were", "with", "you", "your",
    )

    private val SMALL_NUMBERS = mapOf(
        "zero" to 0L, "one" to 1L, "two" to 2L, "three" to 3L, "four" to 4L,
        "five" to 5L, "six" to 6L, "seven" to 7L, "eight" to 8L, "nine" to 9L,
        "ten" to 10L, "eleven" to 11L, "twelve" to 12L, "thirteen" to 13L,
        "fourteen" to 14L, "fifteen" to 15L, "sixteen" to 16L, "seventeen" to 17L,
        "eighteen" to 18L, "nineteen" to 19L,
    )
    private val TENS = mapOf(
        "twenty" to 20L, "thirty" to 30L, "forty" to 40L, "fifty" to 50L,
        "sixty" to 60L, "seventy" to 70L, "eighty" to 80L, "ninety" to 90L,
    )
    private val ORDINALS = mapOf(
        "first" to 1L, "second" to 2L, "third" to 3L, "fourth" to 4L,
        "fifth" to 5L, "sixth" to 6L, "seventh" to 7L, "eighth" to 8L,
        "ninth" to 9L, "tenth" to 10L,
    )
    private val NUMBER_WORDS = SMALL_NUMBERS.keys + TENS.keys + ORDINALS.keys +
        setOf("hundred", "thousand")
}
