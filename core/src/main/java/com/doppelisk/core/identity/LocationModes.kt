package com.doppelisk.core.identity

import java.util.Locale

/**
 * The location playback modes.
 *
 * Stored as strings because they travel inside profiles; [normalize] is a
 * whitelist, so an unknown value from a newer build degrades to [OFF] instead of
 * making the profile unusable.
 */
object LocationModes {

    /** Leave the real location alone. */
    const val OFF = "off"

    /** Always report one fixed point. */
    const val FIXED = "fixed"

    /** Report the fixed point plus a small per-second wobble. */
    const val JITTER = "jitter"

    /** Walk a small circle to look like a moving device. */
    const val ROUTE = "route"

    val ALL: List<String> = listOf(OFF, FIXED, JITTER, ROUTE)

    /** Trims and lowercases [mode], falling back to [OFF] for anything unknown. */
    fun normalize(mode: String?): String = when (mode?.trim()?.lowercase(Locale.ROOT)) {
        FIXED -> FIXED
        JITTER -> JITTER
        ROUTE -> ROUTE
        else -> OFF
    }
}
