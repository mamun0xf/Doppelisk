package com.doppelisk.app.ui

import java.util.Locale

/**
 * Turns a build string into something a person would write.
 *
 * `android.os.Build.MANUFACTURER` is lower case on most devices (`google`,
 * `samsung`), which looks wrong in a list of device names. Only the first
 * character is touched: brands with internal capitals (`OnePlus`, `LG`) keep
 * them.
 */
fun displayManufacturer(value: String): String {
    val trimmed = value.trim()
    if (trimmed.isEmpty() || !trimmed[0].isLowerCase()) return trimmed
    return trimmed[0].uppercase(Locale.ROOT) + trimmed.substring(1)
}

/** Human label for a profile's device: `Google Pixel 8 Pro`. */
fun deviceLabel(manufacturer: String, model: String): String =
    listOf(displayManufacturer(manufacturer), model)
        .filter { it.isNotBlank() }
        .joinToString(" ")
        .ifBlank { "Unknown device" }

/** `6.7"` or an empty string when the catalog did not record a size. */
fun diagonalLabel(inches: String): String = inches.trim().let {
    if (it.isEmpty()) "" else "$it\""
}
