package com.doppelisk.app.ui.fields

/**
 * What the custom-device sheet collects.
 *
 * A custom device is a chassis that is not in the shipped catalog. It borrows the
 * hardware surface of an existing chassis (SoC, kernel, baseband, sensors) and
 * overrides only the things a person can meaningfully type: the name, the
 * firmware train and the panel. That keeps it coherent by construction instead of
 * producing a device whose build strings and kernel disagree.
 */
data class CustomDeviceSpec(
    val manufacturer: String = "Google",
    val brand: String = "google",
    val model: String = "Custom Phone",
    val device: String = "custom",
    val product: String = "custom",
    val release: String = "15",
    val buildId: String = "AP4A.250105.002",
    val securityPatch: String = "2025-01-05",
    val soc: String = "",
    val gpuRenderer: String = "",
    val width: Int = 1080,
    val height: Int = 2400,
    val density: Int = 420,
    val refresh: Int = 60,
    val screenInches: String = "",
)
