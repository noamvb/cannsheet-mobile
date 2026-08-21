package com.example.nfc

import androidx.compose.runtime.Composable

/** Small Compose integration seam so Settings can provide the already-collected resolver copy. */
@Composable
fun NfcQuickLogSettingsCoordinator(resolverDescription: String) {
    NfcQuickLogSettingsSection(resolverDescription = resolverDescription)
}
