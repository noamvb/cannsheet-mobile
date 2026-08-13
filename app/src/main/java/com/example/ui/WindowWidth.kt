package com.example.ui

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Material 3 width breakpoints derived locally so the app can adapt without
 * adding another Compose artifact during the v1.3 release cycle.
 */
enum class WindowWidth {
    COMPACT,
    MEDIUM,
    EXPANDED,
}

fun windowWidthFor(maxWidth: Dp): WindowWidth = when {
    maxWidth < 600.dp -> WindowWidth.COMPACT
    maxWidth < 840.dp -> WindowWidth.MEDIUM
    else -> WindowWidth.EXPANDED
}
