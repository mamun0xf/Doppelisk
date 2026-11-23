package com.doppelisk.core.model

import kotlinx.serialization.Serializable

/**
 * One hardware definition from the chassis catalog.
 *
 * A chassis is *not* a finished identity: it describes a real device family
 * (build strings, display metrics, hardware strings, plausible carriers) from
 * which the identity engine derives per-profile values.
 *
 * [hash] is `sha256:<hex>` over the canonical JSON of the entry with `hash`
 * blanked, and [provenance] records where the numbers came from.
 */
@Serializable
data class Chassis(
    val id: String,
    val name: String,
    val hash: String = "",
    val version: Int = 1,
    val provenance: Provenance = Provenance(),
    val build: ChassisBuild,
    val display: ChassisDisplay,
    val hardware: ChassisHardware = ChassisHardware(),
    val telephony: ChassisTelephony = ChassisTelephony(),
)

/** Where a catalog entry or derived value came from. */
@Serializable
data class Provenance(
    val source: String = "",
    val notes: String = "",
)

/**
 * Build strings common to every firmware revision of a chassis.
 *
 * [buildOptions] lists the stock firmware builds the chassis shipped with; the
 * engine picks one and fills the volatile fields from it.
 */
@Serializable
data class ChassisBuild(
    val manufacturer: String,
    val brand: String,
    val model: String,
    val device: String,
    val product: String,
    val board: String = "",
    val hardware: String = "",
    val bootloader: String = "",
    val release: String,
    val supportedAbis: List<String> = emptyList(),
    val buildOptions: List<FirmwareBuild> = emptyList(),
)

/** One stock firmware revision for a chassis. */
@Serializable
data class FirmwareBuild(
    val release: String,
    val buildId: String,
    val incremental: String,
    val securityPatch: String,
    val displayId: String,
    val fingerprint: String,
    val bootloader: String,
    val buildType: String = "user",
    val tags: String = "release-keys",
    val host: String = "",
    val user: String = "",
)

/** Display metrics of a chassis. [refreshRates] is the panel's supported set. */
@Serializable
data class ChassisDisplay(
    val width: Int,
    val height: Int,
    val density: Int,
    val refreshRates: List<Int> = listOf(60),
    val screenInches: String = "",
)

/** Hardware surface of a chassis. */
@Serializable
data class ChassisHardware(
    val soc: String = "",
    val gpuRenderer: String = "",
    val cpuHardware: String = "",
    val kernel: String = "",
    val baseband: String = "",
    val wifiChipVendor: String = "",
    val wifiOui: String = "",
    val cameraIds: List<String> = emptyList(),
    val sensors: List<String> = emptyList(),
    val codecs: List<String> = emptyList(),
    val codecPrefix: String = "",
)

/** Plausible carrier surface for a chassis. */
@Serializable
data class ChassisTelephony(
    /** Accepted `mcc`+`mnc` concatenations, e.g. `310260`. */
    val plausibleMccMnc: List<String> = emptyList(),
    val carriers: List<String> = emptyList(),
    val countryIso: String = "",
    val timezone: String = "",
)

/** The shipped `assets/chassis/catalog.json` document. */
@Serializable
data class CatalogFile(
    val version: Int = 1,
    val provenance: Provenance = Provenance(),
    val chassis: List<Chassis>,
)
