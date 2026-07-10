package de.ledgerline.app.ui.pairing

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
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
    // Tracks whether the user has already been prompted, to distinguish an
    // initial "not yet asked" state from a "permanently denied" state.
    var permissionRequested by remember { mutableStateOf(false) }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        hasCamera = it
        permissionRequested = true
    }

    // A deep link that launched the app takes precedence over scanning.
    LaunchedEffect(initialPairLink) {
        initialPairLink?.let { link -> parsePairLink(link)?.let { (url, code) -> vm.startPairing(url, code, Build.MODEL) } }
    }

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

    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            stringResource(R.string.pairing_title),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(24.dp))

        if (hasCamera) {
            Text(
                stringResource(R.string.pairing_point_at_code),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            ScannerCard { link ->
                parsePairLink(link)?.let { (url, code) -> vm.startPairing(url, code, Build.MODEL) }
            }
            Spacer(Modifier.height(20.dp))
            PairingStatus(state, authFailed)
        } else {
            CameraRationaleCard(
                permanentlyDenied = permissionRequested,
                onAllow = { permLauncher.launch(Manifest.permission.CAMERA) },
            )
        }
    }
}

@Composable
private fun PairingStatus(state: PairingState, authFailed: Boolean) {
    when (state) {
        is PairingState.Claiming, is PairingState.Polling -> {
            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.pairing_waiting),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        is PairingState.Approved ->
            if (authFailed) {
                Text(stringResource(R.string.lock_locked), color = MaterialTheme.colorScheme.error)
            } else {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            }
        is PairingState.Failed ->
            Text(
                stringResource(R.string.pairing_failed, state.reason.name),
                color = MaterialTheme.colorScheme.error,
            )
        else -> {}
    }
}

/** Rationale card shown before the camera permission is granted. */
@Composable
private fun CameraRationaleCard(permanentlyDenied: Boolean, onAllow: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_camera),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(48.dp),
            )
            Spacer(Modifier.height(16.dp))
            Text(
                stringResource(R.string.pairing_rationale_heading),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                stringResource(R.string.pairing_rationale_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(20.dp))
            Button(
                onClick = onAllow,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(16.dp),
            ) {
                Text(stringResource(R.string.pairing_allow_camera), style = MaterialTheme.typography.labelLarge)
            }
            if (permanentlyDenied) {
                Spacer(Modifier.height(12.dp))
                Text(
                    stringResource(R.string.pairing_settings_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/** Centered square camera preview constrained in a rounded card with a viewfinder. */
@Composable
private fun ScannerCard(onQr: (String) -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .widthIn(max = 300.dp)
            .aspectRatio(1f)
            .clip(RoundedCornerShape(24.dp)),
        contentAlignment = Alignment.Center,
    ) {
        CameraPreview(Modifier.fillMaxSize(), onQr)
        ViewfinderOverlay(Modifier.fillMaxSize())
    }
}

/** Dims everything outside a centered rounded square and draws teal corner brackets. */
@Composable
private fun ViewfinderOverlay(modifier: Modifier) {
    val accent = MaterialTheme.colorScheme.primary
    Canvas(modifier) {
        val side = size.minDimension * 0.72f
        val left = (size.width - side) / 2f
        val top = (size.height - side) / 2f
        val corner = 24.dp.toPx()
        val bracket = side * 0.18f
        val stroke = 4.dp.toPx()

        // Dim the area outside the centered square (four rectangles).
        val scrim = Color.Black.copy(alpha = 0.5f)
        drawRect(scrim, topLeft = Offset(0f, 0f), size = Size(size.width, top))
        drawRect(scrim, topLeft = Offset(0f, top + side), size = Size(size.width, size.height - top - side))
        drawRect(scrim, topLeft = Offset(0f, top), size = Size(left, side))
        drawRect(scrim, topLeft = Offset(left + side, top), size = Size(size.width - left - side, side))

        // Teal corner brackets.
        val right = left + side
        val bottom = top + side
        fun corner(x1: Float, y1: Float, x2: Float, y2: Float) {
            drawLine(accent, Offset(x1, y1), Offset(x2, y2), strokeWidth = stroke, cap = StrokeCap.Round)
        }
        // top-left
        corner(left + corner, top, left + corner + bracket, top)
        corner(left, top + corner, left, top + corner + bracket)
        // top-right
        corner(right - corner, top, right - corner - bracket, top)
        corner(right, top + corner, right, top + corner + bracket)
        // bottom-left
        corner(left + corner, bottom, left + corner + bracket, bottom)
        corner(left, bottom - corner, left, bottom - corner - bracket)
        // bottom-right
        corner(right - corner, bottom, right - corner - bracket, bottom)
        corner(right, bottom - corner, right, bottom - corner - bracket)
    }
}

@Composable
private fun CameraPreview(modifier: Modifier, onQr: (String) -> Unit) {
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
        modifier = modifier,
    )
}
