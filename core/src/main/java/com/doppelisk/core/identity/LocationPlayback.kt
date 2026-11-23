package com.doppelisk.core.identity

import com.doppelisk.core.model.LocationPolicy
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Produces the location fix to hand to a hooked process.
 *
 * Three modes trade realism against stability:
 *
 *  - [LocationModes.FIXED] returns the anchor exactly, so a screenshot taken
 *    twice shows the same coordinates;
 *  - [LocationModes.JITTER] adds a sub-100 m wobble that changes every second,
 *    which is what a stationary real receiver looks like;
 *  - [LocationModes.ROUTE] walks a ~178 m circle at walking pace, for apps that
 *    require movement.
 *
 * All three are deterministic functions of the artifact id and the clock, so two
 * processes hooking the same app agree without sharing state.
 */
object LocationPlayback {

    /** Metres per degree of latitude (longitude is scaled by cos(latitude)). */
    private const val METERS_PER_DEGREE = 111_320.0

    /** Default accuracy when the policy does not pin one. */
    private const val DEFAULT_ACCURACY = 15.0

    /** Walking pace used by [LocationModes.ROUTE]. */
    private const val ROUTE_SPEED = 1.4f

    /** Route circle radius, in degrees. */
    private const val ROUTE_RADIUS_DEGREES = 0.0016

    /** Route bucket size: the circle repeats every 10 minutes. */
    private const val ROUTE_BUCKET_SECONDS = 600L

    /**
     * Returns the fix for [policy] at [now], or null when playback is off or the
     * anchor is missing.
     *
     * @throws IllegalArgumentException when the anchor is outside the valid
     *   latitude/longitude ranges.
     */
    fun fix(
        policy: LocationPolicy,
        doppeliskId: String = "",
        now: Long = System.currentTimeMillis(),
        provider: String = "gps",
    ): LocationFix? {
        val mode = LocationModes.normalize(policy.mode)
        if (mode == LocationModes.OFF) return null

        val lat = policy.lat ?: return null
        val lon = policy.lon ?: return null
        if (lat !in -90.0..90.0 || lon !in -180.0..180.0) {
            throw IllegalArgumentException("invalid location anchor")
        }

        val accuracy = max(policy.accuracy ?: DEFAULT_ACCURACY, 1.0).toFloat()

        if (mode == LocationModes.FIXED) {
            return LocationFix(lat, lon, accuracy, 0.0, 0f, 0f, now, provider, mocked = false)
        }

        // Shared basis: an unsigned per-(id, second) value in 0..3599.
        val bucket = now / 1000
        val seed = doppeliskId.hashCode().toLong() * 31 xor bucket
        val angle = ((seed ushr 1) % 3600) / 3600.0 * 2.0 * PI
        val radiusMeters = max(accuracy.toDouble(), 5.0) * 0.35
        val dLat = radiusMeters / METERS_PER_DEGREE * sin(angle)
        val dLon = radiusMeters / (max(cos(Math.toRadians(lat)), 0.1) * METERS_PER_DEGREE) * cos(angle)

        if (mode == LocationModes.JITTER) {
            return LocationFix(
                latitude = clampLat(lat + dLat),
                longitude = wrapLon(lon + dLon),
                accuracy = accuracy,
                altitude = 0.0,
                speed = 0f,
                bearing = 0f,
                time = now,
                provider = provider,
                mocked = false,
            )
        }

        // route
        val routeAngle = (bucket % ROUTE_BUCKET_SECONDS) / ROUTE_BUCKET_SECONDS.toDouble() * 2.0 * PI
        val routeLat = lat + sin(routeAngle) * ROUTE_RADIUS_DEGREES
        val routeLon = lon + cos(routeAngle) * ROUTE_RADIUS_DEGREES /
            max(cos(Math.toRadians(lat)), 0.1)
        val bearing = ((Math.toDegrees(routeAngle) + 90.0) % 360.0).toFloat()
        return LocationFix(
            latitude = clampLat(routeLat),
            longitude = wrapLon(routeLon),
            accuracy = min(accuracy, 10.0f),
            altitude = 0.0,
            speed = ROUTE_SPEED,
            bearing = bearing,
            time = now,
            provider = provider,
            mocked = false,
        )
    }

    private fun clampLat(lat: Double): Double = lat.coerceIn(-90.0, 90.0)

    private fun wrapLon(lon: Double): Double {
        var value = lon
        while (value > 180.0) value -= 360.0
        while (value < -180.0) value += 360.0
        return value
    }
}
