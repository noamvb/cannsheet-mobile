package com.example.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.DailyActivityDto
import com.example.data.HistoryFilters
import com.example.data.InsightsRange
import com.example.data.InsightsResponseDto
import com.example.ui.theme.tabular
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Calendar
import java.util.TimeZone

@Composable
internal fun MetricGrid(data: InsightsResponseDto) {
    Column {
        LedgerValueRow("Active days", data.overview.activeDayCount.toString())
        LedgerValueRow("Products used", data.overview.distinctProductCount.toString())
        LedgerValueRow("Days since last log", data.overview.daysSinceLastLog?.toString() ?: "—")
    }
}

@Composable
internal fun HeadlineFigure(data: InsightsResponseDto, range: InsightsRange) {
    val headlineStyle = MaterialTheme.typography.displaySmall.tabular()
    val days = when (range) {
        is InsightsRange.LastDays -> range.days
        is InsightsRange.Default -> 180
        else -> data.range.dayCount
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text("CONSUMPTION · $days DAYS", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            "${formatWholeNumber(data.overview.logCount)} logs",
            modifier = Modifier.testTag("insights-headline-number")
                .semantics {
                    this[INSIGHTS_FONT_FEATURE_SETTINGS] = headlineStyle.fontFeatureSettings.orEmpty()
                },
            style = headlineStyle,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text("— vs previous period", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun SectionCard(title: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title.uppercase(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        content()
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
private fun LedgerValueRow(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 42.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium.tabular(), textAlign = androidx.compose.ui.text.style.TextAlign.End)
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

internal val INSIGHTS_FONT_FEATURE_SETTINGS = SemanticsPropertyKey<String>("FontFeatureSettings")

@Composable
internal fun NativeBarChart(
    values: List<Pair<String, Int>>,
    description: String,
    valueLabel: (Int) -> String = { formatWholeNumber(it) },
    minimumCellWidth: androidx.compose.ui.unit.Dp = 48.dp,
) {
    if (values.isEmpty()) {
        Text("No data")
        return
    }
    val max = values.maxOf { it.second }.coerceAtLeast(1)
    val chartDescription = "$description: " + values.joinToString("; ") {
        "${it.first}, ${valueLabel(it.second)}"
    }
    var selected by remember(values) { mutableStateOf<Pair<String, Int>?>(null) }
    Column {
        selected?.let { Text("${it.first}: ${valueLabel(it.second)}") }
        BoxWithConstraints(Modifier.fillMaxWidth()) {
        val chartWidth = maxOf(maxWidth, minimumCellWidth * values.size)
        Box(
            Modifier.fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .semantics { contentDescription = chartDescription }
                .testTag("insights-chart-${description.substringBefore(' ').lowercase()}"),
        ) {
            Row(
                Modifier.width(chartWidth).heightIn(min = 130.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.Bottom,
            ) {
                values.forEachIndexed { index, (label, value) ->
                    Column(
                        Modifier.width(minimumCellWidth)
                            .clickable { selected = label to value }
                            .semantics { contentDescription = "$description, $label, ${valueLabel(value)}" },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Bottom,
                    ) {
                        Text(
                            valueLabel(value),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.labelSmall.tabular(),
                        )
                        Box(
                            Modifier
                                .width(24.dp)
                                .height((20 + 80 * value / max).dp)
                                .clip(RoundedCornerShape(topStart = 5.dp, topEnd = 5.dp))
                                .background(
                                    MaterialTheme.colorScheme.primary.copy(
                                        alpha = if (index == values.lastIndex) 1f else 0.8f,
                                    ),
                                ),
                        )
                        Text(
                            label,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
        }
        }
    }
}

@Composable
internal fun HourHeatmap(values: Map<Int, Int>) {
    val max = values.values.maxOrNull()?.coerceAtLeast(1) ?: 1
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        (0 until 24).chunked(6).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                row.forEach { hour ->
                    val value = values[hour] ?: 0
                    val alpha = 0.15f + (0.85f * value / max)
                    Box(
                        Modifier
                            .weight(1f)
                            .height(42.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = alpha))
                            .semantics { contentDescription = "$hour:00, $value logs" },
                        contentAlignment = Alignment.Center,
                    ) { Text(hour.toString(), style = MaterialTheme.typography.labelSmall) }
                }
            }
        }
        val peak = values.entries
            .sortedWith(compareByDescending<Map.Entry<Int, Int>> { it.value }.thenBy { it.key })
            .firstOrNull()
        Text("Most active hour: ${peak?.key ?: 0}:00 (${peak?.value ?: 0} logs)")
    }
}

@Composable
internal fun RangeChips(
    selected: InsightsRange,
    onSelect: (InsightsRange) -> Unit,
    onCustom: () -> Unit,
) {
    val choices = listOf(
        Triple("30d", selected == InsightsRange.LastDays(30), { onSelect(InsightsRange.LastDays(30)) }),
        Triple("90d", selected == InsightsRange.LastDays(90), { onSelect(InsightsRange.LastDays(90)) }),
        Triple("180d", selected is InsightsRange.Default, { onSelect(InsightsRange.Default) }),
        Triple("All", selected is InsightsRange.All, { onSelect(InsightsRange.All) }),
        Triple(
            "Custom",
            selected is InsightsRange.Custom ||
                (selected is InsightsRange.LastDays && selected.days !in setOf(30, 90)),
            onCustom,
        ),
    )
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth < 320.dp) {
            // An expanded pane can be forced into a very narrow window (for example by
            // multi-window resizing). Weighted segments then have less room than their
            // minimum touch targets, making the segmented row's measure pass unstable.
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                choices.forEach { (label, isSelected, action) ->
                    FilterChip(
                        selected = isSelected,
                        onClick = action,
                        modifier = Modifier.testTag("insights-range-$label"),
                        label = { Text(label, maxLines = 1, style = MaterialTheme.typography.labelSmall) },
                    )
                }
            }
        } else {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                choices.forEachIndexed { index, (label, isSelected, action) ->
                    SegmentedButton(
                        selected = isSelected,
                        onClick = action,
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = choices.size),
                        modifier = Modifier.weight(1f).testTag("insights-range-$label"),
                        label = { Text(label, maxLines = 1, style = MaterialTheme.typography.labelSmall) },
                    )
                }
            }
        }
    }
}

@Composable
internal fun CustomRangeDialog(onDismiss: () -> Unit, onApply: (InsightsRange.Custom) -> Unit) {
    var from by rememberSaveable { mutableStateOf("") }
    var to by rememberSaveable { mutableStateOf("") }
    val valid = Regex("""\d{4}-\d{2}-\d{2}""")
    val validSpan = valid.matches(from) && valid.matches(to) &&
        from <= to && dateSpanInclusive(from, to) in 1..3660
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Custom date range") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Use inclusive analytics dates in YYYY-MM-DD format.")
                OutlinedTextField(from, { from = it }, label = { Text("From") }, singleLine = true)
                OutlinedTextField(to, { to = it }, label = { Text("To") }, singleLine = true)
            }
        },
        confirmButton = {
            TextButton(
                enabled = validSpan,
                onClick = { onApply(InsightsRange.Custom(from, to)) },
            ) { Text("Apply") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

internal fun bucketActivity(days: List<DailyActivityDto>): List<Pair<String, Int>> {
    if (days.size <= 31) return days.map { it.date.substring(5) to it.logCount }
    if (days.size <= 366) {
        return days.groupBy { mondayFor(it.date) }
            .map { (week, values) -> week.substring(5) to values.sumOf(DailyActivityDto::logCount) }
    }
    return days.groupBy { it.date.substring(0, 7) }
        .map { (month, values) -> month to values.sumOf(DailyActivityDto::logCount) }
}

internal fun calculateStreaks(days: List<DailyActivityDto>): Pair<Int, Int> {
    var longest = 0
    var running = 0
    days.forEach {
        running = if (it.logCount > 0) running + 1 else 0
        longest = maxOf(longest, running)
    }
    var current = 0
    for (day in days.asReversed()) {
        if (day.logCount <= 0) break
        current++
    }
    return current to longest
}

internal fun hasHistoryFilters(filters: HistoryFilters) =
    !filters.from.isNullOrBlank() ||
        !filters.to.isNullOrBlank() ||
        !filters.productUuid.isNullOrBlank() ||
        !filters.productId.isNullOrBlank() ||
        !filters.type.isNullOrBlank() ||
        !filters.query.isNullOrBlank()

internal fun cad(cents: Long): String = formatCadCents(cents)

internal fun analyticsTimeZoneLabel(timeZone: String): String =
    timeZone.substringAfterLast('/').replace('_', ' ').ifBlank { timeZone }

internal fun formatChartMonth(month: String): String =
    if (Regex("""\d{4}-\d{2}""").matches(month)) "${month.takeLast(2)}/${month.take(4)}" else month

internal fun formatWholeNumber(value: Int): String =
    NumberFormat.getIntegerInstance(Locale.CANADA).format(value)

internal fun formatThc(raw: Double?, quality: String): String = when (quality) {
    "RECORDED_PERCENT" -> "THC: ${raw?.let(::formatDecimal) ?: "unknown"}%"
    // Older cached analytics classified normal Sheets percentage values (for example,
    // 0.75 for 75%) as ambiguous. Keep those caches readable after the backend fix.
    "AMBIGUOUS_SCALE" -> if (raw != null && raw > 0.0 && raw <= 1.0) {
        "THC: ${formatDecimal(raw * 100.0)}%"
    } else {
        "THC: needs scale review"
    }
    "INVALID" -> "THC: invalid source value"
    else -> "THC: unknown"
}

internal fun formatDecimal(value: Double): String =
    if (value % 1.0 == 0.0) value.toInt().toString()
    else "%.2f".format(Locale.CANADA, value).trimEnd('0').trimEnd('.')

internal fun formatTimestamp(epochMillis: Long): String =
    SimpleDateFormat("MMM d, h:mm a", Locale.CANADA).apply {
        timeZone = TimeZone.getTimeZone("America/Toronto")
    }.format(Date(epochMillis))

private fun mondayFor(date: String): String {
    val formatter = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
        isLenient = false
    }
    val calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
        time = requireNotNull(formatter.parse(date))
        val offset = (get(Calendar.DAY_OF_WEEK) + 5) % 7
        add(Calendar.DAY_OF_MONTH, -offset)
    }
    return formatter.format(calendar.time)
}

internal const val MAX_RUNWAY_ROWS = 5
internal const val MAX_RUNWAY_DIAGNOSTICS = 5

private fun dateSpanInclusive(from: String, to: String): Int = runCatching {
    val formatter = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
        isLenient = false
    }
    val start = requireNotNull(formatter.parse(from)).time
    val end = requireNotNull(formatter.parse(to)).time
    ((end - start) / 86_400_000L).toInt() + 1
}.getOrDefault(0)
