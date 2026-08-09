package com.example.data

internal fun GasProduct.toProductEntity(): Product {
    require(totalUses == null || (totalUses.isFinite() && totalUses >= 0.0)) {
        "Product $id has an invalid total uses value"
    }
    return Product(
        id = id,
        name = name,
        type = type,
        status = status,
        cost = cost ?: 0.0,
        thc = thc ?: 0.0,
        grams = grams ?: 0.0,
        productUuid = productUuid,
        totalUses = totalUses,
    )
}
