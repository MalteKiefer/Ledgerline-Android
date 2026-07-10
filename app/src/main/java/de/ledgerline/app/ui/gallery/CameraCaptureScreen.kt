package de.ledgerline.app.ui.gallery

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Cameraswitch
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import de.ledgerline.app.R
import de.ledgerline.app.ui.workspace.LocalFullscreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Full-screen camera capture composable. The captured JPEG bytes are delivered
 * in-memory via [onCaptured] and NEVER written to disk — [ImageCapture] with
 * [ImageCapture.OnImageCapturedCallback] returns an [ImageProxy] whose plane[0]
 * buffer holds the complete JPEG directly in RAM.
 *
 * The UI is drawn edge-to-edge (a full-bleed preview with overlaid controls) rather
 * than in a nested Scaffold, so it composes correctly under the parent's fullscreen
 * inset handling; back/lens-switch sit under [statusBarsPadding] and the shutter over
 * [navigationBarsPadding]. The lens can be flipped between back and front (selfie).
 *
 * Device location is obtained via AOSP [LocationManager] (no Google Play Services)
 * and passed to [onCaptured] as [lat]/[lng]. If the user denies location permission
 * or no last-known fix is available, both values are null — the photo is still taken.
 */
@Composable
fun CameraCaptureScreen(
    onCaptured: (bytes: ByteArray, lat: Double?, lng: Double?) -> Unit,
    onBack: () -> Unit,
) {
    // Hide scaffold chrome while this screen is visible.
    val fs = LocalFullscreen.current
    DisposableEffect(Unit) { fs.value = true; onDispose { fs.value = false } }

    val context = LocalContext.current

    // --- Camera permission ---
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }

    // --- Location permission state (fine preferred, coarse acceptable) ---
    var hasLocationPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        )
    }

    // Request both camera and location together in one system dialog.
    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        hasPermission = grants[Manifest.permission.CAMERA] == true
        hasLocationPermission = grants[Manifest.permission.ACCESS_FINE_LOCATION] == true
            || grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true
    }

    // ImageCapture use-case — kept in remembered state so the shutter button
    // can reference the same instance bound to the camera lifecycle.
    val imageCapture = remember { ImageCapture.Builder().build() }
    var capturing by remember { mutableStateOf(false) }
    // Selected lens; flips between back and front (selfie).
    var lensFacing by remember { mutableIntStateOf(CameraSelector.LENS_FACING_BACK) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        when {
            !hasPermission -> {
                // Permission gate — request on first composition, show message if denied.
                DisposableEffect(Unit) {
                    permLauncher.launch(
                        arrayOf(
                            Manifest.permission.CAMERA,
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION,
                        )
                    )
                    onDispose { }
                }
                Text(
                    text = stringResource(R.string.camera_permission_needed),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(24.dp),
                )
            }

            else -> {
                // Full-bleed camera preview (rebinds when the lens flips).
                CameraPreviewWithCapture(
                    imageCapture = imageCapture,
                    lensFacing = lensFacing,
                    modifier = Modifier.fillMaxSize(),
                )

                // Top overlay: back (left) + lens switch (right), below the status bar.
                Row(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 4.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                            tint = Color.White,
                        )
                    }
                    IconButton(
                        onClick = {
                            lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
                                CameraSelector.LENS_FACING_FRONT
                            } else {
                                CameraSelector.LENS_FACING_BACK
                            }
                        },
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Cameraswitch,
                            contentDescription = stringResource(R.string.camera_switch),
                            tint = Color.White,
                        )
                    }
                }

                // Location hint shown when location permission was granted.
                if (hasLocationPermission) {
                    Text(
                        text = stringResource(R.string.camera_location_hint),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .statusBarsPadding()
                            .padding(top = 56.dp),
                    )
                }

                // Shutter FAB, above the navigation bar.
                if (capturing) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .navigationBarsPadding()
                            .padding(bottom = 32.dp)
                            .size(56.dp),
                    )
                } else {
                    FloatingActionButton(
                        onClick = {
                            capturing = true
                            // Read the best last-known location from AOSP LocationManager
                            // before invoking the shutter. Guard with a runtime permission
                            // check immediately before the read (hasLocationPermission may
                            // have changed since composition).
                            val location: Location? = if (hasLocationPermission) {
                                readLastKnownLocation(context)
                            } else null

                            imageCapture.takePicture(
                                ContextCompat.getMainExecutor(context),
                                object : ImageCapture.OnImageCapturedCallback() {
                                    override fun onCaptureSuccess(image: ImageProxy) {
                                        // planes[0] buffer holds the complete JPEG in RAM —
                                        // no disk I/O involved at any point.
                                        val buf = image.planes[0].buffer
                                        val bytes = ByteArray(buf.remaining())
                                        buf.get(bytes)
                                        image.close()
                                        capturing = false
                                        onCaptured(bytes, location?.latitude, location?.longitude)
                                    }

                                    override fun onError(exc: ImageCaptureException) {
                                        capturing = false
                                        // Dismiss silently; user can retry via the back arrow.
                                    }
                                },
                            )
                        },
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .navigationBarsPadding()
                            .padding(bottom = 32.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.PhotoCamera,
                            contentDescription = stringResource(R.string.gallery_take_photo),
                        )
                    }
                }
            }
        }
    }
}

/**
 * Reads the most recent last-known location from the AOSP [LocationManager] by
 * querying GPS, Network, and the AOSP fused provider (constant `"fused"` — this is
 * NOT Google Play Services' FusedLocationProviderClient). Returns the fix with the
 * newest timestamp, or null when no fix is available or permission is absent.
 *
 * The [SuppressLint] is intentional: we check permission immediately before every
 * call to this function, so the suppress is safe.
 */
@SuppressLint("MissingPermission")
private fun readLastKnownLocation(context: android.content.Context): Location? {
    val lm = context.getSystemService(LocationManager::class.java) ?: return null
    return listOf(
        LocationManager.GPS_PROVIDER,
        LocationManager.NETWORK_PROVIDER,
        LocationManager.FUSED_PROVIDER,
    ).mapNotNull { provider ->
        runCatching { lm.getLastKnownLocation(provider) }.getOrNull()
    }.maxByOrNull { it.time }
}

/**
 * Binds a CameraX [Preview] + [ImageCapture] to the current lifecycle owner using the
 * given [lensFacing]. Re-binds whenever the lens changes (back ↔ front). Falls back
 * silently if the requested lens is unavailable on the device.
 */
@Composable
private fun CameraPreviewWithCapture(
    imageCapture: ImageCapture,
    lensFacing: Int,
    modifier: Modifier = Modifier,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val context = LocalContext.current
    val previewView = remember { PreviewView(context) }

    LaunchedEffect(lensFacing) {
        val provider = withContext(Dispatchers.IO) { ProcessCameraProvider.getInstance(context).get() }
        val preview = Preview.Builder().build().also {
            it.setSurfaceProvider(previewView.surfaceProvider)
        }
        val selector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
        runCatching {
            provider.unbindAll()
            provider.bindToLifecycle(lifecycleOwner, selector, preview, imageCapture)
        }
    }

    AndroidView(factory = { previewView }, modifier = modifier)
}
