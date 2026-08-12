package com.example.ui

internal fun usesToSeconds(uses: Double, secondsPerUse: Double): Double =
    com.example.domain.usesToSeconds(uses, secondsPerUse)

internal fun secondsToUses(seconds: Double, secondsPerUse: Double): Double =
    com.example.domain.secondsToUses(seconds, secondsPerUse)

internal fun formatQuantityInInputUnit(uses: Double, secondsPerUse: Double?): String =
    com.example.domain.formatQuantityInInputUnit(uses, secondsPerUse)
