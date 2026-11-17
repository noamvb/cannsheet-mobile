package com.example.ui

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.semantics.text
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.Product
import com.example.data.productStatus
import com.example.domain.ProductRunway
import com.example.domain.formatQuantityInInputUnit
import com.example.ui.theme.PlexMono
import com.example.ui.theme.tabular
import java.util.Calendar
import java.util.Date
import kotlin.math.abs

@Composable
internal fun LedgerProductRow(
    product: Product,
    isSelected: Boolean,
    pendingUses: Double,
    runway: ProductRunway?,
    lastQuantity: Double?,
    onClick: () -> Unit,
) {
    val confirmedTotal = product.totalUses?.takeIf { it.isFinite() && it >= 0.0 }
    val confirmedText = confirmedTotal?.let { formatUsageAmount(it) } ?: "—"
    val localText = if (pendingUses.isFinite() && pendingUses > 0.0) "+${formatUsageAmount(pendingUses)}" else "+0"
    val confirmedSpoken = confirmedTotal?.let { "Synced total: ${formatUsageAmount(it)} uses" } ?: "Synced: unavailable"
    val localSpoken = if (pendingUses.isFinite() && pendingUses > 0.0) "Pending: +${formatUsageAmount(pendingUses)} uses" else null

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (isSelected) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    androidx.compose.ui.graphics.Color.Transparent
                },
            )
            .clickable(onClick = onClick)
            .semantics { selected = isSelected }
            .padding(horizontal = 6.dp, vertical = 10.dp)
            .testTag(ConsumptionLedgerTestTags.productRow(product.id)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = product.name,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = "${product.productStatus.label} · ${ProductTypes.label(product.type)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "·",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "${formatQuantity(product.grams)} g",
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = PlexMono).tabular(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag(ConsumptionLedgerTestTags.remainingQuantity(product.id)),
                )
            }
            // Its own line: at 320dp the metadata row above has no room for a fourth item.
            lastQuantity?.let { quantity ->
                Text(
                    text = "Last: ${formatQuantity(quantity)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (isSelected && runway != null) {
                Text(
                    text = runwaySummaryText(runway),
                    modifier = Modifier.testTag(ConsumptionRunwayTestTags.SELECTED_PRODUCT_RUNWAY),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }

        // Confirmed count
        Text(
            text = confirmedText,
            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = PlexMono).tabular(),
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.End,
            modifier = Modifier
                .width(76.dp)
                .clearAndSetSemantics { text = AnnotatedString(confirmedSpoken) },
        )

        // Local count
        Text(
            text = localText,
            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = PlexMono).tabular(),
            color = if (pendingUses > 0.0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.End,
            modifier = Modifier
                .width(48.dp)
                .clearAndSetSemantics { if (localSpoken != null) text = AnnotatedString(localSpoken) },
        )
    }
}

