package com.doppelisk.core.identity

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * HKDF-SHA256 (RFC 5869) over `javax.crypto.Mac`.
 *
 * Android has no HKDF before API 33, and the identity derivation must be
 * bit-identical on every Android version the app supports, so it is spelled out
 * here rather than delegated to a platform helper.
 *
 * The only domain separation between derived values is the [derive] label, so
 * callers must use the constants in [IdentityFields] and the `Generators`
 * labels rather than inventing strings.
 */
object Hkdf {

    private const val HASH = "HmacSHA256"
    private const val HASH_LEN = 32

    /**
     * Derives [length] bytes from [seed] under [label].
     *
     * [salt] defaults to 32 zero bytes — deliberately *not* an empty array,
     * because [extract] substitutes the same 32 zero bytes for an empty salt and
     * making that explicit keeps the two paths identical.
     */
    fun derive(seed: ByteArray, label: String, length: Int, salt: ByteArray = ByteArray(HASH_LEN)): ByteArray {
        val prk = extract(salt, seed)
        return expand(prk, label.toByteArray(Charsets.UTF_8), length)
    }

    /** RFC 5869 extract step: `HMAC(salt, ikm)`. */
    private fun extract(salt: ByteArray, ikm: ByteArray): ByteArray {
        val mac = Mac.getInstance(HASH)
        mac.init(SecretKeySpec(if (salt.isEmpty()) ByteArray(HASH_LEN) else salt, HASH))
        return mac.doFinal(ikm)
    }

    /** RFC 5869 expand step: counter-mode HMAC over `T || info || i`. */
    private fun expand(prk: ByteArray, info: ByteArray, length: Int): ByteArray {
        val blocks = maxOf((length + HASH_LEN - 1) / HASH_LEN, 1)
        val okm = ByteArray(blocks * HASH_LEN)
        var previous = ByteArray(0)
        for (i in 1..blocks) {
            val mac = Mac.getInstance(HASH)
            mac.init(SecretKeySpec(prk, HASH))
            mac.update(previous)
            mac.update(info)
            mac.update(i.toByte())
            previous = mac.doFinal()
            System.arraycopy(previous, 0, okm, (i - 1) * HASH_LEN, HASH_LEN)
        }
        return okm.copyOf(minOf(length, okm.size))
    }
}
