package com.example.ui

import com.example.data.Product
import com.example.data.PurchaseDefaultKey
import com.example.data.PurchaseDefaultValues
import com.example.data.PurchaseDefaultsState
import java.math.BigDecimal
import java.util.Locale

object PurchaseContentTestTags {
    const val DATE = "purchase_date"
    const val TYPE = "purchase_type"
    const val TYPE_MENU = "purchase_type_menu"
    const val NAME = "purchase_name"
    const val SUGGESTIONS = "purchase_suggestions"
    const val COST = "purchase_cost"
    const val THC = "purchase_thc"
    const val GRAMS = "purchase_grams"
    const val BORROWED = "purchase_borrowed"
    const val POST_TAX = "purchase_post_tax"
    const val SAVE_AS_DEFAULT = "purchase_save_as_default"
    const val SUBMIT = "purchase_submit"
    const val VALIDATION_ERROR = "purchase_validation_error"
    const val APPLIED_AUTOFILL = "purchase_applied_autofill"

    fun suggestion(name: String): String = "purchase_suggestion_${name.normalizedPurchaseValue()}"

    fun typeOption(type: String): String = "purchase_type_option_${type.normalizedPurchaseValue()}"
}

internal fun String.normalizedPurchaseValue(): String = trim().lowercase(Locale.ROOT)

internal fun purchaseSuggestions(
    products: List<Product>,
    selectedType: String,
    query: String,
): List<Product> {
    val normalizedType = selectedType.normalizedPurchaseValue()
    if (normalizedType.isEmpty()) return emptyList()

    val normalizedQuery = query.normalizedPurchaseValue()
    return products.asSequence()
        .filter { it.type.normalizedPurchaseValue() == normalizedType }
        .filter { it.name.normalizedPurchaseValue().isNotEmpty() }
        .filter { normalizedQuery.isEmpty() || it.name.normalizedPurchaseValue().contains(normalizedQuery) }
        .sortedWith(compareBy<Product>({ it.name.normalizedPurchaseValue() }, { it.name }, { it.id }))
        .distinctBy { it.name.normalizedPurchaseValue() }
        .take(5)
        .toList()
}

internal fun matchingSavedDefault(
    defaultsState: PurchaseDefaultsState,
    type: String,
    name: String,
): PurchaseDefaultValues? =
    (defaultsState as? PurchaseDefaultsState.Loaded)
        ?.defaults
        ?.get(PurchaseDefaultKey(name, type))

internal fun catalogThcPercent(value: Double): Double? =
    when {
        !value.isFinite() || value <= 0.0 || value > 100.0 -> null
        value <= 1.0 -> value * 100.0
        else -> value
    }

internal fun canonicalPurchaseNumber(value: Double): String =
    BigDecimal.valueOf(if (value == -0.0) 0.0 else value)
        .stripTrailingZeros()
        .toPlainString()
