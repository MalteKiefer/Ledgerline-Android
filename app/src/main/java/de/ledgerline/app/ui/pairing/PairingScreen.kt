package de.ledgerline.app.ui.pairing

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.ledgerline.app.R
import de.ledgerline.app.domain.model.PairingState
import de.ledgerline.app.ui.scan.QrCodeAnalyzer
import de.ledgerline.app.ui.scan.parsePairLink

/**
 * Pairing screen. On approval it requires an app-lock auth (biometric / device
 * credential) via [authGate] before the session is sealed to disk — this both
 * establishes the user and opens the auth window the keystore key needs to seal.
 *
 * @param authGate runs the app-lock prompt; returns true on success.
 * @param initialPairLink an optional `ledgerline://pair` deep link to auto-start.
 */
@Composable
fun PairingScreen(
    vm: PairingViewModel = hiltViewModel(),
    authGate: suspend () -> Boolean,
    initialPairLink: String? = null,
    onPaired: () -> Unit,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var hasCamera by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }
    var authFailed by remember { mutableStateOf(false) }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { hasCamera = it }

    // A deep link that launched the app takes precedence over scanning.
    LaunchedEffect(initialPairLink) {
        initialPairLink?.let { link -> parsePairLink(link)?.let { (url, code) -> vm.startPairing(url, code, Build.MODEL) } }
    }
    LaunchedEffect(Unit) { if (!hasCamera) permLauncher.launch(Manifest.permission.CAMERA) }

    // On approval: gate on app-lock auth, then seal the session, then advance.
    LaunchedEffect(state) {
        val s = state
        if (s is PairingState.Approved) {
            authFailed = false
            if (authGate()) {
                vm.persist(s.session)
                onPaired()
            } else {
                authFailed = true
            }
        }
    }

    Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(stringResource(R.string.pairing_title), style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(16.dp))
        if (hasCamera) {
            CameraPreview { link ->
                parsePairLink(link)?.let { (url, code) -> vm.startPairing(url, code, Build.MODEL) }
            }
        } else {
            Text(stringResource(R.string.pairing_no_camera))
        }
        Spacer(Modifier.height(16.dp))
        when (val s = state) {
            is PairingState.Claiming, is PairingState.Polling -> {
                CircularProgressIndicator()
                Text(stringResource(R.string.pairing_waiting))
            }
            is PairingState.Approved ->
                if (authFailed) {
                    Text(stringResource(R.string.lock_locked), color = MaterialTheme.colorScheme.error)
                } else {
                    CircularProgressIndicator()
                }
            is PairingState.Failed -> Text(stringResource(R.string.pairing_failed, s.reason.name), color = MaterialTheme.colorScheme.error)
            else -> {}
        }
    }
}

@Composable
private fun CameraPreview(onQr: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    AndroidView(
        factory = { ctx ->
            val previewView = PreviewView(ctx)
            val providerFuture = ProcessCameraProvider.getInstance(ctx)
            providerFuture.addListener({
                val provider = providerFuture.get()
                val preview = androidx.camera.core.Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }
                val analysis = androidx.camera.core.ImageAnalysis.Builder()
                    .setBackpressureStrategy(androidx.camera.core.ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(ContextCompat.getMainExecutor(ctx), QrCodeAnalyzer(onQr))
                provider.unbindAll()
                provider.bindToLifecycle(lifecycleOwner, androidx.camera.core.CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            }, ContextCompat.getMainExecutor(ctx))
            previewView
        },
        modifier = Modifier.fillMaxWidth().height(320.dp),
    )
}
