package com.doppelisk.core.identity

import com.doppelisk.core.model.LocationPolicy
import com.doppelisk.core.model.TelephonyIdentity
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import kotlin.math.abs
import kotlin.math.min

/** Result of [GeoValidator.validate]. */
data class GeoValidation(
    val ok: Boolean,
    val errors: List<String> = emptyList(),
)

/**
 * Checks that a location policy is self-consistent: coordinates in range, an
 * accuracy a real receiver could report, and a time zone that actually matches
 * the longitude.
 *
 * The zone/longitude check is the interesting one: a spoofed fix at
 * `51.5N 0.1W` reported in `Asia/Tokyo` is a classic tell, and this catches it
 * with the mean-solar-time approximation (15° of longitude per hour).
 */
object GeoValidator {

    /** Maximum plausible distance between solar and civil time, in hours. */
    private const val MAX_ZONE_DRIFT_HOURS = 4.0

    fun validate(
        policy: LocationPolicy,
        telephony: TelephonyIdentity,
        timezoneId: String? = null,
        now: Instant = Instant.now(),
    ): GeoValidation {
        val errors = mutableListOf<String>()

        // 1-2. Mode must be known; unknown values are reported but treated as off.
        val rawMode = policy.mode.trim().lowercase(Locale.ROOT)
        if (rawMode !in LocationModes.ALL) {
            errors += "unknown location mode '$rawMode'"
        }
        val mode = LocationModes.normalize(rawMode)

        val lat = policy.lat
        val lon = policy.lon

        if (mode == LocationModes.OFF) {
            // In off mode the coordinates are irrelevant, but an explicitly
            // requested zone must still be a real one.
            if (timezoneId != null) validateZoneId(timezoneId, errors)
        } else {
            // 4. Coordinates.
            if (lat == null || lat !in -90.0..90.0) errors += "latitude missing or out of range"
            if (lon == null || lon !in -180.0..180.0) errors += "longitude missing or out of range"

            // 5. Accuracy.
            val accuracy = policy.accuracy
            if (accuracy != null && (accuracy <= 0.0 || accuracy > 50_000.0)) {
                errors += "accuracy outside plausible range"
            }

            // 6-7. Zone/longitude consistency.
            val effectiveZone = timezoneId ?: GeoTime.zoneFor(telephony.countryIso, null, lat, lon)
            if (lat != null && lon != null && effectiveZone != null) {
                val zone = runCatching { ZoneId.of(effectiveZone) }.getOrNull()
                if (zone == null) {
                    errors += "timezone '$effectiveZone' is not a valid IANA zone"
                } else {
                    val offsetHours = zone.rules.getOffset(now).totalSeconds / 3600.0
                    val lonHours = (((lon / 15.0 + 12.0) % 24.0) + 24.0) % 24.0 - 12.0
                    val zoneHours = (((offsetHours + 12.0) % 24.0) + 24.0) % 24.0 - 12.0
                    val rawDistance = abs(zoneHours - lonHours)
                    val distance = min(rawDistance, 24.0 - rawDistance)
                    if (distance > MAX_ZONE_DRIFT_HOURS) {
                        errors += "timezone '$effectiveZone' is more than 4h from longitude $lon"
                    }
                }
            }
        }

        // 8-9. Telephony country and MCC, checked in every mode.
        if (telephony.countryIso.isNotBlank() && !Regex("[A-Za-z]{2}").matches(telephony.countryIso)) {
            errors += "countryIso is not a two-letter code"
        }
        if (telephony.mcc.isNotBlank() && !Regex("\\d{3}").matches(telephony.mcc)) {
            errors += "MCC is not three digits"
        }

        return GeoValidation(errors.isEmpty(), errors)
    }

    private fun validateZoneId(id: String, errors: MutableList<String>) {
        if (runCatching { ZoneId.of(id) }.isFailure) {
            errors += "timezone '$id' is not a valid IANA zone"
        }
    }
}
