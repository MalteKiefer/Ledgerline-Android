package de.ledgerline.app.ui.workspace

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.compositionLocalOf

/**
 * Drives WorkspaceScaffold's immersive mode. When true, the host scaffold hides its
 * own top + bottom bars and switches its [de.ledgerline.app.ui.common.AppScaffold] to
 * `immersive = true` (consumes NO system-bar insets), so a nested full-screen view
 * (photo viewer, camera, file viewer, note/todo detail+editor) owns the whole screen
 * with its own top bar and inset handling.
 *
 * Design note: these detail/viewer screens are rendered *inside* the current tab's
 * content slot (not as separate nav destinations), so this CompositionLocal — rather
 * than a nav-graph swap — is how the host learns to yield the chrome. It is kept
 * (instead of removed) precisely because those screens are content-nested; it is now
 * driven correctly through AppScaffold so there is no phantom top gap. A later pass
 * that promotes these to real nav destinations can retire it.
 */
val LocalFullscreen = compositionLocalOf<MutableState<Boolean>> { error("LocalFullscreen not provided") }
