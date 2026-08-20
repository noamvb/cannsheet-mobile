package com.example.widget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import com.example.R
import com.example.data.CannsheetGraph
import com.example.data.Product
import com.example.data.ProductTypeCodes
import com.example.data.productStatus
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PenWidgetConfigureActivity : ComponentActivity() {
    private var appWidgetId: Int = AppWidgetManager.INVALID_APPWIDGET_ID
    private var selectablePens by mutableStateOf<List<Product>>(emptyList())
    private var selectedProductId by mutableStateOf<String?>(null)
    private var discreet by mutableStateOf(false)
    private var stepSeconds by mutableIntStateOf(STEP_SECONDS)
    private var isLoading by mutableStateOf(true)
    private var isSaving by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Keep the launcher's default failure result in place for every early exit path.
        setResult(Activity.RESULT_CANCELED)
        val isDarkTheme = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !isDarkTheme
            isAppearanceLightNavigationBars = !isDarkTheme
        }

        appWidgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID,
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID

        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }

        setContent {
            MyApplicationTheme {
                PenWidgetConfigureScreen(
                    products = selectablePens,
                    selectedProductId = selectedProductId,
                    discreet = discreet,
                    stepSeconds = stepSeconds,
                    isLoading = isLoading,
                    isSaving = isSaving,
                    onProductSelected = { selectedProductId = it },
                    onDiscreetChanged = { discreet = it },
                    onStepSecondsSelected = { stepSeconds = it },
                    onSave = {
                        save(
                            PenWidgetInstanceConfig(
                                pinnedProductId = selectedProductId,
                                discreet = discreet,
                                stepSecondsOverride = stepSeconds,
                            ),
                        )
                    },
                )
            }
        }

        loadConfiguration()
    }

    private fun loadConfiguration() {
        lifecycleScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val graph = CannsheetGraph.get(applicationContext)
                    val products = graph.repository.allProducts.first().filter { product ->
                        product.productStatus.isSelectable &&
                            ProductTypeCodes.normalize(product.type) == ProductTypeCodes.PEN
                    }
                    val config = PenWidgetConfigRepository(applicationContext)
                        .read(appWidgetId)
                    products to config
                }
            }.onSuccess { (products, config) ->
                selectablePens = products
                selectedProductId = config.pinnedProductId
                    ?.takeIf { id -> products.any { it.id == id } }
                discreet = config.discreet
                stepSeconds = config.stepSecondsOverride ?: STEP_SECONDS
                isLoading = false
            }.onFailure {
                // Leave RESULT_CANCELED in place when the catalog cannot be read.
                finish()
            }
        }
    }

    private fun save(config: PenWidgetInstanceConfig) {
        if (isSaving) return
        isSaving = true
        lifecycleScope.launch {
            runCatching {
                PenWidgetConfigRepository(applicationContext).write(appWidgetId, config)
                PenWidgetRuntime.withSerialized {
                    PenWidgetUpdater.update(applicationContext, appWidgetId)
                }
            }.onSuccess {
                setResult(
                    Activity.RESULT_OK,
                    Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId),
                )
                finish()
            }.onFailure {
                isSaving = false
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PenWidgetConfigureScreen(
    products: List<Product>,
    selectedProductId: String?,
    discreet: Boolean,
    stepSeconds: Int,
    isLoading: Boolean,
    isSaving: Boolean,
    onProductSelected: (String?) -> Unit,
    onDiscreetChanged: (Boolean) -> Unit,
    onStepSecondsSelected: (Int) -> Unit,
    onSave: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
    ) {
        if (isLoading) {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                CircularProgressIndicator()
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
            ) {
                Text(
                    text = stringResource(R.string.pen_widget_configure_label),
                    style = MaterialTheme.typography.headlineSmall,
                )
                Spacer(modifier = Modifier.height(24.dp))

                PenWidgetRadioOption(
                    selected = selectedProductId == null,
                    label = stringResource(R.string.pen_widget_follow_loaded_cart),
                    onClick = { onProductSelected(null) },
                )
                products.forEach { product ->
                    PenWidgetRadioOption(
                        selected = selectedProductId == product.id,
                        label = product.name,
                        onClick = { onProductSelected(product.id) },
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.pen_widget_discreet_mode),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = stringResource(R.string.pen_widget_discreet_mode_hint),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Switch(
                        checked = discreet,
                        onCheckedChange = onDiscreetChanged,
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))
                Text(
                    text = stringResource(R.string.pen_widget_step_size),
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(modifier = Modifier.height(8.dp))
                val stepOptions = listOf(5, STEP_SECONDS, 30)
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    stepOptions.forEachIndexed { index, option ->
                        SegmentedButton(
                            selected = stepSeconds == option,
                            onClick = { onStepSecondsSelected(option) },
                            shape = SegmentedButtonDefaults.itemShape(
                                index = index,
                                count = stepOptions.size,
                            ),
                            modifier = Modifier.weight(1f),
                            label = { Text("${option}s") },
                        )
                    }
                }

                Spacer(modifier = Modifier.height(32.dp))
                Button(
                    onClick = onSave,
                    enabled = !isSaving,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(text = stringResource(R.string.pen_widget_save))
                }
            }
        }
    }
}

@Composable
private fun PenWidgetRadioOption(
    selected: Boolean,
    label: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(
                selected = selected,
                onClick = onClick,
                role = Role.RadioButton,
            )
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(
            selected = selected,
            onClick = null,
        )
        Text(
            text = label,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}
