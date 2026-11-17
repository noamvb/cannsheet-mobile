package com.example.ui

import com.example.data.Product

data class ConsumptionFormState(
    val selectedProductId: String? = null,
    val quantityText: String = "1",
)

data class RecentProduct(
    val product: Product,
    val lastQuantity: Double,
)

data class PurchaseFeedback(
    val message: String,
)
