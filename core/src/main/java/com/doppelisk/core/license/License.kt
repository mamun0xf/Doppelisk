package com.doppelisk.core.license

import com.doppelisk.core.config.ConfigPaths
import com.doppelisk.core.model.JsonFormats
import kotlinx.serialization.Serializable
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.time.Instant
import java.util.Base64

/** An offline license: a base64url payload and its ECDSA signature. */
@Serializable
data class SignedLicense(
    val payload: String,
    val signature: String,
)

/**
 * The signed claims of a license.
 *
 * [product] is checked against [ConfigPaths.SOCKET_NAME] so a license issued for
 * another app cannot be replayed here. [expiresAt] is an ISO-8601 instant;
 * [maxDoppelisks] caps how many profiles may exist at once.
 */
@Serializable
data class LicenseClaims(
    val schema: Int = CURRENT_SCHEMA,
    val licensee: String = "",
    val product: String = ConfigPaths.SOCKET_NAME,
    val features: Map<String, Boolean> = emptyMap(),
    val maxDoppelisks: Int = Int.MAX_VALUE,
    val issuedAt: String? = null,
    val expiresAt: String? = null,
) {
    companion object {
        const val CURRENT_SCHEMA = 1
    }
}

/**
 * The answer to "is this feature allowed?".
 *
 * A gate is *open* when there is neither a license nor a reason — the free
 * build. When a license is present [claims] is non-null; when verification
 * failed [reason] says why and every feature is denied.
 */
data class LicenseGate(
    val claims: LicenseClaims?,
    val reason: String? = null,
) {
    val open: Boolean get() = claims == null && reason == null

    /** True when [name] is granted. Unknown features fall back to [open]. */
    fun feature(name: String): Boolean = claims?.features?.get(name) ?: open

    /** True when one more profile may be created given [existing]. */
    fun canCreate(existing: Int): Boolean {
        if (!open && existing >= (claims?.maxDoppelisks ?: 0)) return false
        return true
    }
}

/**
 * Verifies and produces [SignedLicense]s.
 *
 * Verification is deliberately strict and offline: an empty public key means
 * "no license configured" and yields an open gate, while any cryptographic or
 * format problem yields a closed gate with a human-readable reason. Nothing here
 * ever trusts an unsigned claim.
 */
object LicenseVerifier {

    private val json = JsonFormats.compact

    /** Verifies [envelope] against [publicKeyPem]. */
    fun verify(envelope: SignedLicense, publicKeyPem: String, now: Instant = Instant.now()): LicenseGate {
        if (publicKeyPem.isBlank()) return LicenseGate(claims = null, reason = null)

        val payload: ByteArray
        val signature: ByteArray
        try {
            signature = Base64.getUrlDecoder().decode(envelope.signature)
            payload = Base64.getUrlDecoder().decode(envelope.payload)
            val verifier = Signature.getInstance("SHA256withECDSA")
            verifier.initVerify(parsePublicKey(publicKeyPem))
            verifier.update(payload)
            if (!verifier.verify(signature)) return LicenseGate(null, "signature mismatch")
        } catch (e: Throwable) {
            return LicenseGate(null, "invalid license: ${e.message}")
        }

        val claims = try {
            json.decodeFromString(LicenseClaims.serializer(), payload.toString(Charsets.UTF_8))
        } catch (e: Throwable) {
            return LicenseGate(null, "invalid license: ${e.message}")
        }

        if (claims.product != ConfigPaths.SOCKET_NAME) return LicenseGate(null, "wrong product")

        val expiry = claims.expiresAt
        if (expiry != null) {
            val end = runCatching { Instant.parse(expiry) }.getOrNull()
                ?: return LicenseGate(null, "invalid expiry")
            if (!now.isBefore(end)) return LicenseGate(null, "license expired")
        }

        if (claims.maxDoppelisks < 0) return LicenseGate(null, "negative maxDoppelisks")

        return LicenseGate(claims, null)
    }

    /** Signs [claims] with [privateKey]. Used by the license tooling, not the app. */
    fun encode(claims: LicenseClaims, privateKey: PrivateKey): SignedLicense {
        val payload = json.encodeToString(LicenseClaims.serializer(), claims).toByteArray(Charsets.UTF_8)
        val signer = Signature.getInstance("SHA256withECDSA")
        signer.initSign(privateKey)
        signer.update(payload)
        val encoder = Base64.getUrlEncoder().withoutPadding()
        return SignedLicense(encoder.encodeToString(payload), encoder.encodeToString(signer.sign()))
    }

    /** Parses a PEM-encoded EC public key, ignoring the header lines and whitespace. */
    private fun parsePublicKey(pem: String): PublicKey {
        val body = pem
            .replace("-----BEGIN PUBLIC KEY-----", "")
            .replace("-----END PUBLIC KEY-----", "")
            .replace(Regex("\\s"), "")
        val bytes = Base64.getDecoder().decode(body)
        return KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(bytes))
    }
}
