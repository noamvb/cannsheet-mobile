package com.example.ui

import com.example.data.Product
import com.example.data.PurchaseDefaultKey
import com.example.data.PurchaseDefaultValues
import com.example.data.PurchaseDefaultsState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PurchaseFormStateTest {
    @Test
    fun `cleared selection blanks values and preserves identity fields`() {
        val cleared = populatedState().clearedForNewSelection()

        assertEquals("2026-08-26", cleared.date)
        assertEquals("P", cleared.type)
        assertEquals("Blue Dream", cleared.name)
        assertEquals("", cleared.cost)
        assertEquals("", cleared.thc)
        assertEquals("", cleared.grams)
        assertFalse(cleared.borrowed)
        assertFalse(cleared.postTax)
        assertFalse(cleared.saveAsDefault)
        assertNull(cleared.appliedAutofillMessage)
        assertNull(cleared.validationMessage)
    }

    @Test
    fun `reset blanks type name and all value fields`() {
        val reset = populatedState().reset()

        assertEquals(currentSubmissionDateTime().date, reset.date)
        assertEquals("", reset.type)
        assertEquals("", reset.name)
        assertEquals("", reset.cost)
        assertEquals("", reset.thc)
        assertEquals("", reset.grams)
        assertFalse(reset.borrowed)
        assertFalse(reset.postTax)
        assertFalse(reset.saveAsDefault)
        assertNull(reset.appliedAutofillMessage)
        assertNull(reset.validationMessage)
    }

    @Test
    fun `autofill prefers a complete saved default`() {
        val product = Product("product", "Blue Dream", "P", 0, cost = 12.0, thc = 0.2, grams = 3.5)
        val defaults = PurchaseDefaultsState.Loaded(
            mapOf(
                PurchaseDefaultKey("Blue Dream", "P") to PurchaseDefaultValues(
                    cost = 42.0,
                    thc = 0.2345,
                    grams = 7.0,
                ),
            ),
        )

        val autofilled = populatedState().withAutofillFor(product, defaults)

        assertEquals("Blue Dream", autofilled.name)
        assertEquals("42", autofilled.cost)
        assertEquals("23.45", autofilled.thc)
        assertEquals("7", autofilled.grams)
        assertEquals("Saved defaults applied.", autofilled.appliedAutofillMessage)
    }

    @Test
    fun `autofill falls back to catalog values without a saved default`() {
        val product = Product("product", "Blue Dream", "P", 0, cost = 12.5, thc = 23.0, grams = 3.5)

        val autofilled = populatedState().withAutofillFor(
            product = product,
            defaultsState = PurchaseDefaultsState.Loaded(emptyMap()),
        )

        assertEquals("12.5", autofilled.cost)
        assertEquals("23", autofilled.thc)
        assertEquals("3.5", autofilled.grams)
        assertNull(autofilled.appliedAutofillMessage)
    }

    @Test
    fun `catalog autofill converts fractional thc and blanks out of range thc`() {
        val state = PurchaseFormState(date = "2026-08-26", type = "P")
        val defaults = PurchaseDefaultsState.Loaded(emptyMap())

        val fractional = state.withAutofillFor(
            Product("fraction", "Fraction", "P", 0, thc = 0.25),
            defaults,
        )
        val outOfRange = state.withAutofillFor(
            Product("invalid", "Invalid", "P", 0, thc = 101.0),
            defaults,
        )

        assertEquals("25", fractional.thc)
        assertEquals("", outOfRange.thc)
    }

    @Test
    fun `cleared selection drops a stale thc verification flag`() {
        val flagged = populatedState().copy(thcNeedsVerification = true)

        assertEquals(false, flagged.clearedForNewSelection().thcNeedsVerification)
        assertEquals(false, flagged.reset().thcNeedsVerification)
    }

    @Test
    fun `a scanned barcode survives choosing a type and is only dropped on reset`() {
        val scanned = populatedState().copy(
            thcNeedsVerification = true,
            pendingScanGtin = "00840773004481",
            pendingScanBatch = "26070000162",
        )

        val afterNewSelection = scanned.clearedForNewSelection()
        assertEquals("00840773004481", afterNewSelection.pendingScanGtin)
        assertEquals("26070000162", afterNewSelection.pendingScanBatch)
        assertEquals("", afterNewSelection.cost)
        assertEquals("", afterNewSelection.thc)
        assertEquals("", afterNewSelection.grams)
        assertFalse(afterNewSelection.borrowed)
        assertFalse(afterNewSelection.postTax)
        assertFalse(afterNewSelection.saveAsDefault)
        assertNull(afterNewSelection.appliedAutofillMessage)
        assertNull(afterNewSelection.validationMessage)
        assertFalse(afterNewSelection.thcNeedsVerification)

        val afterReset = scanned.reset()
        assertNull(afterReset.pendingScanGtin)
        assertNull(afterReset.pendingScanBatch)
    }

    @Test
    fun `explicitly detaching a barcode leaves the rest of the form untouched`() {
        val state = populatedState().copy(
            thcNeedsVerification = true,
            pendingScanGtin = "00840773004481",
            pendingScanBatch = "26070000162",
        )

        val detached = state.withoutPendingScan()

        assertNull(detached.pendingScanGtin)
        assertNull(detached.pendingScanBatch)
        assertEquals(state.date, detached.date)
        assertEquals(state.type, detached.type)
        assertEquals(state.name, detached.name)
        assertEquals(state.cost, detached.cost)
        assertEquals(state.thc, detached.thc)
        assertEquals(state.grams, detached.grams)
        assertTrue(detached.borrowed)
        assertTrue(detached.postTax)
        assertTrue(detached.saveAsDefault)
        assertEquals(state.appliedAutofillMessage, detached.appliedAutofillMessage)
        assertEquals(state.validationMessage, detached.validationMessage)
        assertTrue(detached.thcNeedsVerification)
    }

    @Test
    fun `batch change is only flagged when both lots are known and differ`() {
        assertEquals(true, scanBatchChanged("lot-2", "lot-1"))
        assertEquals(false, scanBatchChanged("lot-1", "lot-1"))
        // A label with no batch, or a product learned before its batch was recorded,
        // must not flag potency on every scan.
        assertEquals(false, scanBatchChanged(null, "lot-1"))
        assertEquals(false, scanBatchChanged("lot-1", null))
        assertEquals(false, scanBatchChanged(null, null))
    }

    private fun populatedState(): PurchaseFormState = PurchaseFormState(
        date = "2026-08-26",
        type = "P",
        name = "Blue Dream",
        cost = "42",
        thc = "23",
        grams = "7",
        borrowed = true,
        postTax = true,
        saveAsDefault = true,
        appliedAutofillMessage = "Saved defaults applied.",
        validationMessage = "Invalid",
    )
}
