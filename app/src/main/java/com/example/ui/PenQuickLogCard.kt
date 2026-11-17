package com.example.ui

import androidx.compose.ui.semantics.selected
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.data.productStatus
import com.example.domain.PenQuickLogState
import com.example.domain.ProductRunway
import com.example.domain.formatQuantityInInputUnit

internal object PenQuickLogTestTags {
    const val CARD = "pen-quick-log-card"
    const val CHOOSE_CART = "pen-quick-log-choose-cart"
    const val SWAP_CART = "pen-quick-log-swap-cart"
    const val RUNWAY = "pen-quick-log-runway"

    fun quickLogChip(position: Int) = "pen-quick-log-chip-$position"
}

@Composable
internal fun PenQuickLogCard(
    state: PenQuickLogState,
    onQuickLogPen: (Double) -> Unit,
    onChooseCart: () -> Unit,
    runwayByProductId: Map<String, ProductRunway> = emptyMap(),
) {
    when (state) {
        PenQuickLogState.Unavailable -> Unit
        PenQuickLogState.NoCartLoaded -> {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(PenQuickLogTestTags.CARD),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text("Which cart is in the battery?", style = MaterialTheme.typography.titleMedium)
                    Button(
                        onClick = onChooseCart,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag(PenQuickLogTestTags.CHOOSE_CART),
                    ) {
                        Text("Choose pen cart")
                    }
                }
            }
        }

        is PenQuickLogState.Loaded -> {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(PenQuickLogTestTags.CARD),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(state.product.name, style = MaterialTheme.typography.titleMedium)
                            Text(
                                state.syncedUses?.let {
                                    "${state.product.productStatus.label} · synced ${formatUsageAmount(it)} uses"
                                } ?: "${state.product.productStatus.label} · synced unavailable",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            if (state.pendingUses > 0.0) {
                                Text(
                                    "Pending: +${formatUsageAmount(state.pendingUses)} uses",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            runwayByProductId[state.product.id]?.let { runway ->
                                Text(
                                    runwaySummaryText(runway),
                                    modifier = Modifier.testTag(PenQuickLogTestTags.RUNWAY),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                        TextButton(
                            onClick = onChooseCart,
                            modifier = Modifier.testTag(PenQuickLogTestTags.SWAP_CART),
                        ) {
                            Text("Swap cart")
                        }
                    }
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(state.presetUses) { preset ->
                            val position = state.presetUses.indexOf(preset) + 1
                            FilterChip(
                                selected = false,
                                onClick = { onQuickLogPen(preset) },
                                label = {
                                    Text(formatQuantityInInputUnit(preset, state.secondsPerUse))
                                },
                                modifier = Modifier.testTag(
                                    PenQuickLogTestTags.quickLogChip(position),
                                ),
                            )
                        }
                    }
                    Text(
                        "${formatQuantityInInputUnit(1.0, state.secondsPerUse)} = 1 use",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}
