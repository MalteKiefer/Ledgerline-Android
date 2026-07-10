package de.ledgerline.app.ui.gallery

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.lifecycle.compose.LocalLifecycleOwner
import de.ledgerline.app.R
import de.ledgerline.app.ui.workspace.LocalFullscreen

/**
 * Full-screen camera capture composable. The captured JPEG bytes are delivered
 * in-memory via [onCaptured] and NEVER written to disk — [ImageCapture] with
 * [ImageCapture.OnImageCapturedCallback] returns an [ImageProxy] whose plane[0]
 * buffer holds the complete JPEG directly in RAM.
 *
 * Callers must check/request CAMERA permission before showing this screen, or
 * rely on the built-in permission gate rendered here when permission is absent.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CameraCaptureScreen(
    onCaptured: (ByteArray) -> Unit,
    onBack: () -> Unit,
) {
    // Hide scaffold chrome while this screen is visible.
    val fs = LocalFullscreen.current
    DisposableEffect(Unit) { fs.value = true; onDispose { fs.value = false } }

    val context = LocalContext.current
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }
    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasPermission = granted }

    // ImageCapture use-case — kept in remembered state so the shutter button
    // can reference the same instance bound to the camera lifecycle.
    val imageCapture = remember { ImageCapture.Builder().build() }
    var capturing by remember { mutableStateOf(false) }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.gallery_take_photo)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            when {
                !hasPermission -> {
                    // Permission gate — request on first composition, show message if denied.
                    DisposableEffect(Unit) {
                        permLauncher.launch(Manifest.permission.CAMERA)
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
                    // Camera preview.
                    CameraPreviewWithCapture(
                        imageCapture = imageCapture,
                        modifier = Modifier.fillMaxSize(),
                    )

                    // Shutter FAB.
                    if (capturing) {
                        CircularProgressIndicator(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(bottom = 32.dp)
                                .size(56.dp),
                        )
                    } else {
                        FloatingActionButton(
                            onClick = {
                                capturing = true
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
                                            onCaptured(bytes)
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
}

/** Binds a CameraX [Preview] + [ImageCapture] to the current lifecycle owner. */
@Composable
private fun CameraPreviewWithCapture(
    imageCapture: ImageCapture,
    modifier: Modifier = Modifier,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    AndroidView(
        factory = { ctx ->
            val previewView = PreviewView(ctx)
            val providerFuture = ProcessCameraProvider.getInstance(ctx)
            providerFuture.addListener(
                {
                    val provider = providerFuture.get()
                    val preview = Preview.Builder().build().also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }
                    provider.unbindAll()
                    provider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        imageCapture,
                    )
                },
                ContextCompat.getMainExecutor(ctx),
            )
            previewView
        },
        modifier = modifier,
    )
}
