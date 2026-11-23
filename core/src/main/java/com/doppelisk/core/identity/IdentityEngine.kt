package com.doppelisk.core.identity

import com.doppelisk.core.model.BuildIdentity
import com.doppelisk.core.model.Chassis
import com.doppelisk.core.model.ChassisRef
import com.doppelisk.core.model.ContainerSpec
import com.doppelisk.core.model.DisplayIdentity
import com.doppelisk.core.model.DoppeliskArtifact
import com.doppelisk.core.model.HardwareIdentity
import com.doppelisk.core.model.Identity
import com.doppelisk.core.model.IdentityFields
import com.doppelisk.core.model.Ids
import com.doppelisk.core.model.LocationPolicy
import com.doppelisk.core.model.NetworkIds
import com.doppelisk.core.model.NetworkPolicy
import com.doppelisk.core.model.PolicySet
import com.doppelisk.core.model.Target
import com.doppelisk.core.model.TelephonyIdentity
import java.util.Locale

/**
 * Turns a chassis plus a 32-byte seed into a complete, coherent artifact.
 *
 * Everything that identifies the *device family* comes verbatim from the
 * chassis; everything that identifies the *individual unit* is derived from the
 * seed. That split is what keeps a profile internally consistent: the
 * fingerprint, build numbers and display metrics always describe the same
 * phone, while the serial, SSAID and MACs differ per profile.
 *
 * The engine is deterministic: the same seed and chassis always produce the same
 * artifact, which is what makes a stored profile reproducible.
 */
object IdentityEngine {

    /** Fallback TAC: a generic test IMEI prefix. The catalog has no per-device TAC. */
    private const val IMEI_TAC = "35824010"

    /** Used when a chassis declares no Wi-Fi OUI. */
    private const val FALLBACK_OUI = "02:00:00"

    /** Label used when the caller does not supply one. */
    private const val DEFAULT_LABEL_PREFIX = "Doppelisk — "

