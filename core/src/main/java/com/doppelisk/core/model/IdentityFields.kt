package com.doppelisk.core.model

import kotlinx.serialization.Serializable

/**
 * The canonical field paths of an [Identity], in serialization order.
 *
 * These strings are the vocabulary of `overrides`: a profile either pins an
 * explicit subset of them, or (when `overrides == null`) treats every field as
 * pinned. Keeping the list in one place means the generator, the manager UI and
 * the validator all agree on the spelling.
 */
object IdentityFields {

    const val BUILD_MANUFACTURER = "build.manufacturer"
    const val BUILD_BRAND = "build.brand"
    const val BUILD_MODEL = "build.model"
    const val BUILD_DEVICE = "build.device"
    const val BUILD_PRODUCT = "build.product"
    const val BUILD_BOARD = "build.board"
    const val BUILD_HARDWARE = "build.hardware"
    const val BUILD_BOOTLOADER = "build.bootloader"
    const val BUILD_BASEBAND = "build.baseband"
    const val BUILD_FINGERPRINT = "build.fingerprint"
    const val BUILD_ID = "build.id"
    const val BUILD_DISPLAY_ID = "build.displayId"
    const val BUILD_INCREMENTAL = "build.incremental"
    const val BUILD_RELEASE = "build.release"
    const val BUILD_SECURITY_PATCH = "build.securityPatch"
    const val BUILD_TYPE = "build.type"
    const val BUILD_TAGS = "build.tags"
    const val BUILD_HOST = "build.host"
    const val BUILD_USER = "build.user"
    const val BUILD_ABIS = "build.supportedAbis"

    const val ID_SSAID = "ids.ssaid"
    const val ID_GAID = "ids.gaid"
    const val ID_APP_SET = "ids.appSetId"
    const val ID_SERIAL = "ids.serial"
    const val ID_BOOT = "ids.bootId"
    const val ID_IMEI = "ids.imei"

    const val DISPLAY_WIDTH = "display.width"
    const val DISPLAY_HEIGHT = "display.height"
    const val DISPLAY_DENSITY = "display.density"
    const val DISPLAY_REFRESH = "display.refresh"
    const val DISPLAY_INCHES = "display.screenInches"

    const val HARDWARE_SOC = "hardware.soc"
    const val HARDWARE_GPU = "hardware.gpuRenderer"
    const val HARDWARE_CPU = "hardware.cpuHardware"
    const val HARDWARE_KERNEL = "hardware.kernel"
    const val HARDWARE_CAMERAS = "hardware.cameraIds"

    const val NETWORK_WIFI_MAC = "networkIds.wifiMac"
    const val NETWORK_BT_MAC = "networkIds.bluetoothMac"
    const val NETWORK_OUI = "networkIds.macOuiVendor"

    const val TELEPHONY_MCC = "telephony.mcc"
    const val TELEPHONY_MNC = "telephony.mnc"
    const val TELEPHONY_CARRIER = "telephony.carrier"
    const val TELEPHONY_COUNTRY = "telephony.countryIso"
    const val TELEPHONY_PHONE_1 = "telephony.phone1"
    const val TELEPHONY_PHONE_2 = "telephony.phone2"
    const val TELEPHONY_SIM_SLOTS = "telephony.simSlots"

    val BUILD: List<String> = listOf(
        BUILD_MANUFACTURER, BUILD_BRAND, BUILD_MODEL, BUILD_DEVICE, BUILD_PRODUCT,
        BUILD_BOARD, BUILD_HARDWARE, BUILD_BOOTLOADER, BUILD_BASEBAND, BUILD_FINGERPRINT,
        BUILD_ID, BUILD_DISPLAY_ID, BUILD_INCREMENTAL, BUILD_RELEASE, BUILD_SECURITY_PATCH,
        BUILD_TYPE, BUILD_TAGS, BUILD_HOST, BUILD_USER, BUILD_ABIS,
    )

    val IDS: List<String> = listOf(ID_SSAID, ID_GAID, ID_APP_SET, ID_SERIAL, ID_BOOT, ID_IMEI)

    val DISPLAY: List<String> = listOf(
        DISPLAY_WIDTH, DISPLAY_HEIGHT, DISPLAY_DENSITY, DISPLAY_REFRESH, DISPLAY_INCHES,
    )

    val HARDWARE: List<String> = listOf(
        HARDWARE_SOC, HARDWARE_GPU, HARDWARE_CPU, HARDWARE_KERNEL, HARDWARE_CAMERAS,
    )

    val NETWORK_IDS: List<String> = listOf(NETWORK_WIFI_MAC, NETWORK_BT_MAC, NETWORK_OUI)

    val TELEPHONY: List<String> = listOf(
        TELEPHONY_MCC, TELEPHONY_MNC, TELEPHONY_CARRIER, TELEPHONY_COUNTRY,
        TELEPHONY_PHONE_1, TELEPHONY_PHONE_2, TELEPHONY_SIM_SLOTS,
    )

    val ALL: List<String> = BUILD + IDS + DISPLAY + HARDWARE + NETWORK_IDS + TELEPHONY

    private val ALL_SET: Set<String> = ALL.toHashSet()

    /** True when [key] names an identity field rather than a policy knob. */
    fun isIdentityField(key: String): Boolean = key in ALL_SET

    /**
     * Orders [keys] into canonical order, dropping duplicates and keeping any
     * unknown keys at the end in sorted order so a newer build's overrides are
     * never silently lost.
     */
    fun canonical(keys: Collection<String>): List<String> {
        val wanted = keys.toHashSet()
        val known = ALL.filter { it in wanted }
        val extras = wanted.filterNot { it in ALL_SET }.sorted()
        return known + extras
    }
}

/**
 * True when [key] should be taken from the stored identity rather than
 * regenerated.
 *
 * A `null` override list means the artifact is fully pinned; a list means only
 * the listed fields are.
 */
fun DoppeliskArtifact.applies(key: String): Boolean {
    val selected = overrides ?: return true
    return key in selected
}

/** True when every identity field is pinned. */
fun DoppeliskArtifact.isFullyOverridden(): Boolean = overrides == null

/**
 * Pins [key] as well.
 *
 * Pinning a field of a fully pinned artifact is a no-op; pinning one of a
 * partially pinned artifact adds it. Non-identity keys are ignored.
 */
fun DoppeliskArtifact.withOverride(key: String): DoppeliskArtifact {
    if (!IdentityFields.isIdentityField(key)) return this
    val selected = overrides?.toMutableSet() ?: IdentityFields.ALL.toMutableSet()
    selected.add(key)
    return copy(overrides = IdentityFields.canonical(selected))
}

/** Unpins [key], leaving the other pins in place. */
fun DoppeliskArtifact.withoutOverride(key: String): DoppeliskArtifact {
    if (!IdentityFields.isIdentityField(key)) return this
    // A fully pinned artifact is represented as `null`; unpinning one field has
    // to materialise the full list minus that field.
    val selected = overrides?.toMutableSet() ?: IdentityFields.ALL.toMutableSet()
    selected.remove(key)
    return copy(overrides = IdentityFields.canonical(selected))
}

/** Pins every identity field. */
fun DoppeliskArtifact.withAllOverrides(): DoppeliskArtifact = copy(overrides = IdentityFields.ALL)

/** Pins nothing: the whole identity is regenerated from the seed. */
fun DoppeliskArtifact.withNoOverrides(): DoppeliskArtifact = copy(overrides = emptyList())
