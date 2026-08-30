package com.example.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.R
import com.example.data.CannsheetGraph
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class PenWidgetInstanceUiModel(
    val appWidgetId: Int,
    val label: String,
    val config: PenWidgetInstanceConfig,
)

internal object WidgetSettingsTestTags {
    const val SECTION = "widget-settings-section"
    const val DEFAULT_STEP_ROW = "widget-settings-default-step-row"
    const val INSTANCES_LIST = "widget-settings-instances-list"
    const val INSTANCES_EMPTY = "widget-settings-instances-empty"

    fun defaultStepOption(seconds: Int) = "widget-settings-default-step-$seconds"
    fun instanceRow(appWidgetId: Int) = "widget-settings-instance-$appWidgetId"
    fun instanceStepOption(appWidgetId: Int, seconds: Int?) =
        "widget-settings-instance-$appWidgetId-step-${seconds ?: "inherit"}"
}

@StringRes
internal fun penWidgetSizeLabelRes(
    widthDp: Int,
    heightDp: Int,
    compactBreakpointHeightDp: Int,
): Int = when {
    heightDp in 1 until compactBreakpointHeightDp -> R.string.settings_widget_size_compact
    widthDp >= PenWidgetSizing.FULL_SCALE_WIDTH_DP &&
        heightDp >= PenWidgetSizing.FULL_SCALE_HEIGHT_DP -> R.string.settings_widget_size_large
    else -> R.string.settings_widget_size_base
}

internal suspend fun loadPenWidgetInstances(
    context: Context,
    configRepository: PenWidgetConfigRepository,
): List<PenWidgetInstanceUiModel> = withContext(Dispatchers.IO) {
    val appContext = context.applicationContext
    val manager = AppWidgetManager.getInstance(appContext)
    val component = ComponentName(appContext, PenConsumptionWidgetProvider::class.java)
    val appWidgetIds = manager.getAppWidgetIds(component)
    if (appWidgetIds.isEmpty()) return@withContext emptyList()

    val graph = CannsheetGraph.get(appContext)
    val products = graph.repository.allProducts.first()
    val productsById = products.associateBy { it.id }

    val compactBreakpointHeightDp = (
        appContext.resources.getDimension(R.dimen.widget_compact_breakpoint_height) /
            appContext.resources.displayMetrics.density
    ).toInt()

    val followLoadedCartText = appContext.getString(R.string.pen_widget_follow_loaded_cart)

    appWidgetIds.map { appWidgetId ->
        val config = configRepository.read(appWidgetId)
        val options = manager.getAppWidgetOptions(appWidgetId)
        val widthDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0)
        val heightDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0)
        val productName = config.pinnedProductId?.let { id -> productsById[id]?.name }
            ?: followLoadedCartText
        val sizeWordRes = penWidgetSizeLabelRes(
            widthDp = widthDp,
            heightDp = heightDp,
            compactBreakpointHeightDp = compactBreakpointHeightDp,
        )
        val sizeWord = appContext.getString(sizeWordRes)
        val label = appContext.getString(
            R.string.settings_widget_instance_label,
            productName,
            sizeWord,
            widthDp,
            heightDp,
        )
        PenWidgetInstanceUiModel(
            appWidgetId = appWidgetId,
            label = label,
            config = config,
        )
    }
}