    fun generate(
        seed: ByteArray,
        chassis: Chassis,
        label: String = DEFAULT_LABEL_PREFIX + chassis.name,
        targetPackage: String = "",
        policy: PolicySet = PolicySet(),
        artifactId: String? = null,
        container: ContainerSpec = ContainerSpec(),
        network: NetworkPolicy = NetworkPolicy(),
        location: LocationPolicy = LocationPolicy(),
    ): DoppeliskArtifact {
        // 1. Firmware: prefer the seed-selected build so two profiles of the
        //    same chassis look like different units on the same OTA train.
        val firmware = Generators.chooseFirmware(seed, chassis)
            ?: chassis.build.buildOptions.firstOrNull()
            ?: throw IllegalArgumentException("Chassis ${chassis.id} has no firmware builds")

        // 2. Artifact id: random-looking, but reproducible from the seed.
        val id = artifactId ?: ("dpl_" + Generators.hex(Hkdf.derive(seed, "artifact.id", 6)))

        // 3-4. Unit identifiers.
        val ssaid = Generators.ssaid(seed)
        val gaid = Generators.gaid(seed)
        val appSetId = Generators.appSetId(seed)
        val serial = Generators.serial(seed)
        val bootId = Generators.bootId(seed)
        val imei = Generators.imei(seed, IMEI_TAC)

        // 5. MACs share the chassis OUI but diverge in the last three octets.
        val oui = chassis.hardware.wifiOui.ifBlank { FALLBACK_OUI }
        val wifiMac = Generators.wifiMac(seed, oui)
        val bluetoothMac = Generators.bluetoothMac(seed, oui)

        // 6. `ro.product.name` in a fingerprint is the second segment; the
        //    catalog's `product` is only a fallback for non-standard strings.
        val productSegment = firmware.fingerprint.substringAfter("/").substringBefore("/")
        val product = productSegment.ifBlank { chassis.build.product }

        // 7. Build surface.
        val build = BuildIdentity(
            manufacturer = chassis.build.manufacturer,
            brand = chassis.build.brand,
            model = chassis.build.model,
            device = chassis.build.device,
            product = product,
            board = chassis.build.board,
            hardware = chassis.build.hardware,
            bootloader = firmware.bootloader,
            baseband = chassis.hardware.baseband,
            fingerprint = firmware.fingerprint,
            id = firmware.buildId,
            displayId = firmware.displayId,
            incremental = firmware.incremental,
            type = firmware.buildType,
            tags = firmware.tags,
            host = firmware.host.ifBlank { defaultHost(chassis.build.brand) },
            user = firmware.user.ifBlank { defaultUser(chassis.build.brand) },
            release = firmware.release,
            securityPatch = firmware.securityPatch,
            supportedAbis = chassis.build.supportedAbis,
        )

        // 8. Display: report the panel's maximum refresh rate, which is what
        //    DeviceConfig reports on real hardware.
        val display = DisplayIdentity(
            width = chassis.display.width,
            height = chassis.display.height,
            density = chassis.display.density,
            refresh = chassis.display.refreshRates.maxOrNull() ?: 60,
            screenInches = chassis.display.screenInches,
        )

        // 9. Identifiers.
        val ids = Ids(
            ssaid = ssaid,
            gaid = gaid,
            serial = serial,
            bootId = bootId,
            imei = imei,
            appSetId = appSetId,
        )

        // 10. Telephony: deterministic pick from the chassis' plausible list.
        val mccMnc = chassis.telephony.plausibleMccMnc.firstOrNull()
        val telephony = TelephonyIdentity(
            mcc = mccMnc?.take(3) ?: "",
            mnc = mccMnc?.drop(3) ?: "",
            carrier = chassis.telephony.carriers.firstOrNull() ?: "",
            countryIso = chassis.telephony.countryIso,
        )

        // 11. Hardware surface.
        val hardware = HardwareIdentity(
            soc = chassis.hardware.soc,
            gpuRenderer = chassis.hardware.gpuRenderer,
            cpuHardware = chassis.hardware.cpuHardware,
            kernel = chassis.hardware.kernel,
            cameraIds = chassis.hardware.cameraIds,
        )

        // 12. Network identifiers.
        val networkIds = NetworkIds(
            wifiMac = wifiMac,
            bluetoothMac = bluetoothMac,
            macOuiVendor = chassis.hardware.wifiChipVendor,
        )

        // 13. Provenance records which sections were copied and which derived.
        val identity = Identity(
            build = build,
            ids = ids,
            display = display,
            hardware = hardware,
            networkIds = networkIds,
            telephony = telephony,
            provenance = linkedMapOf(
                "build" to "chassis:${chassis.id}",
                "ids" to "derived:hkdf-sha256",
                "display" to "chassis:${chassis.id}",
            ),
        )

        // 14-17. Everything else is caller policy.
        val target = Target(packageName = targetPackage, minVersionCode = 0, maxVersionCode = null)
        val chassisRef = ChassisRef(id = chassis.id, hash = chassis.hash)

        return DoppeliskArtifact(
            schema = DoppeliskArtifact.CURRENT_SCHEMA,
            id = id,
            label = label,
            target = target,
            chassis = chassisRef,
            identity = identity,
            overrides = IdentityFields.ALL,
            policy = policy,
            container = container,
            network = network,
            location = location,
        )
    }

    /** Build host a brand's OTA images are produced on. */
    private fun defaultHost(brand: String): String = when (brand.lowercase(Locale.ROOT)) {
        "google" -> "abfarm-release"
        "samsung" -> "SWDHUB"
        else -> "buildhost"
    }

    /** Build user a brand's OTA images are produced as. */
    private fun defaultUser(brand: String): String = when (brand.lowercase(Locale.ROOT)) {
        "google" -> "android-build"
        "samsung" -> "dpi"
        else -> "builder"
    }
}
