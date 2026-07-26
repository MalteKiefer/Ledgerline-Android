package de.ledgerline.app.ui.common

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType

/**
 * `KeyboardOptions` for any field that holds a secret (master passphrase, recovery code,
 * password/TOTP/secret custom fields). §3.3 IME hardening:
 *
 * `KeyboardType.Password` maps to the platform `TYPE_TEXT_VARIATION_PASSWORD` input type,
 * which Android documents as **excluding the field from IME personalized learning** and
 * suppresses autocorrect/suggestions — so a typed secret is never persisted into the
 * keyboard's learned-words store or shown in the suggestion strip. `autoCorrectEnabled =
 * false` is belt-and-suspenders for IMEs that would otherwise offer corrections.
 *
 * Compose's `OutlinedTextField`/`KeyboardOptions` does not expose the raw EditorInfo
 * `IME_FLAG_NO_PERSONALIZED_LEARNING`; the password input variation is the supported
 * mechanism for the same guarantee.
 */
fun secretKeyboardOptions(): KeyboardOptions =
    KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false)
