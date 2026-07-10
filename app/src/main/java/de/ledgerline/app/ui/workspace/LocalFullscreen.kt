package de.ledgerline.app.ui.workspace

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.compositionLocalOf

/** When true, WorkspaceScaffold hides its own top+bottom bars and stops consuming
 *  the status-bar inset, so a nested full-screen view owns the whole screen with a
 *  single top bar. Detail/viewer screens toggle this while composed. */
val LocalFullscreen = compositionLocalOf<MutableState<Boolean>> { error("LocalFullscreen not provided") }