@Composable
internal fun QuantitySection(
    presets: List<Double>,
    secondsPerUse: Double?,
    quantityText: String,
    onQuantityChange: (String) -> Unit,
) {
    val effectivePresets = remember(presets) {
        if (presets.isNotEmpty()) presets else listOf(1.0, 2.0, 3.0)
    }
    val currentQuantity = quantityText.toDoubleOrNull()
    var isCustomMode by rememberSaveable { mutableStateOf(false) }

    val matchingPreset = effectivePresets.firstOrNull { preset ->
        currentQuantity != null && abs(preset - currentQuantity) < 0.0001
    }
    val isCustomSelected = isCustomMode || (matchingPreset == null && quantityText.isNotBlank())

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "QUANTITY",
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = PlexMono).tabular(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = if (secondsPerUse != null) "SECONDS" else "USES",
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = PlexMono).tabular(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Bold,
            )
        }

        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(effectivePresets) { preset ->
                val isSelected = !isCustomSelected && matchingPreset == preset
                val label = if (secondsPerUse != null) {
                    formatQuantityInInputUnit(preset, secondsPerUse)
                } else {
                    formatQuantity(preset)
                }
                FilterChip(
                    selected = isSelected,
                    onClick = {
                        isCustomMode = false
                        onQuantityChange(formatQuantity(preset))
                    },
                    label = {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelMedium.copy(fontFamily = PlexMono).tabular(),
                        )
                    },
                    shape = CircleShape,
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    ),
                )
            }
            item {
                FilterChip(
                    selected = isCustomSelected,
                    onClick = {
                        isCustomMode = true
                    },
                    label = {
                        Text("Custom", style = MaterialTheme.typography.labelMedium)
                    },
                    shape = CircleShape,
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    ),
                )
            }
        }

        AnimatedVisibility(visible = isCustomSelected) {
            OutlinedTextField(
                value = quantityText,
                onValueChange = {
                    isCustomMode = true
                    onQuantityChange(it)
                },
                label = {
                    Text(if (secondsPerUse == null) "Custom quantity" else "Custom quantity (uses)")
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
                isError = quantityText.isNotBlank() &&
                    (currentQuantity == null || !currentQuantity.isFinite() || currentQuantity <= 0.0),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
internal fun DateTimeSection(
    adjustDateTime: Boolean,
    customDateMillis: Long,
    customHour: Int,
    customMinute: Int,
    nowMillisProvider: () -> Long,
    onToggleAdjustment: () -> Unit,
    onUseNow: () -> Unit,
    onChooseDate: () -> Unit,
    onChooseTime: () -> Unit,
) {
    val nowCalendar = remember(nowMillisProvider, adjustDateTime) {
        Calendar.getInstance().apply { timeInMillis = nowMillisProvider() }
    }
    val displayTime = if (adjustDateTime) {
        "${pickerDateToWire(customDateMillis)} · ${formatTimeAmPm(customHour, customMinute)}"
    } else {
        "Now · ${formatTimeAmPm(nowCalendar.get(Calendar.HOUR_OF_DAY), nowCalendar.get(Calendar.MINUTE))}"
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .heightIn(min = 48.dp)
            .clickable(onClick = onToggleAdjustment)
            .padding(vertical = 4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "Date & time",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = displayTime,
                    style = MaterialTheme.typography.bodyMedium.copy(fontFamily = PlexMono).tabular(),
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = if (adjustDateTime) "Done" else "Adjust",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        AnimatedVisibility(visible = adjustDateTime) {
            Column(
                modifier = Modifier.padding(top = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(onClick = onChooseDate, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.DateRange, contentDescription = null)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Date")
                    }
                    OutlinedButton(onClick = onChooseTime, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.AccessTime, contentDescription = null)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Time")
                    }
                }
                TextButton(onClick = onUseNow, modifier = Modifier.align(Alignment.End)) {
                    Text("Use current date & time")
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ProductPickerSheet(
    products: List<Product>,
    categories: List<String>,
    selectedCategory: String?,
    searchQuery: String,
    includeUnopened: Boolean,
    onSearchQueryChange: (String) -> Unit,
    onCategoryChange: (String?) -> Unit,
    onIncludeUnopenedChange: (Boolean) -> Unit,
    onProductSelected: (Product) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val isDark = isSystemInDarkTheme()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Choose a product", style = MaterialTheme.typography.headlineSmall)
            OutlinedTextField(
                value = searchQuery,
                onValueChange = onSearchQueryChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Search name, ID, or type") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { onSearchQueryChange("") }) {
                            Icon(Icons.Default.Close, contentDescription = "Clear search")
                        }
                    }
                },
                singleLine = true,
            )
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    FilterChip(
                        selected = selectedCategory == null,
                        onClick = { onCategoryChange(null) },
                        label = { Text("All types") },
                    )
                }
                items(categories) { category ->
                    val color = ProductTypes.categoryColor(category, isDark)
                    FilterChip(
                        selected = selectedCategory == category,
                        onClick = {
                            onCategoryChange(if (selectedCategory == category) null else category)
                        },
                        label = { Text(category) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = color.copy(alpha = 0.22f),
                        ),
                    )
                }
            }
            FilterChip(
                selected = includeUnopened,
                onClick = { onIncludeUnopenedChange(!includeUnopened) },
                label = { Text("Include unopened products") },
            )
            HorizontalDivider()
            if (products.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(160.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("No matching products")
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 480.dp),
                ) {
                    items(products, key = Product::id) { product ->
                        ListItem(
                            headlineContent = { Text(product.name) },
                            supportingContent = {
                                Text("${product.productStatus.label} · ${product.type} · ${product.id}")
                            },
                            modifier = Modifier.clickable { onProductSelected(product) },
                        )
                        HorizontalDivider()
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}
