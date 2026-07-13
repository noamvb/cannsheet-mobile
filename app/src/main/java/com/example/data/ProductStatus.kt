package com.example.data

enum class ProductStatus(val code: Int, val label: String) {
    ACTIVE(0, "Active"),
    FINISHED(1, "Finished"),
    UNOPENED(2, "Unopened"),
    UNKNOWN(Int.MIN_VALUE, "Unknown");

    val isSelectable: Boolean
        get() = this == ACTIVE || this == UNOPENED

    companion object {
        fun fromCode(code: Int): ProductStatus = entries.firstOrNull { it.code == code } ?: UNKNOWN
    }
}

val Product.productStatus: ProductStatus
    get() = ProductStatus.fromCode(status)
