package com.doppelisk.app.ui.fields

import com.doppelisk.app.ManagerRepository
import com.doppelisk.core.container.ContainerModes
import com.doppelisk.core.identity.IdentityEngine
import com.doppelisk.core.model.Chassis
import com.doppelisk.core.model.ChassisBuild
import com.doppelisk.core.model.ChassisDisplay
import com.doppelisk.core.model.ChassisHardware
import com.doppelisk.core.model.ContainerSpec
import com.doppelisk.core.model.DoppeliskArtifact
import com.doppelisk.core.model.FirmwareBuild
import com.doppelisk.core.model.Provenance
import java.security.SecureRandom
import java.util.UUID

/**
 * Creates and regenerates profiles.
 *
 * Every profile starts from a random 32-byte seed and a chassis. The seed is what
 * makes the identifiers unique to the profile; the chassis is what makes them
 * plausible. Regenerating keeps the id, the target and the policy, and re-derives
 * the identity, which is how a profile is moved to a different device without
 * losing its place in the index.
 */
object ProfileDrafts {

    private val random = SecureRandom()

    private fun seed(): ByteArray = ByteArray(32).also(random::nextBytes)

    /** A brand-new profile for [target] on [chassis]. */
    fun newDraft(
        target: ManagerRepository.TargetOption,
        chassis: Chassis,
        containerMode: String = ContainerModes.ISOLATED,
    ): DoppeliskArtifact = IdentityEngine.generate(
        seed = seed(),
        chassis = chassis,
        label = "${target.label} — ${chassis.name}",
        targetPackage = target.packageName,
        container = ContainerSpec(mode = containerMode),
    )

    /** Re-derives [artifact] from [chassis], keeping its identity in the index. */
    fun regenerate(artifact: DoppeliskArtifact, chassis: Chassis): DoppeliskArtifact {
        val oldAutoName = "${artifact.identity.build.manufacturer} ${artifact.identity.build.model}"
        val suffix = " — $oldAutoName"
        val label = if (artifact.label.endsWith(suffix)) {
            artifact.label.removeSuffix(suffix) + " — ${chassis.name}"
        } else {
            artifact.label
        }

        return IdentityEngine.generate(
            seed = seed(),
            chassis = chassis,
            label = label,
            targetPackage = artifact.target.packageName,
            policy = artifact.policy,
            artifactId = artifact.id,
            container = artifact.container,
            network = artifact.network,
            location = artifact.location,
        )
    }

    /** Builds a catalog-less chassis from [spec], borrowing hardware from [base]. */
    fun customChassis(spec: CustomDeviceSpec, base: Chassis): Chassis {
        val brand = spec.brand.trim().ifBlank { "google" }
        val device = spec.device.trim().ifBlank { "custom" }
        val product = spec.product.trim().ifBlank { device }
        val release = spec.release.trim().ifBlank { "15" }
        val buildId = spec.buildId.trim().ifBlank { "AP4A.250105.002" }
        val securityPatch = spec.securityPatch.trim().ifBlank { "2025-01-05" }
        val bootloader = base.build.bootloader.ifBlank { "unknown" }

        val fingerprint = "$brand/$product/$device:$release/$buildId/1:user/release-keys"
        val firmware = FirmwareBuild(
            release = release,
            buildId = buildId,
            incremental = "1",
            securityPatch = securityPatch,
            displayId = buildId,
            fingerprint = fingerprint,
            bootloader = bootloader,
        )

        val build = ChassisBuild(
            manufacturer = spec.manufacturer.trim().ifBlank { "Google" },
            brand = brand,
            model = spec.model.trim().ifBlank { "Custom Phone" },
            device = device,
            product = product,
            board = base.build.board.ifBlank { device },
            hardware = base.build.hardware.ifBlank { device },
            bootloader = bootloader,
            release = release,
            supportedAbis = base.build.supportedAbis,
            buildOptions = listOf(firmware),
        )

        val display = ChassisDisplay(
            width = spec.width.coerceAtLeast(320),
            height = spec.height.coerceAtLeast(320),
            density = spec.density.coerceAtLeast(120),
            refreshRates = listOf(60, spec.refresh.coerceAtLeast(60)).distinct().sorted(),
            screenInches = spec.screenInches.trim().ifBlank { base.display.screenInches },
        )

        val hardware = ChassisHardware(
            soc = spec.soc.trim(),
            gpuRenderer = spec.gpuRenderer.trim(),
            cpuHardware = spec.soc.trim(),
            kernel = base.hardware.kernel,
            baseband = base.hardware.baseband,
            wifiChipVendor = base.hardware.wifiChipVendor,
            wifiOui = base.hardware.wifiOui,
            cameraIds = base.hardware.cameraIds,
            sensors = base.hardware.sensors,
            codecs = base.hardware.codecs,
            codecPrefix = base.hardware.codecPrefix,
        )

        val id = "custom-" + UUID.randomUUID().toString().take(8)
        return Chassis(
            id = id,
            name = "${build.manufacturer} ${build.model}",
            hash = "",
            version = 0,
            provenance = Provenance(
                source = "custom",
                notes = "Created in the Doppelisk manager; hardware defaults from ${base.name}.",
            ),
            build = build,
            display = display,
            hardware = hardware,
            telephony = base.telephony,
        )
    }
}
