package com.doppelisk.core.model

import kotlinx.serialization.Serializable

/**
 * The full identity handed to a hooked process.
 *
 * Every sub-document has a default so that a partially specified profile still
 * decodes; only the three documents that actually carry the disguise ([build],
 * [ids], [display]) are required.
 */
@Serializable
data class Identity(
    val build: BuildIdentity,
    val ids: Ids,
    val display: DisplayIdentity,
    val hardware: HardwareIdentity = HardwareIdentity(),
    val networkIds: NetworkIds = NetworkIds(),
    val telephony: TelephonyIdentity = TelephonyIdentity(),
    /**
     * Where each field came from, keyed by identity section
     * (`identity`, `build`, ...). Values look like `real-device`,
     * `chassis:<id>` or `derived:hkdf-sha256`.
     */
    val provenance: Map<String, String> = emptyMap(),
)

/**
 * The subset of `android.os.Build` that fingerprinting code reads.
 *
 * `manufacturer`, `brand`, `model`, `device`, `product` and `fingerprint` are
 * required because every real device has them; the rest default to empty and
 * are filled in by the identity engine.
 */
@Serializable
data class BuildIdentity(
    val manufacturer: String,
    val brand: String,
    val model: String,
    val device: String,
    val product: String,
    val board: String = "",
    val hardware: String = "",
    val bootloader: String = "",
    val baseband: String = "",
    val fingerprint: String,
    val id: String = "",
    val displayId: String = "",
    val incremental: String = "",
    val type: String = "user",
    val tags: String = "release-keys",
    val host: String = "",
    val user: String = "",
    val release: String = "",
    val securityPatch: String = "",
    val supportedAbis: List<String> = emptyList(),
)

/**
 * Stable identifiers exposed to apps.
 *
 * [imei] is null on devices and Android versions where apps can no longer read
 * it, and [appSetId] is the per-app-set identifier introduced with Play
 * services. An empty string means "present but blank".
 */
@Serializable
data class Ids(
    val ssaid: String,
    val gaid: String,
    val serial: String,
    val bootId: String,
    val imei: String? = null,
    val appSetId: String = "",
)

/** Display metrics. `screenInches` is a decimal string, not a number. */
@Serializable
data class DisplayIdentity(
    val width: Int,
    val height: Int,
    val density: Int,
    val refresh: Int = 60,
    val screenInches: String = "",
)

/** SoC, GPU and camera surface. */
@Serializable
data class HardwareIdentity(
    val soc: String = "",
    val gpuRenderer: String = "",
    val cpuHardware: String = "",
    val kernel: String = "",
    val cameraIds: List<String> = emptyList(),
)

/** MAC addresses and the vendor prefix they imply. */
@Serializable
data class NetworkIds(
    val wifiMac: String = "",
    val bluetoothMac: String = "",
    val macOuiVendor: String = "",
)

/**
 * Cell identity surface.
 *
 * [phoneNumbers] is indexed by SIM slot: element 0 is slot 1, element 3 is
 * slot 4. A blank entry means "this slot has no number", which is also how a
 * single-SIM profile is represented. Slots are contiguous: a number in slot 3
 * requires one in slots 1 and 2. Numbers are stored in E.164 form so the hook
 * can hand them to `getLine1Number` unchanged.
 *
 * [simSlots] is the number of slots the profile presents. `0` means "derive it
 * from the numbers": one slot when only slot 1 is set, and so on up to
 * [MAX_SIM_SLOTS].
 */
@Serializable
data class TelephonyIdentity(
    val mcc: String = "",
    val mnc: String = "",
    val carrier: String = "",
    val countryIso: String = "",
    val phoneNumbers: List<String> = emptyList(),
    val simSlots: Int = 0,
) {
    /** The E.164 number for [slot] (0-based), or an empty string. */
    fun phoneNumber(slot: Int): String = phoneNumbers.getOrNull(slot).orEmpty()

    /** The number of SIM slots this profile should present. */
    val effectiveSimSlots: Int
        get() {
            if (simSlots in 1..MAX_SIM_SLOTS) return simSlots
            val last = phoneNumbers.indexOfLast { it.isNotBlank() }
            return if (last < 0) 1 else (last + 1).coerceIn(1, MAX_SIM_SLOTS)
        }

    companion object {
        /**
         * The most SIM slots a profile can present.
         *
         * Android supports devices with three physical slots and some ROMs add
         * an eSIM on top, so two was an artificial limit. Four covers every
         * combination without making the editor endless.
         */
        const val MAX_SIM_SLOTS = 4
    }
}
