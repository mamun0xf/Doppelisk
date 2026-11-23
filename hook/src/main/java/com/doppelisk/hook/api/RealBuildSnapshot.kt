package com.doppelisk.hook.api

/**
 * The device's real build values, captured before any hook is installed.
 *
 * Hooks need the real values for two reasons: to report evidence ("the app asked
 * for this, we answered that"), and to find the real identity inside a string
 * such as a user agent so it can be substituted. They must be read exactly once,
 * at install time, because after the first `Build` hook the "real" values are
 * already the fake ones.
 */
data class RealBuildSnapshot(
    val manufacturer: String,
    val brand: String,
    val model: String,
    val release: String,
    val buildId: String,
)
