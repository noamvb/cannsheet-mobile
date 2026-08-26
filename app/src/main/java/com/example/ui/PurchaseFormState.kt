package com.example.ui

import com.example.data.Product
import com.example.data.PurchaseDefaultsState

data class PurchaseFormState(
    val date: String,
    val type: String = "",
    val name: String = "",
    val cost: String = "",
    val thc: String = "",
    val grams: String = "",
    val borrowed: Boolean = false,
    val postTax: Boolean = false,
    val saveAsDefault: Boolean = false,
    val appliedAutofillMessage: String? = null,
    val validationMessage: String? = null,
    val thcNeedsVerification: Boolean = false,
    /**
     * Barcode scanned into this form and not yet learned. Held here rather than on the
     * ViewModel so that it shares the form's lifetime: abandoning a scan and later
     * entering a different product by hand must never link the first barcode to the
     * second product.
     */
    val pendingScanGtin: String? = null,
    val pendingScanBatch: String? = null,
) {
    companion object {
        fun initial(): PurchaseFormState =
            PurchaseFormState(date = currentSubmissionDateTime().date)
    }
}

/**
 * True when a scanned lot differs from the one recorded last time, which makes the
 * remembered potency stale.
 *
 * An absent value on either side is deliberately not a change: a label that carries no
 * batch, or a product first learned before its batch was recorded, must not flag THC on
 * every single scan. Only a genuine lot-to-lot difference does.
 */
internal fun scanBatchChanged(scannedBatch: String?, rememberedBatch: String?): Boolean =
    scannedBatch != null && rememberedBatch != null && scannedBatch != rememberedBatch

// The barcode belongs to the in-progress purchase, so it deliberately survives form edits.
fun PurchaseFormState.clearedForNewSelection(): PurchaseFormState = copy(
    cost = "",
    thc = "",
    grams = "",
    borrowed = false,
    postTax = false,
    saveAsDefault = false,
    appliedAutofillMessage = null,
    validationMessage = null,
    thcNeedsVerification = false,
)

// The user's explicit detach; unlike reset(), this leaves the rest of the form intact.
fun PurchaseFormState.withoutPendingScan(): PurchaseFormState =
    copy(pendingScanGtin = null, pendingScanBatch = null)

fun PurchaseFormState.reset(): PurchaseFormState = copy(
    date = currentSubmissionDateTime().date,
    type = "",
    name = "",
    pendingScanGtin = null,
    pendingScanBatch = null,
).clearedForNewSelection()

fun PurchaseFormState.withAutofillFor(
    product: Product,
    defaultsState: PurchaseDefaultsState,
): PurchaseFormState {
    val cleared = copy(name = product.name).clearedForNewSelection()
    val savedDefault = matchingSavedDefault(
        defaultsState = defaultsState,
        type = cleared.type,
        name = product.name,
    )
    return if (savedDefault != null) {
        cleared.copy(
            cost = canonicalPurchaseNumber(savedDefault.cost),
            thc = canonicalPurchaseNumber(savedDefault.thc * 100.0),
            grams = canonicalPurchaseNumber(savedDefault.grams),
            appliedAutofillMessage = "Saved defaults applied.",
        )
    } else {
        cleared.copy(
            cost = product.cost.takeIf { it.isFinite() && it > 0.0 }
                ?.let(::canonicalPurchaseNumber)
                .orEmpty(),
            thc = catalogThcPercent(product.thc)
                ?.let(::canonicalPurchaseNumber)
                .orEmpty(),
            grams = product.grams.takeIf { it.isFinite() && it > 0.0 }
                ?.let(::canonicalPurchaseNumber)
                .orEmpty(),
        )
    }
}
