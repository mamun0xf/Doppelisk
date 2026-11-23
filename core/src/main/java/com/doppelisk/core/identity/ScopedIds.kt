package com.doppelisk.core.identity

import java.security.MessageDigest
import java.util.Locale
import java.util.UUID

/**
 * Ids derived from a *scope* (a package name, a Doppelisk id) rather than from a
 * profile seed.
 *
 * Not every identifier belongs in the profile: a per-app-set id must differ
 * between apps, and a manager-side id must not require a seed. These are
 * deterministic functions of the scope string so they stay stable across
 * restarts without being stored anywhere.
 */
object ScopedIds {

    /** A version-4-shaped UUID derived from `label|root`. */
    fun uuid(root: String, label: String): String {
        val digest = sha256("$label|$root").copyOf(16)
        digest[6] = ((digest[6].toInt() and 0x0F) or 0x40).toByte()
        digest[8] = ((digest[8].toInt() and 0x3F) or 0x80).toByte()
        return Generators.uuidCanonical(digest)
    }

    /** Lowercase hex derived from `label|root`, truncated to [length]. */
    fun hex(root: String, label: String, length: Int = 32): String =
        sha256("$label|$root").joinToString("") { String.format(Locale.US, "%02x", it) }
            .take(maxOf(length, 1))

    /** Raw SHA-256 of the UTF-8 bytes of [value]. */
    fun sha256(value: String): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
}
