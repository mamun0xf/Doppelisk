package com.doppelisk.core.identity

import com.doppelisk.core.model.Chassis
import com.doppelisk.core.model.FirmwareBuild
import com.doppelisk.core.model.IdentityFields
import java.util.Locale

/**
 * Every value the identity engine invents, as a pure function of the seed.
 *
 * All randomness comes from [Hkdf], never from `Random` or `SecureRandom`, so a
 * profile can be reproduced exactly from its 32-byte seed. The encodings here
 * are chosen to look like what Android actually reports: 16-hex SSAID, UUIDv4
 * GAID, 15-digit Luhn-valid IMEI, `AA:BB:CC:DD:EE:FF` MACs.
 */
object Generators {

    private const val SERIAL_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"

    /** Default TAC (first 8 IMEI digits) used for every chassis. */
    private const val DEFAULT_TAC = "35824010"

    /** Default serial length used by [IdentityEngine]. */
    const val SERIAL_LENGTH = 11

    /** Android ID: 8 derived bytes rendered as 16 lowercase hex characters. */
    fun ssaid(seed: ByteArray): String = hex(Hkdf.derive(seed, IdentityFields.ID_SSAID, 8))

    /** Advertising id: a version-4-shaped UUID. */
    fun gaid(seed: ByteArray): String =
        uuidV4Shape(Hkdf.derive(seed, IdentityFields.ID_GAID, 16))

    /** Per-app-set id: a version-4-shaped UUID. */
    fun appSetId(seed: ByteArray): String =
        uuidV4Shape(Hkdf.derive(seed, IdentityFields.ID_APP_SET, 16))

    /**
     * Boot id: a canonical UUID with **no** version or variant bits forced.
     *
     * Android does not guarantee a version-4 boot id, so the third group is left
     * arbitrary on purpose.
     */
    fun bootId(seed: ByteArray): String =
        uuidCanonical(Hkdf.derive(seed, IdentityFields.ID_BOOT, 16))

    /** Device serial: [length] uppercase alphanumerics. */
    fun serial(seed: ByteArray, length: Int = SERIAL_LENGTH): String =
        base32ish(Hkdf.derive(seed, IdentityFields.ID_SERIAL, length))
            .take(length)
            .uppercase(Locale.US)

    /** Wi-Fi MAC address using [oui] as the vendor prefix. */
    fun wifiMac(seed: ByteArray, oui: String): String = mac(seed, IdentityFields.NETWORK_WIFI_MAC, oui)

    /** Bluetooth MAC address using [oui] as the vendor prefix. */
    fun bluetoothMac(seed: ByteArray, oui: String): String = mac(seed, IdentityFields.NETWORK_BT_MAC, oui)

    /**
     * IMEI: [tac] digits + 6 derived digits + a Luhn check digit.
     *
     * The TAC is padded/truncated to 14 digits first and only its first 8 digits
     * are used, so a short TAC is zero-padded rather than rejected.
     */
    fun imei(seed: ByteArray, tac: String = DEFAULT_TAC): String {
        val prefix = tac.filter(Char::isDigit).padEnd(14, '0').take(14)
        val body = prefix.take(8) + digitsFrom(seed, IdentityFields.ID_IMEI + ".body", 6)
        val normalized = body.take(14)
        return normalized + luhnCheckDigit(normalized)
    }

    /** [count] decimal digits derived from [seed] under [label]. */
    fun digitsFrom(seed: ByteArray, label: String, count: Int): String {
        if (count <= 0) return ""
        val bytes = Hkdf.derive(seed, label, count)
        val out = StringBuilder(count)
        for (b in bytes) {
            if (out.length >= count) break
            out.append((b.toInt() and 0xFF) % 10)
        }
        while (out.length < count) out.append('0')
        return out.toString()
    }

