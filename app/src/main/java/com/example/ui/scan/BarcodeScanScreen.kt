package com.example.ui.scan

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.data.barcode.Gs1Barcode
import com.example.data.barcode.Gs1ScanResult
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors

object BarcodeScanTestTags {
    const val PERMISSION_RATIONALE = "scan_permission_rationale"
    const val GRANT_PERMISSION = "scan_grant_permission"
    const val CANCEL = "scan_cancel"
    const val PREVIEW = "scan_preview"
    const val HINT = "scan_hint"
}

/**
 * Full-screen barcode scanner. Frames are analysed in memory and never stored.
 *
 * Only the formats a cannabis product label actually carries are enabled: a GS1
 * DataMatrix, plus the linear UPC-A / EAN-13 that the same package usually also
 * prints. [Gs1Barcode] normalises every one of them to the same 14-digit GTIN.
 */
@Composable
fun BarcodeScanScreen(
    onScanned: (Gs1ScanResult) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var hasPermission by remember { mutableStateOf(context.hasCameraPermission()) }
    var permissionDenied by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        hasPermission = granted
        permissionDenied = !granted
    }

    LaunchedEffect(Unit) {
        if (!hasPermission) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    if (hasPermission) {
        CameraPreview(onScanned = onScanned, onCancel = onCancel, modifier = modifier)
    } else {
        PermissionRationale(
            denied = permissionDenied,
            onGrant = { permissionLauncher.launch(Manifest.permission.CAMERA) },
            onCancel = onCancel,
            modifier = modifier,
        )
    }
}

@Composable
private fun PermissionRationale(
    denied: Boolean,
    onGrant: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = if (denied) {
                "Cannsheet needs the camera to read a product barcode. " +
                    "You can still add the purchase by typing it in."
            } else {
                "Cannsheet needs the camera to read a product barcode."
            },
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.testTag(BarcodeScanTestTags.PERMISSION_RATIONALE),
        )
        Button(
            onClick = onGrant,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 24.dp)
                .testTag(BarcodeScanTestTags.GRANT_PERMISSION),
        ) { Text("Allow camera") }
        TextButton(
            onClick = onCancel,
            modifier = Modifier
                .fillMaxWidth()
                .testTag(BarcodeScanTestTags.CANCEL),
        ) { Text("Enter it manually") }
    }
}

@Composable
private fun CameraPreview(
    onScanned: (Gs1ScanResult) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }

    // Guards against a second delivery while the caller is still navigating away.
    val alreadyDelivered = remember { java.util.concurrent.atomic.AtomicBoolean(false) }

    val scanner = remember {
        BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder()
                .setBarcodeFormats(
                    Barcode.FORMAT_DATA_MATRIX,
                    Barcode.FORMAT_UPC_A,
                    Barcode.FORMAT_EAN_13,
                )
                .build(),
        )
    }

    DisposableEffect(Unit) {
        onDispose {
            scanner.close()
            analysisExecutor.shutdown()
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier
                .fillMaxSize()
                .testTag(BarcodeScanTestTags.PREVIEW),
            factory = { viewContext ->
                val previewView = PreviewView(viewContext)
                val providerFuture = ProcessCameraProvider.getInstance(viewContext)
                providerFuture.addListener({
                    val provider = providerFuture.get()
                    val preview = Preview.Builder().build().also {
                        it.surfaceProvider = previewView.surfaceProvider
                    }
                    val analysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                        .also { useCase ->
                            useCase.setAnalyzer(analysisExecutor) { proxy ->
                                proxy.analyseForGs1(scanner) { result ->
                                    if (alreadyDelivered.compareAndSet(false, true)) {
                                        previewView.post { onScanned(result) }
                                    }
                                }
                            }
                        }
                    runCatching {
                        provider.unbindAll()
                        provider.bindToLifecycle(
                            lifecycleOwner,
                            CameraSelector.DEFAULT_BACK_CAMERA,
                            preview,
                            analysis,
                        )
                    }
                }, ContextCompat.getMainExecutor(viewContext))
                previewView
            },
        )

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "Point the camera at the barcode on the product label.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.testTag(BarcodeScanTestTags.HINT),
            )
            TextButton(
                onClick = onCancel,
                modifier = Modifier.testTag(BarcodeScanTestTags.CANCEL),
            ) { Text("Enter it manually") }
        }
    }
}

@OptIn(ExperimentalGetImage::class)
private fun androidx.camera.core.ImageProxy.analyseForGs1(
    scanner: com.google.mlkit.vision.barcode.BarcodeScanner,
    onResult: (Gs1ScanResult) -> Unit,
) {
    val mediaImage = image
    if (mediaImage == null) {
        close()
        return
    }
    val input = InputImage.fromMediaImage(mediaImage, imageInfo.rotationDegrees)
    scanner.process(input)
        .addOnSuccessListener { barcodes ->
            barcodes.asSequence()
                .mapNotNull { it.rawValue }
                .mapNotNull(Gs1Barcode::parse)
                .firstOrNull()
                ?.let(onResult)
        }
        .addOnCompleteListener { close() }
}

private fun Context.hasCameraPermission(): Boolean =
    ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
        PackageManager.PERMISSION_GRANTED
