package com.example.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class ConsumptionFormHolderTest {
    private fun holder(remembered: Double? = null) = ConsumptionFormHolder(
        rememberedQuantity = { remembered },
        productType = { "Pen" },
        defaultQuantity = { if (it == "Pen") 3.0 else 2.0 },
    )

    @Test
    fun selectUsesRememberedQuantity() {
        val form = holder(2.5)
        form.select("a")
        assertEquals(ConsumptionFormState("a", "2.5"), form.state.value)
    }

    @Test
    fun selectFallsBackToTypeDefaultWhenNothingRemembered() {
        val form = ConsumptionFormHolder({ null }, { "Pen" }, { if (it == "Pen") 3.0 else 1.0 })
        form.select("a")
        assertEquals(ConsumptionFormState("a", "3"), form.state.value)
    }

    @Test
    fun selectIgnoresInvalidRememberedQuantity() {
        for (quantity in listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            val form = holder(quantity)
            form.select("a")
            assertEquals(ConsumptionFormState("a", "3"), form.state.value)
        }
    }

    @Test
    fun updateQuantityKeepsSelection() {
        val form = holder()
        form.select("a")
        form.updateQuantity("4.25")
        assertEquals(ConsumptionFormState("a", "4.25"), form.state.value)
    }

    @Test
    fun clearUsesUntypedDefault() {
        val form = holder()
        form.select("a")
        form.clear()
        assertEquals(ConsumptionFormState(null, "2"), form.state.value)
    }

    @Test
    fun clearIfSelectedOnlyClearsMatchingProduct() {
        val form = holder()
        form.select("a")
        form.clearIfSelected("b")
        assertEquals(ConsumptionFormState("a", "3"), form.state.value)
        form.clearIfSelected("a")
        assertEquals(ConsumptionFormState(null, "2"), form.state.value)
    }

    @Test
    fun selectBorrowedFormatsUses() {
        val form = holder()
        form.selectBorrowed("tmp-1", 1.50)
        assertEquals(ConsumptionFormState("tmp-1", "1.5"), form.state.value)
    }

    @Test
    fun applyRemapsReplacesOnlyMappedSelection() {
        val form = holder(2.0)
        form.select("tmp-1")
        form.applyRemaps(mapOf("tmp-9" to "x"))
        assertEquals(ConsumptionFormState("tmp-1", "2"), form.state.value)
        form.applyRemaps(mapOf("tmp-1" to "legacy-7"))
        assertEquals(ConsumptionFormState("legacy-7", "2"), form.state.value)
        form.clear()
        val cleared = form.state.value
        form.applyRemaps(mapOf("tmp-1" to "legacy-7"))
        assertEquals(cleared, form.state.value)
    }
}
