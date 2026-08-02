package de.ledgerline.app.core

sealed interface Outcome<out T> {
    data class Ok<T>(val value: T) : Outcome<T>
    data class Err(val kind: ErrorKind, val cause: Throwable? = null) : Outcome<Nothing>
}

enum class ErrorKind { NETWORK, HTTP, WRONG_PASSPHRASE, DECRYPT, PIN_MISMATCH, NOT_CONFIGURED, GONE, RATE_LIMITED, QUOTA, UNKNOWN }
