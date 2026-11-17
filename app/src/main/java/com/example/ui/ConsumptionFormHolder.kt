package com.example.ui

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/** Holds the Log form selection and quantity across product lifecycle changes. */
internal class ConsumptionFormHolder(
    private val rememberedQuantity: (productId: String) -> Double?,
    private val productType: (productId: String) -> String?,
    private val defaultQuantity: (productType: String?) -> Double,
) {
    private val _state = MutableStateFlow(ConsumptionFormState())
    val state: StateFlow<ConsumptionFormState> = _state

    fun select(productId: String) {
        val remembered = rememberedQuantity(productId)?.takeIf { it.isFinite() && it > 0.0 }
        val suggested = remembered ?: defaultQuantity(productType(productId))
        _state.value = ConsumptionFormState(productId, formatQuantity(suggested))
    }

    fun updateQuantity(text: String) {
        _state.update { it.copy(quantityText = text) }
    }

    fun clear() {
        _state.value = ConsumptionFormState(quantityText = formatQuantity(defaultQuantity(null)))
    }

    fun clearIfSelected(productId: String) {
        if (_state.value.selectedProductId == productId) clear()
    }

    fun selectBorrowed(tempId: String, uses: Double) {
        _state.value = ConsumptionFormState(tempId, formatQuantity(uses))
    }

    fun applyRemaps(idMappings: Map<String, String>) {
        val remappedId = _state.value.selectedProductId?.let(idMappings::get)
        if (remappedId != null) {
            _state.update { it.copy(selectedProductId = remappedId) }
        }
    }
}
