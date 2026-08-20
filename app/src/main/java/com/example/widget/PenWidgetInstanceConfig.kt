package com.example.widget

/**
 * Per-instance widget configuration. Every field has a default that reproduces
 * the pre-configuration behaviour, because `configuration_optional` means the
 * activity may never have run for a given instance.
 */
data class PenWidgetInstanceConfig(
    val pinnedProductId: String? = null,
    val discreet: Boolean = false,
    val stepSecondsOverride: Int? = null,
) {
    companion object {
        val DEFAULT = PenWidgetInstanceConfig()
    }
}
