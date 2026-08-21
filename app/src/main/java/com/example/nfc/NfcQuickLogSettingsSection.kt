package com.example.nfc

import android.content.Context
import android.content.Intent
import android.nfc.NfcAdapter
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.data.CannsheetGraph
import com.example.data.PenQuickLogDataSource
import com.example.domain.PenQuickLogState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

@Composable
fun NfcQuickLogSettingsSection(resolverDescription: String? = null) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val adapter = remember { NfcAdapter.getDefaultAdapter(context) }
    val registry = remember { NfcQuickLogRegistryRepository(context) }
    val registryState by registry.registry.collectAsState(NfcQuickLogRegistryState.Ready(emptyList()))
    val scope = rememberCoroutineScope()
    var penState by remember { mutableStateOf<PenQuickLogState>(PenQuickLogState.Unavailable) }
    var explicitId by remember { mutableStateOf<String?>(null) }
    var uses by rememberSaveable { mutableStateOf(1f) }
    var label by rememberSaveable { mutableStateOf("1-use tag") }
    var resetDialog by remember { mutableStateOf(false) }
    var revokeTarget by remember { mutableStateOf<RegisteredNfcQuickLogTag?>(null) }
    var renameTarget by remember { mutableStateOf<RegisteredNfcQuickLogTag?>(null) }
    var renameText by remember { mutableStateOf("") }
    var resumeTick by remember { mutableStateOf(0) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) resumeTick++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    androidx.compose.runtime.LaunchedEffect(Unit) {
        val graph = CannsheetGraph.get(context)
        val loaded = withContext(Dispatchers.IO) {
            PenQuickLogDataSource.load(context) to
                graph.consumptionPreferences.preferences.first().loadedPenProductId
        }
        penState = loaded.first
        explicitId = loaded.second
    }

    val launchPreferenceSupported = resumeTick >= 0 && Build.VERSION.SDK_INT >= 36 && adapter != null &&
        adapter.isTagIntentAppPreferenceSupported()
    val launchAllowed = if (launchPreferenceSupported) adapter?.isTagIntentAllowed() == true else true
    val canWrite = adapter != null && adapter.isEnabled && launchAllowed &&
        registryState !is NfcQuickLogRegistryState.Corrupt
    val resolvedDescription = resolverDescription ?: when (val state = penState) {
        PenQuickLogState.Unavailable -> "Current target: no selectable Pen cart."
        PenQuickLogState.NoCartLoaded -> "Current target: no Pen cart is currently resolvable."
        is PenQuickLogState.Loaded -> if (state.product.id == explicitId) {
            "Current target: ${state.product.name} — selected."
        } else {
            "Current target: ${state.product.name} — inferred from most recent Pen log."
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth().testTag("nfc-quick-log-settings"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("NFC quick-log tags", style = MaterialTheme.typography.titleLarge)
        Text(
            when {
                adapter == null -> "This phone has no NFC hardware."
                !adapter.isEnabled -> "NFC is off."
                !launchAllowed -> "Launch via NFC is disabled for Cannsheet."
                launchPreferenceSupported -> "NFC is ready; Launch via NFC is allowed."
                Build.VERSION.SDK_INT >= 36 -> "NFC is ready; Launch via NFC preference is unavailable."
                else -> "NFC is ready."
            },
        )
        Text(resolvedDescription)
        Text("Each tag records its configured uses for whichever Pen cart is current when tapped.")
        Text("Tags are not bound to the cart shown here. Tags store uses directly; seconds-per-use is not used.")
        if (adapter == null || !adapter.isEnabled) {
            OutlinedButton(onClick = { openNfcSettings(context) }, modifier = Modifier.fillMaxWidth()) {
                Text("Open NFC settings")
            }
        }
        if (!launchAllowed) {
            OutlinedButton(onClick = { openLaunchPreference(context) }, modifier = Modifier.fillMaxWidth()) {
                Text("Open Launch via NFC")
            }
        }
        OutlinedTextField(
            value = label,
            onValueChange = { if (it.codePointCount(0, it.length) <= 40) label = it },
            label = { Text("Private tag label (optional)") },
            singleLine = true,
            enabled = canWrite,
            modifier = Modifier.fillMaxWidth().testTag("nfc-quick-log-label"),
        )
        Text("Write or rewrite quantity: ${uses.toInt()} uses")
        Text("This tag will record ${uses.toInt()} uses for whichever Pen cart is current when tapped.")
        Slider(
            value = uses,
            onValueChange = { uses = it.toInt().coerceIn(1, 10).toFloat() },
            valueRange = 1f..10f,
            steps = 8,
            enabled = canWrite,
            modifier = Modifier.testTag("nfc-quick-log-uses"),
        )
        Button(
            onClick = {
                context.startActivity(
                    NfcTagWriterActivityContract.newTag(
                        context,
                        uses.toInt(),
                        label.trim().takeIf(String::isNotEmpty),
                    ),
                )
            },
            enabled = canWrite,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Write new tag") }
        OutlinedButton(
            onClick = { context.startActivity(NfcTagWriterActivityContract.adopt(context)) },
            enabled = canWrite,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Adopt compatible tag") }

        when (val current = registryState) {
            NfcQuickLogRegistryState.Corrupt -> {
                Text("Registry is corrupt; no tags will be accepted.")
                Button(onClick = { resetDialog = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("Reset NFC tag registry")
                }
            }
            is NfcQuickLogRegistryState.Ready -> {
                Text("Registered tags (${current.tags.size}/50)")
                current.tags.forEach { tag ->
                    RegisteredNfcQuickLogTagRow(
                        tag = tag,
                        canWrite = canWrite,
                        onVerify = { context.startActivity(NfcTagWriterActivityContract.verify(context, tag)) },
                        onRewrite = {
                            context.startActivity(
                                NfcTagWriterActivityContract.rewrite(
                                    context = context,
                                    tagId = tag.tagId,
                                    uses = uses.toInt(),
                                    label = tag.label,
                                ),
                            )
                        },
                        onRename = {
                            renameTarget = tag
                            renameText = tag.label.orEmpty()
                        },
                        onRevoke = { revokeTarget = tag },
                    )
                }
            }
        }
    }

    if (resetDialog) {
        AlertDialog(
            onDismissRequest = { resetDialog = false },
            title = { Text("Reset NFC tag registry?") },
            text = { Text("Only local authorizations and labels are removed. Physical tags remain intact.") },
            confirmButton = {
                TextButton(onClick = {
                    resetDialog = false
                    scope.launch { registry.reset() }
                }) { Text("Reset") }
            },
            dismissButton = { TextButton(onClick = { resetDialog = false }) { Text("Cancel") } },
        )
    }
    revokeTarget?.let { tag ->
        AlertDialog(
            onDismissRequest = { revokeTarget = null },
            title = { Text("Revoke tag?") },
            text = { Text("The physical tag remains unchanged and can be adopted again.") },
            confirmButton = {
                TextButton(onClick = {
                    revokeTarget = null
                    scope.launch { registry.revoke(tag.tagId) }
                }) { Text("Revoke") }
            },
            dismissButton = { TextButton(onClick = { revokeTarget = null }) { Text("Cancel") } },
        )
    }
    renameTarget?.let { tag ->
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("Rename tag") },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { if (it.codePointCount(0, it.length) <= 40) renameText = it },
                    singleLine = true,
                    label = { Text("Private label") },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    renameTarget = null
                    scope.launch { registry.rename(tag.tagId, renameText) }
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { renameTarget = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun RegisteredNfcQuickLogTagRow(
    tag: RegisteredNfcQuickLogTag,
    canWrite: Boolean,
    onVerify: () -> Unit,
    onRewrite: () -> Unit,
    onRename: () -> Unit,
    onRevoke: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text("${tag.label ?: "NFC tag"}: ${tag.uses} uses · …${tag.tagId.takeLast(8)}")
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            TextButton(onClick = onVerify) { Text("Verify") }
            TextButton(onClick = onRename) { Text("Rename") }
            TextButton(onClick = onRewrite, enabled = canWrite) { Text("Rewrite") }
            TextButton(onClick = onRevoke) { Text("Revoke") }
        }
    }
}

private fun openNfcSettings(context: Context) {
    val nfcIntent = Intent(Settings.ACTION_NFC_SETTINGS)
    val fallback = Intent(Settings.ACTION_SETTINGS)
    val target = nfcIntent.takeIf { it.resolveActivity(context.packageManager) != null } ?: fallback
    runCatching { context.startActivity(target) }
}

private fun openLaunchPreference(context: Context) {
    if (Build.VERSION.SDK_INT >= 36) {
        val preferenceIntent = Intent(NfcAdapter.ACTION_CHANGE_TAG_INTENT_PREFERENCE)
        if (preferenceIntent.resolveActivity(context.packageManager) != null) {
            runCatching { context.startActivity(preferenceIntent) }
        } else {
            openNfcSettings(context)
        }
    } else openNfcSettings(context)
}
