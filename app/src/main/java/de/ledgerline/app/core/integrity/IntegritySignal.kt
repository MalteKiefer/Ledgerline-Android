package de.ledgerline.app.core.integrity

/**
 * Client-integrity assessment (§3.6). Two independent, **non-blocking** signals:
 *
 *  1. **Android Keystore key attestation** — proves the device's cryptographic keys are
 *     hardware-backed (TEE / StrongBox) rather than emulated/software. A software-only or
 *     unverifiable result is a strong emulator/tamper indicator.
 *  2. **Root/tamper heuristics** — lightweight, OSS, RootBeer-style checks (su binaries,
 *     test-keys build tag, Magisk paths). Informational ONLY: a legitimately-rooted privacy
 *     ROM (e.g. GrapheneOS) must never be locked out, so this NEVER blocks unlock or pairing.
 *
 * This is the flavor-abstracted seam (§0.1): the default AOSP implementation is the `foss`
 * baseline (zero Google). A future `play` flavor may additionally consult Play Integrity and
 * fold it into [IntegrityReport], still advisory-only.
 */
interface IntegritySignal {
    suspend fun assess(): IntegrityReport
}

/** Where the assessed Keystore key lives (highest = strongest). [UNVERIFIED] = no attestation chain. */
enum class AttestationLevel { STRONGBOX, TEE, SOFTWARE, UNVERIFIED }

/**
 * The outcome of an [IntegritySignal.assess]. [attestation] is the hardware-backing level of a
 * freshly-attested Keystore key; [rooted] + [rootReasons] are the informational tamper heuristics.
 * [hardwareBacked] is the headline "is this a real secure device" boolean.
 */
data class IntegrityReport(
    val attestation: AttestationLevel,
    val rooted: Boolean,
    val rootReasons: List<String>,
    val assessedAt: Long,
) {
    /** True when the Keystore key is TEE- or StrongBox-backed (not software/emulated). */
    val hardwareBacked: Boolean get() = attestation == AttestationLevel.TEE || attestation == AttestationLevel.STRONGBOX

    /** True when nothing is amiss: hardware-backed keys AND no root/tamper indicator. */
    val clean: Boolean get() = hardwareBacked && !rooted
}