@Composable
internal fun WidgetSettingsCoordinator(
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val configRepository = remember { PenWidgetConfigRepository(context) }
    val defaultStepSeconds by configRepository.defaultStepSecondsFlow()
        .collectAsState(initial = STEP_SECONDS)
    val scope = rememberCoroutineScope()
    var instances by remember { mutableStateOf<List<PenWidgetInstanceUiModel>>(emptyList()) }
    var refreshTrigger by remember { mutableIntStateOf(0) }
    var resumeTick by remember { mutableIntStateOf(0) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) resumeTick++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(refreshTrigger, resumeTick) {
        instances = loadPenWidgetInstances(context, configRepository)
    }

    WidgetSettingsSection(
        defaultStepSeconds = defaultStepSeconds,
        instances = instances,
        onDefaultStepSecondsChanged = { seconds ->
            scope.launch {
                withContext(Dispatchers.IO) {
                    configRepository.writeDefaultStepSeconds(seconds)
                }
                PenWidgetUpdater.updateAll(context)
                refreshTrigger++
            }
        },
        onInstanceStepSecondsChanged = { appWidgetId, secondsOverride ->
            val currentInstance = instances.firstOrNull { it.appWidgetId == appWidgetId }
            val currentConfig = currentInstance?.config ?: PenWidgetInstanceConfig.DEFAULT
            val newConfig = currentConfig.copy(stepSecondsOverride = secondsOverride)
            instances = instances.map {
                if (it.appWidgetId == appWidgetId) it.copy(config = newConfig) else it
            }
            scope.launch {
                withContext(Dispatchers.IO) {
                    configRepository.write(appWidgetId, newConfig)
                    PenWidgetRuntime.withSerialized {
                        PenWidgetUpdater.update(context, appWidgetId)
                    }
                }
                refreshTrigger++
            }
        },
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WidgetSettingsSection(
    defaultStepSeconds: Int,
    instances: List<PenWidgetInstanceUiModel>,
    onDefaultStepSecondsChanged: (Int) -> Unit,
    onInstanceStepSecondsChanged: (Int, Int?) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag(WidgetSettingsTestTags.SECTION),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(R.string.settings_widgets_title),
            style = MaterialTheme.typography.titleLarge,
        )
        Text(
            text = stringResource(R.string.settings_widget_step_default),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = stringResource(R.string.settings_widget_step_default_hint),
            style = MaterialTheme.typography.bodySmall,
        )
        val defaultOptions = listOf(5, STEP_SECONDS, 30)
        SingleChoiceSegmentedButtonRow(
            modifier = Modifier
                .fillMaxWidth()
                .testTag(WidgetSettingsTestTags.DEFAULT_STEP_ROW),
        ) {
            defaultOptions.forEachIndexed { index, option ->
                SegmentedButton(
                    selected = defaultStepSeconds == option,
                    onClick = { onDefaultStepSecondsChanged(option) },
                    shape = SegmentedButtonDefaults.itemShape(
                        index = index,
                        count = defaultOptions.size,
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .testTag(WidgetSettingsTestTags.defaultStepOption(option)),
                    label = { Text("${option}s") },
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = stringResource(R.string.settings_widget_instances),
            style = MaterialTheme.typography.titleMedium,
        )

        if (instances.isEmpty()) {
            Text(
                text = stringResource(R.string.settings_widget_instances_empty),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.testTag(WidgetSettingsTestTags.INSTANCES_EMPTY),
            )
        } else {
            val perWidgetOptions = listOf<Int?>(null, 5, STEP_SECONDS, 30)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(WidgetSettingsTestTags.INSTANCES_LIST),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                instances.forEach { instance ->
                    key(instance.appWidgetId) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag(WidgetSettingsTestTags.instanceRow(instance.appWidgetId)),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                text = instance.label,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                                perWidgetOptions.forEachIndexed { index, option ->
                                    SegmentedButton(
                                        selected = instance.config.stepSecondsOverride == option,
                                        onClick = {
                                            onInstanceStepSecondsChanged(
                                                instance.appWidgetId,
                                                option,
                                            )
                                        },
                                        shape = SegmentedButtonDefaults.itemShape(
                                            index = index,
                                            count = perWidgetOptions.size,
                                        ),
                                        modifier = Modifier
                                            .weight(1f)
                                            .testTag(
                                                WidgetSettingsTestTags.instanceStepOption(
                                                    instance.appWidgetId,
                                                    option,
                                                ),
                                            ),
                                        label = {
                                            Text(
                                                option?.let { "${it}s" }
                                                    ?: stringResource(R.string.pen_widget_step_inherit),
                                            )
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