    /**
     * Builds a MAC from the OUI and three derived octets.
     *
     * A well-formed OUI keeps its three octets verbatim (only the multicast bit
     * of the first octet is cleared). An unparseable OUI falls back to the
     * locally-administered `02:00:00` prefix.
     */
    private fun mac(seed: ByteArray, label: String, oui: String): String {
        val ouiBytes = parseOui(oui)
        val tail = Hkdf.derive(seed, label, 3)
        val bytes = ByteArray(6)
        val first = if (ouiBytes.isNotEmpty()) ouiBytes[0].toInt() else 2
        bytes[0] = ((first and 0xFC) or 0x02).toByte()
        if (ouiBytes.size == 3) {
            bytes[0] = (ouiBytes[0].toInt() and 0xFE).toByte()
            bytes[1] = ouiBytes[1]
            bytes[2] = ouiBytes[2]
        }
        bytes[3] = tail[0]
        bytes[4] = tail[1]
        bytes[5] = tail[2]
        return bytes.joinToString(":") { String.format(Locale.US, "%02X", it.toInt() and 0xFF) }
    }

    /** Parses `AA:BB:CC`, `AA-BB-CC` or a mixed form into three bytes. */
    private fun parseOui(oui: String): ByteArray {
        val parts = oui.replace("-", ":").split(":").filter { it.isNotBlank() }
        if (parts.size < 3) return ByteArray(0)
        return parts.take(3).map { it.toInt(16).toByte() }.toByteArray()
    }

    /**
     * Luhn check digit for a 14-digit body.
     *
     * Doubling starts at the rightmost digit of the body, which lands it on an
     * even position of the finished 15-digit number.
     */
    fun luhnCheckDigit(number14: String): Char {
        val digits = number14.filter(Char::isDigit)
        var sum = 0
        var double = true
        for (i in digits.indices.reversed()) {
            var d = digits[i] - '0'
            if (double) {
                d *= 2
                if (d > 9) d -= 9
            }
            sum += d
            double = !double
        }
        return ('0' + (10 - (sum % 10)) % 10)
    }

    /** Standard Luhn check: the check digit itself is not doubled. */
    fun isValidLuhn(number: String): Boolean {
        val digits = number.filter(Char::isDigit)
        if (digits.length < 2) return false
        var sum = 0
        var double = false
        for (i in digits.indices.reversed()) {
            var n = digits[i] - '0'
            if (double) {
                n *= 2
                if (n > 9) n -= 9
            }
            sum += n
            double = !double
        }
        return sum % 10 == 0
    }

    /** Forces the version-4 and RFC 4122 variant bits, then renders canonically. */
    fun uuidV4Shape(bytes16: ByteArray): String {
        val b = bytes16.copyOf(16)
        b[6] = ((b[6].toInt() and 0x0F) or 0x40).toByte()
        b[8] = ((b[8].toInt() and 0x3F) or 0x80).toByte()
        return uuidCanonical(b)
    }

    /** Renders 16 bytes as `8-4-4-4-12` lowercase hex. */
    fun uuidCanonical(b: ByteArray): String {
        val h = hex(b.copyOf(16))
        return h.substring(0, 8) + "-" + h.substring(8, 12) + "-" + h.substring(12, 16) +
            "-" + h.substring(16, 20) + "-" + h.substring(20, 32)
    }

    /** Lowercase hex, two characters per byte. */
    fun hex(bytes: ByteArray): String =
        bytes.joinToString("") { String.format(Locale.US, "%02x", it.toInt() and 0xFF) }

    /** One character per byte from a 36-character alphabet (a modulo map, not base32). */
    private fun base32ish(bytes: ByteArray): String =
        bytes.joinToString("") { SERIAL_ALPHABET[(it.toInt() and 0xFF) % SERIAL_ALPHABET.length].toString() }

    /**
     * Picks one of the chassis' stock firmware builds.
     *
     * Returns null when the chassis ships no build options; the engine falls
     * back to the chassis' own build strings in that case.
     */
    fun chooseFirmware(seed: ByteArray, chassis: Chassis): FirmwareBuild? {
        val options = chassis.build.buildOptions
        if (options.isEmpty()) return null
        val index = (Hkdf.derive(seed, "build.firmware", 4)[0].toInt() and 0xFF) % options.size
        return options[index]
    }
}
