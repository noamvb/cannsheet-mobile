package com.example.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun SettingsScreen(viewModel: CannsheetViewModel) {
    val gasUrl by viewModel.gasUrl.collectAsState()
    val syncStatus by viewModel.syncStatus.collectAsState()
    val pendingCount by viewModel.pendingActionCount.collectAsState()

    var urlInput by remember { mutableStateOf(gasUrl) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text("Settings & Sync", style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.height(24.dp))

        OutlinedTextField(
            value = urlInput,
            onValueChange = { urlInput = it },
            label = { Text("GAS Web App URL") },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(8.dp))
        Button(
            onClick = { viewModel.setGasUrl(urlInput) },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Save URL")
        }

        Spacer(modifier = Modifier.height(32.dp))
        Text("Submission Timer", style = MaterialTheme.typography.titleLarge)
        Spacer(modifier = Modifier.height(8.dp))

        val timerValue by viewModel.submissionTimer.collectAsState()
        Text("Cancel window: $timerValue seconds")
        Slider(
            value = timerValue.toFloat(),
            onValueChange = { viewModel.setSubmissionTimer(it.toInt()) },
            valueRange = 0f..5f,
            steps = 4
        )

        Spacer(modifier = Modifier.height(32.dp))
        Text("Offline Queue", style = MaterialTheme.typography.titleLarge)
        Spacer(modifier = Modifier.height(8.dp))
        Text("Pending Actions: $pendingCount")

        Spacer(modifier = Modifier.height(16.dp))
        Button(
            onClick = { viewModel.syncQueue() },
            modifier = Modifier.fillMaxWidth(),
            enabled = pendingCount > 0 && urlInput.isNotEmpty()
        ) {
            Text("Sync Now")
        }

        Spacer(modifier = Modifier.height(16.dp))
        Button(
            onClick = { viewModel.fetchProducts() },
            modifier = Modifier.fillMaxWidth(),
            enabled = urlInput.isNotEmpty()
        ) {
            Text("Force Fetch Products")
        }

        Spacer(modifier = Modifier.height(32.dp))
        if (syncStatus != null) {
            Text("Status: $syncStatus", color = MaterialTheme.colorScheme.primary)
        }
    }
}
