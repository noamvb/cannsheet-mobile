package com.example.widget.projection

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
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.lifecycle.lifecycleScope
import com.example.R
import com.example.widget.PenWidgetRuntime
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Stable contract for the provider and updater: DataStore [DATASTORE_NAME] stores a string at
 * key [MODE_KEY_PREFIX] + appWidgetId, whose value is [MODE_RUNWAY] or [MODE_SPEND].
 */
object ProjectionWidgetConfiguration {
    const val DATASTORE_NAME = "projection_widget_config"
    const val MODE_KEY_PREFIX = "mode_"
    const val MODE_RUNWAY = "runway"
    const val MODE_SPEND = "spend"

    fun modeKey(appWidgetId: Int) = stringPreferencesKey("$MODE_KEY_PREFIX$appWidgetId")
}

private enum class ProjectionConfigurationState {
    LOADING,
    READY,
    FAILURE,
}

class ProjectionWidgetConfigureActivity : ComponentActivity() {
    private var appWidgetId: Int = AppWidgetManager.INVALID_APPWIDGET_ID
    private var selectedMode by mutableStateOf(ProjectionWidgetConfiguration.MODE_RUNWAY)
    private var configurationState by mutableStateOf(ProjectionConfigurationState.LOADING)
    private var isSaving by mutableStateOf(false)
    private var saveFailed by mutableStateOf(false)

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

        appWidgetId = intent?.getIntExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID,
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }

        setContent {
            MyApplicationTheme {
                ProjectionWidgetConfigureScreen(
                    selectedMode = selectedMode,
                    state = configurationState,
                    isSaving = isSaving,
                    saveFailed = saveFailed,
                    onModeSelected = { selectedMode = it },
                    onRetry = ::loadConfiguration,
                    onSave = ::saveConfiguration,
                )
            }
        }

        loadConfiguration()
    }

    private fun loadConfiguration() {
        configurationState = ProjectionConfigurationState.LOADING
        saveFailed = false
        lifecycleScope.launch {
            try {
                val mode = withContext(Dispatchers.IO) {
                    ProjectionWidgetStateRepository(applicationContext).readMode(appWidgetId)
                }
                selectedMode = mode
                    ?.takeIf { it == ProjectionWidgetConfiguration.MODE_RUNWAY || it == ProjectionWidgetConfiguration.MODE_SPEND }
                    ?: ProjectionWidgetConfiguration.MODE_RUNWAY
                configurationState = ProjectionConfigurationState.READY
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Throwable) {
                configurationState = ProjectionConfigurationState.FAILURE
            }
        }
    }

    private fun saveConfiguration() {
        if (isSaving || configurationState != ProjectionConfigurationState.READY) return
        isSaving = true
        saveFailed = false
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    ProjectionWidgetStateRepository(applicationContext)
                        .writeMode(appWidgetId, selectedMode)
                }
                PenWidgetRuntime.withSerialized {
                    ProjectionWidgetUpdater.update(applicationContext, appWidgetId)
                }
                setResult(
                    Activity.RESULT_OK,
                    Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId),
                )
                finish()
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Throwable) {
                isSaving = false
                saveFailed = true
            }
        }
    }
}

@Composable
private fun ProjectionWidgetConfigureScreen(
    selectedMode: String,
    state: ProjectionConfigurationState,
    isSaving: Boolean,
    saveFailed: Boolean,
    onModeSelected: (String) -> Unit,
    onRetry: () -> Unit,
    onSave: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
    ) {
        when (state) {
            ProjectionConfigurationState.LOADING -> {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator()
                }
            }

            ProjectionConfigurationState.FAILURE -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        text = stringResource(R.string.projection_widget_configure_load_error),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(onClick = onRetry) {
                        Text(text = stringResource(R.string.projection_widget_configure_retry))
                    }
                }
            }

            ProjectionConfigurationState.READY -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                ) {
                    Text(
                        text = stringResource(R.string.projection_widget_configure_label),
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.projection_widget_configure_prompt),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    ProjectionModeOption(
                        selected = selectedMode == ProjectionWidgetConfiguration.MODE_RUNWAY,
                        label = stringResource(R.string.projection_widget_mode_runway),
                        onClick = { onModeSelected(ProjectionWidgetConfiguration.MODE_RUNWAY) },
                    )
                    ProjectionModeOption(
                        selected = selectedMode == ProjectionWidgetConfiguration.MODE_SPEND,
                        label = stringResource(R.string.projection_widget_mode_spend),
                        onClick = { onModeSelected(ProjectionWidgetConfiguration.MODE_SPEND) },
                    )
                    Spacer(modifier = Modifier.height(32.dp))
                    Button(
                        onClick = onSave,
                        enabled = !isSaving,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = stringResource(
                                if (isSaving) {
                                    R.string.projection_widget_configure_saving
                                } else {
                                    R.string.projection_widget_configure_save
                                },
                            ),
                        )
                    }
                    if (saveFailed) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = stringResource(R.string.projection_widget_configure_save_error),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ProjectionModeOption(
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
