package com.doppelisk.core.identity

import com.doppelisk.core.model.Chassis
import com.doppelisk.core.model.DoppeliskArtifact
import com.doppelisk.core.model.TelephonyIdentity
import java.util.Locale

/** Result of [CoherenceValidator.validate]. */
data class ValidationResult(val errors: List<String>) {
    val ok: Boolean get() = errors.isEmpty()
}

/**
 * Cross-checks an artifact for internal contradictions.
 *
 * This is the safety net behind hand-edited profiles: the generator's own output
 * always passes, so any error here means someone pinned a field that no longer
 * agrees with the rest of the disguise (a Pixel fingerprint with a Samsung model,
 * a 1080p display on a chassis that reports 1440p, a Wi-Fi MAC from another
 * vendor).
 *
 * [validate] takes the chassis optionally; without it the cross-checks against
 * the catalog are skipped and only self-consistency is verified.
 */
object CoherenceValidator {

    private val FINGERPRINT = Regex(
        "^([^/]+)/([^/]+)/([^:]+):([^/]+)/([^/]+)/([^:]+):([^/]+)/([^/]+)$",
    )
    private val SSAID = Regex("(?i)^[0-9a-f]{16}$")
    private val UUID_V4 = Regex("(?i)^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")
    private val UUID_ANY = Regex("(?i)^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
    private val MAC = Regex("(?i)^([0-9A-F]{2}:){5}[0-9A-F]{2}$")
    private val DATE = Regex("\\d{4}-\\d{2}-\\d{2}")

    fun validate(artifact: DoppeliskArtifact, chassis: Chassis? = null): ValidationResult {
        val errors = mutableListOf<String>()
        val build = artifact.identity.build
        val ids = artifact.identity.ids
        val display = artifact.identity.display
        val hardware = artifact.identity.hardware
        val telephony = artifact.identity.telephony

        // A. Fingerprint must parse and agree with the build strings it encodes.
        val match = FINGERPRINT.matchEntire(build.fingerprint)
        if (match == null) {
            errors += "fingerprint does not parse: ${build.fingerprint}"
        } else {
            val (fBrand, fProduct, fDevice, fRelease, fId, fIncremental, fType, fTags) = match.destructured
            if (!fBrand.equals(build.brand, ignoreCase = true)) {
                errors += "fingerprint brand '$fBrand' != build.brand '${build.brand}'"
            }
            if (fProduct != build.product) {
                errors += "fingerprint product '$fProduct' != build.product '${build.product}'"
            }
            if (fDevice != build.device) {
                errors += "fingerprint device '$fDevice' != build.device '${build.device}'"
            }
            if (fRelease != build.release) {
                errors += "fingerprint release '$fRelease' != build.release '${build.release}'"
            }
            if (build.id.isNotBlank() && fId != build.id) {
                errors += "fingerprint id '$fId' != build.id '${build.id}'"
            }
            if (build.incremental.isNotBlank() && fIncremental != build.incremental) {
                errors += "fingerprint incremental '$fIncremental' != build.incremental '${build.incremental}'"
            }
            if (fType != build.type) {
                errors += "fingerprint type '$fType' != build.type '${build.type}'"
            }
            if (fTags != build.tags) {
                errors += "fingerprint tags '$fTags' != build.tags '${build.tags}'"
            }
        }

        // B. Cross-checks against the chassis the profile claims to be derived from.
        if (chassis != null) {
            val cb = chassis.build
            if (build.model != cb.model) errors += "build.model '${build.model}' != chassis '${cb.model}'"
            if (!build.brand.equals(cb.brand, ignoreCase = true)) {
                errors += "build.brand '${build.brand}' != chassis '${cb.brand}'"
            }
            if (!build.manufacturer.equals(cb.manufacturer, ignoreCase = true)) {
                errors += "build.manufacturer '${build.manufacturer}' != chassis '${cb.manufacturer}'"
            }
            if (build.device != cb.device) errors += "build.device '${build.device}' != chassis '${cb.device}'"

            val ch = chassis.hardware
            if (ch.soc.isNotBlank() && hardware.soc.isNotBlank() && hardware.soc != ch.soc) {
                errors += "hardware.soc '${hardware.soc}' != chassis '${ch.soc}'"
            }
            if (ch.gpuRenderer.isNotBlank() && hardware.gpuRenderer.isNotBlank() &&
                hardware.gpuRenderer != ch.gpuRenderer
            ) {
                errors += "hardware.gpuRenderer '${hardware.gpuRenderer}' != chassis '${ch.gpuRenderer}'"
            }

            val cd = chassis.display
            if (cd.width > 0 && display.width != cd.width) {
                errors += "display.width ${display.width} != chassis ${cd.width}"
            }
            if (cd.height > 0 && display.height != cd.height) {
                errors += "display.height ${display.height} != chassis ${cd.height}"
            }
            if (cd.density > 0 && display.density != cd.density) {
                errors += "display.density ${display.density} != chassis ${cd.density}"
            }

            if (ch.cameraIds.isNotEmpty() && hardware.cameraIds.isNotEmpty() &&
                hardware.cameraIds != ch.cameraIds
            ) {
                errors += "hardware.cameraIds do not match chassis catalog"
            }
            if (ch.kernel.isNotBlank() && hardware.kernel.isBlank()) {
                errors += "hardware.kernel is empty but chassis defines one"
            }
        }

        // C. A build number is required.
        if (build.displayId.isBlank()) errors += "build.displayId (build number) is empty"

        // D. Security patch format, and a plausible year when we know the release.
        if (!DATE.matches(build.securityPatch)) {
            errors += "securityPatch not YYYY-MM-DD: '${build.securityPatch}'"
        } else if (match != null && build.release.toIntOrNull() != null) {
            val year = build.securityPatch.substring(0, 4).toIntOrNull()
            if (year != null && (year < 2008 || year > 2035)) {
                errors += "securityPatch year implausible: ${build.securityPatch}"
            }
        }

        // E-H. Identifier shapes.
        if (!SSAID.matches(ids.ssaid)) errors += "ssaid must be 16 hex chars: '${ids.ssaid}'"
        if (!UUID_V4.matches(ids.gaid)) errors += "gaid must be UUIDv4: '${ids.gaid}'"
        if (!UUID_ANY.matches(ids.bootId)) errors += "bootId must be UUID: '${ids.bootId}'"
        if (ids.appSetId.isNotBlank() && !UUID_V4.matches(ids.appSetId)) {
            errors += "appSetId must be UUIDv4: '${ids.appSetId}'"
        }

        // I. IMEI, when present.
        val imei = ids.imei
        if (imei != null) {
            val digits = imei.filter(Char::isDigit)
            if (digits.length != 15) {
                errors += "imei must be 15 digits: '$imei'"
            } else if (!Generators.isValidLuhn(digits)) {
                errors += "imei fails Luhn: '$imei'"
            }
        }

        // J. Wi-Fi MAC shape, unicast bit and vendor prefix.
        val wifi = artifact.identity.networkIds.wifiMac
        if (wifi.isNotBlank()) {
            if (!MAC.matches(wifi)) {
                errors += "wifiMac format invalid: '$wifi'"
            }
            val first = wifi.take(2).toIntOrNull(16)
            if (first != null && (first and 1) == 1) {
                errors += "wifiMac must be unicast: '$wifi'"
            }
            if (chassis != null && chassis.hardware.wifiOui.isNotBlank()) {
                val expected = normOui(chassis.hardware.wifiOui)
                val actual = normOui(wifi.substring(0, 8))
                if (expected != actual) {
                    errors += "wifiMac OUI '$actual' != chassis wifiOui '$expected'"
                }
            }
        }

        // K. Display positivity.
        if (display.width <= 0 || display.height <= 0) errors += "display dimensions must be positive"
        if (display.density <= 0) errors += "display density must be positive"

        // L. Screen size, when known.
        if (display.screenInches.isNotBlank()) {
            val inches = display.screenInches.toDoubleOrNull()
            if (inches == null || inches !in 3.0..15.0) {
                errors += "display.screenInches must be a number between 3 and 15: '${display.screenInches}'"
            }
        }

        // M-N. Telephony.
        if (telephony.mcc.isNotBlank() && !Regex("\\d{3}").matches(telephony.mcc)) {
            errors += "mcc must be 3 digits: '${telephony.mcc}'"
        }
        if (telephony.mnc.isNotBlank() && !Regex("\\d{2,3}").matches(telephony.mnc)) {
            errors += "mnc must be 2-3 digits: '${telephony.mnc}'"
        }

        // Phone numbers are stored in E.164 form so the hook can return them
        // without guessing a country code. A blank slot is valid: it means the
        // SIM has no number, which is a normal state on many networks.
        val maxSlots = TelephonyIdentity.MAX_SIM_SLOTS
        if (telephony.phoneNumbers.size > maxSlots) {
            errors += "a profile can carry at most $maxSlots phone numbers"
        }
        for ((index, number) in telephony.phoneNumbers.withIndex()) {
            if (number.isBlank()) continue
            if (!Regex("\\+[1-9]\\d{6,14}").matches(number.trim())) {
                errors += "telephony.phone${index + 1} must be E.164, e.g. +8801XXXXXXXXX: '$number'"
            }
        }
        val presentNumbers = telephony.phoneNumbers.filter { it.isNotBlank() }
        if (presentNumbers.size != presentNumbers.toSet().size) {
            errors += "phone numbers must be unique"
        }

        // Slots are contiguous: SIM 3 cannot be populated while SIM 2 is empty,
        // because the framework's slot indices are dense and an app that walks
        // them would skip the fake entry.
        val lastNumber = telephony.phoneNumbers.indexOfLast { it.isNotBlank() }
        for (slot in 0..lastNumber) {
            if (telephony.phoneNumbers.getOrNull(slot).isNullOrBlank()) {
                errors += "SIM ${slot + 1} has no number but a later SIM does"
                break
            }
        }
        if (telephony.simSlots in 1..maxSlots && lastNumber >= telephony.simSlots) {
            errors += "simSlots is ${telephony.simSlots} but SIM ${lastNumber + 1} has a number"
        }
        if (telephony.simSlots !in 0..maxSlots) {
            errors += "telephony.simSlots must be 0 (auto) or 1..$maxSlots: '${telephony.simSlots}'"
        }

        // O. Network policy.
        val network = NetworkPolicyValidator.validate(artifact.network)
        if (!network.ok) errors += network.errors.map { "network: $it" }

        // P. Location policy.
        val geo = GeoValidator.validate(
            artifact.location,
            telephony,
            timezoneId = artifact.location.timezone,
        )
        if (!geo.ok) errors += geo.errors.map { "location: $it" }

        // Q. ABIs must match the chassis exactly, order included.
        if (chassis != null &&
            chassis.build.supportedAbis.isNotEmpty() &&
            build.supportedAbis.isNotEmpty() &&
            build.supportedAbis != chassis.build.supportedAbis
        ) {
            errors += "supportedAbis do not match chassis"
        }

        return ValidationResult(errors)
    }

    private fun normOui(value: String): String = value.uppercase(Locale.US).replace("-", ":")
}
