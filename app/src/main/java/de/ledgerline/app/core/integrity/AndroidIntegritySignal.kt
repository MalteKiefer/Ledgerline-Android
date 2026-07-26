package de.ledgerline.app.core.integrity

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.bouncycastle.asn1.ASN1Enumerated
import org.bouncycastle.asn1.ASN1InputStream
import org.bouncycastle.asn1.ASN1OctetString
import org.bouncycastle.asn1.ASN1Sequence
import java.io.File
import java.security.KeyStore
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * AOSP-only [IntegritySignal] (the `foss` baseline — zero Google). Key attestation is pure
 * AndroidKeyStore + a local ASN.1 parse of the attestation extension; root heuristics are
 * self-contained filesystem/build checks. Everything is advisory: nothing here ever blocks.
 */
@Singleton
class AndroidIntegritySignal @Inject constructor() : IntegritySignal {

    override suspend fun assess(): IntegrityReport = withContext(Dispatchers.Default) {
        val attestation = attestationLevel()
        val reasons = rootReasons()
        IntegrityReport(
            attestation = attestation,
            rooted = reasons.isNotEmpty(),
            rootReasons = reasons,
            assessedAt = System.currentTimeMillis(),
        )
    }

    // ---- Keystore key attestation ---------------------------------------------------------

    /**
     * Generate a throwaway EC key with an attestation challenge, then read its certificate
     * chain and parse the KeyDescription extension to learn where the key actually lives.
     * A device with no attestation support (or an emulator) throws or yields a chainless key
     * → [AttestationLevel.UNVERIFIED]. The temp key is always deleted.
     */
    private fun attestationLevel(): AttestationLevel {
        val alias = "ledgerline_attest_probe"
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return try {
            val challenge = ByteArray(16).also { SecureRandom().nextBytes(it) }
            val gen = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
            gen.initialize(
                KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    .setAttestationChallenge(challenge)
                    .build(),
            )
            gen.generateKeyPair()
            val chain = ks.getCertificateChain(alias)
                ?.filterIsInstance<X509Certificate>()
                ?: return AttestationLevel.UNVERIFIED
            // A genuine hardware attestation is signed by a Google/vendor root → chain > 1.
            if (chain.size < 2) return AttestationLevel.SOFTWARE
            securityLevelFrom(chain.first()) ?: AttestationLevel.UNVERIFIED
        } catch (_: Throwable) {
            // Attestation unsupported / disabled → treat as unverifiable, never as "attested".
            AttestationLevel.UNVERIFIED
        } finally {
            runCatching { if (ks.containsAlias(alias)) ks.deleteEntry(alias) }
        }
    }

    /**
     * Parse the KeyDescription (OID 1.3.6.1.4.1.11129.2.1.17). Its second element is
     * `attestationSecurityLevel ENUMERATED { Software(0), TrustedEnvironment(1), StrongBox(2) }`.
     */
    private fun securityLevelFrom(leaf: X509Certificate): AttestationLevel? {
        val raw = leaf.getExtensionValue("1.3.6.1.4.1.11129.2.1.17") ?: return null
        return try {
            // `getExtensionValue` returns a DER OCTET STRING wrapping the extnValue; that inner
            // value is itself the DER of the KeyDescription SEQUENCE.
            val outer = ASN1InputStream(raw).use { it.readObject() } as ASN1OctetString
            val seq = ASN1InputStream(outer.octets).use { it.readObject() } as ASN1Sequence
            when ((seq.getObjectAt(1) as ASN1Enumerated).value.toInt()) {
                0 -> AttestationLevel.SOFTWARE
                1 -> AttestationLevel.TEE
                2 -> AttestationLevel.STRONGBOX
                else -> AttestationLevel.UNVERIFIED
            }
        } catch (_: Throwable) {
            null
        }
    }

    // ---- Root / tamper heuristics (informational only) ------------------------------------

    /** RootBeer-style indicators. Empty list = nothing detected. NEVER used to block. */
    private fun rootReasons(): List<String> {
        val reasons = mutableListOf<String>()
        val tags = Build.TAGS
        if (tags != null && tags.contains("test-keys")) reasons.add("test-keys build")
        SU_PATHS.firstOrNull { runCatching { File(it).exists() }.getOrDefault(false) }
            ?.let { reasons.add("su binary: $it") }
        MAGISK_PATHS.firstOrNull { runCatching { File(it).exists() }.getOrDefault(false) }
            ?.let { reasons.add("Magisk artifact: $it") }
        return reasons
    }

    private companion object {
        val SU_PATHS = listOf(
            "/system/bin/su", "/system/xbin/su", "/sbin/su", "/su/bin/su",
            "/system/app/Superuser.apk", "/data/local/bin/su", "/data/local/xbin/su",
        )
        val MAGISK_PATHS = listOf(
            "/sbin/.magisk", "/cache/.disable_magisk", "/data/adb/magisk",
            "/data/adb/modules", "/system/addon.d/99-magisk.sh",
        )
    }
}
