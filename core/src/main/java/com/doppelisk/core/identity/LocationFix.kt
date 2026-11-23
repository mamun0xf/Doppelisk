package com.doppelisk.core.identity

/**
 * A synthetic location fix, shaped like the platform's `Location` object.
 *
 * [mocked] is always false: a fix that reports itself as mocked is the easiest
 * possible detection signal, so the hooks never set it.
 */
data class LocationFix(
    val latitude: Double,
    val longitude: Double,
    val accuracy: Float,
    val altitude: Double,
    val speed: Float,
    val bearing: Float,
    val time: Long,
    val provider: String,
    val mocked: Boolean = false,
)
