package com.doppelisk.app.ui.fields

import com.doppelisk.core.container.ContainerModes
import com.doppelisk.core.identity.LocationModes
import com.doppelisk.core.model.DoppeliskArtifact
import com.doppelisk.core.model.Identity
import com.doppelisk.core.model.IdentityFields
import com.doppelisk.core.model.TelephonyIdentity
import com.doppelisk.core.model.applies
import com.doppelisk.core.model.withOverride
import com.doppelisk.core.model.withoutOverride
import java.util.Locale

/**
 * The profile editor's field model.
 *
 * A profile is a nested document, but editing it is flat: the user sees labelled
 * rows grouped by what they describe. This file is the translation layer between
 * the two, and it is the only place that knows which dotted key maps to which
 * nested property.
 *
 * The `overrides` list is the other half of the model. A field is either pinned
 * (`isOverridden`) or inherited from the real device; the editor shows inherited
 * values greyed out so it is always obvious what the profile actually changes.
 */
object ProfileFields {

    private val CONTAINER_OPTIONS = ContainerModes.ALL
    private val NETWORK_OPTIONS = listOf("direct", "socks5", "block")
    private val IPV6_OPTIONS = listOf("block", "direct", "proxy")
    private val VPN_OPTIONS = listOf("default", "off", "on", "reversed")
    private val LOCATION_OPTIONS = LocationModes.ALL
    private val BUILD_TYPES = listOf("user", "userdebug", "eng")
    private val BUILD_TAGS = listOf("release-keys", "test-keys", "dev-keys")
    private val POLICY_PRESETS = listOf("stealth", "balanced", "aggressive")

    /**
     * Every category, with values taken from [artifact].
     *
     * When [realArtifact] is supplied, fields the profile does not pin show the
     * real device's value and are marked `isDefault`, which is what the editor
     * renders as a grey placeholder.
     */
    fun categoriesFor(
        artifact: DoppeliskArtifact,
        existingProfileCount: Int,
        screenInches: String,
        realArtifact: DoppeliskArtifact? = null,
    ): List<FieldCategory> {
        val categories = baseCategoriesFor(artifact, existingProfileCount, screenInches)
        if (realArtifact == null) {
            return categories.map { category ->
                category.copy(
                    fields = category.fields.map { field ->
                        if (IdentityFields.isIdentityField(field.key)) {
                            field.copy(isOverridden = artifact.applies(field.key))
                        } else {
                            field
                        }
                    },
                )
            }
        }

        val realValues = baseCategoriesFor(realArtifact, existingProfileCount, screenInches)
            .flatMap { it.fields }
            .associateBy { it.key }
        val regionArtifact = effectiveRegionArtifact(artifact, realArtifact)

        return categories.map { category ->
            category.copy(
                fields = category.fields.map { field ->
                    when {
                        field.key == "region.country" ->
                            field.copy(value = RegionPresets.describe(regionArtifact))

                        IdentityFields.isIdentityField(field.key) -> {
                            val overridden = artifact.applies(field.key)
                            field.copy(
                                value = if (overridden) field.value else realValues[field.key]?.value ?: "",
                                isDefault = !overridden,
                                isOverridden = overridden,
                            )
                        }

                        else -> field
                    }
                },
            )
        }
    }

    /**
     * Writes [raw] into [artifact] at [key].
     *
     * Identity fields are pinned as a side effect: typing a value is an explicit
     * statement that the profile should use it, so leaving the field unpinned
     * would make the edit invisible to the hook side. Non-identity knobs
     * (container, network, location, label) are always part of the profile.
     */
    fun applyField(artifact: DoppeliskArtifact, key: String, raw: String): DoppeliskArtifact {
        val value = raw.trim()
        val updated = applyRaw(artifact, key, value) ?: return artifact
        return if (IdentityFields.isIdentityField(key)) updated.withOverride(key) else updated
    }

    /** Restores [key] to the real device's value and unpins it. */
    fun resetFieldToDefault(
        artifact: DoppeliskArtifact,
        realArtifact: DoppeliskArtifact,
        key: String,
        existingProfileCount: Int,
        screenInches: String,
    ): DoppeliskArtifact {
        require(IdentityFields.isIdentityField(key)) {
            "Only identity fields can be reset to Default."
        }

        val realValue = baseCategoriesFor(realArtifact, existingProfileCount, screenInches)
            .flatMap { it.fields }
            .firstOrNull { it.key == key }
            ?.value
            ?: error("Unknown field $key")

        return applyField(artifact, key, realValue).withoutOverride(key)
    }

    /** A human message when [raw] is not acceptable for [key], or null. */
    fun validateField(key: String, raw: String, countryIso: String = ""): String? {
        val value = raw.trim()

        return when (key) {
            in IdentityFields.TELEPHONY_PHONES -> PhoneNumbers.validate(value, countryIso)

            IdentityFields.TELEPHONY_SIM_SLOTS -> {
                val max = TelephonyIdentity.MAX_SIM_SLOTS
                if (value.lowercase(Locale.ROOT) in (setOf("auto") + (1..max).map(Int::toString))) {
                    null
                } else {
                    "Choose auto, or 1..$max."
                }
            }

            IdentityFields.DISPLAY_REFRESH -> {
                val number = value.toIntOrNull() ?: return "Enter a whole number."
                if (number in 1..360) null else "Refresh must be between 1 and 360 Hz."
            }

            IdentityFields.DISPLAY_DENSITY -> {
                val number = value.toIntOrNull() ?: return "Enter a whole number."
                if (number in 72..2000) null else "Density must be between 72 and 2000 dpi."
            }

            IdentityFields.DISPLAY_INCHES -> {
                val number = toDoubleLenient(value) ?: return "Enter a number."
                if (number in 3.0..15.0) null else "Screen size must be between 3 and 15 inches."
            }

            IdentityFields.DISPLAY_WIDTH, IdentityFields.DISPLAY_HEIGHT -> {
                val number = value.toIntOrNull() ?: return "Enter a whole number."
                if (number in 240..10000) null else "Value must be between 240 and 10000."
            }

            "location.lat" -> {
                if (value.isEmpty()) return null
                val number = toDoubleLenient(value) ?: return "Enter a number."
                if (number in -90.0..90.0) null else "Latitude must be between -90 and 90."
            }

            "location.lon" -> {
                if (value.isEmpty()) return null
                val number = toDoubleLenient(value) ?: return "Enter a number."
                if (number in -180.0..180.0) null else "Longitude must be between -180 and 180."
            }

            "location.accuracy" -> {
                if (value.isEmpty()) return null
                val number = toDoubleLenient(value) ?: return "Enter a number."
                if (number >= 0.0) null else "Accuracy cannot be negative."
            }

            else -> null
        }
    }

    /** The label shown in a dropdown for [value]. */
    fun formatOption(value: String): String = when (value.lowercase(Locale.ROOT)) {
        "release-keys" -> "Release keys"
        "test-keys" -> "Test keys"
        "dev-keys" -> "Dev keys"
        "userdebug" -> "Userdebug"
        "socks5" -> "SOCKS5"
        "default" -> "Default (real)"
        "on" -> "Always on"
        "off" -> "Always off"
        "reversed" -> "Reversed"
        "stealth" -> "Stealth"
        "balanced" -> "Balanced"
        "aggressive" -> "Aggressive"
        else -> value.replaceFirstChar { it.uppercase(Locale.ROOT) }
    }

    // ------------------------------------------------------------- internals

    /**
     * Phone-number rows for the slots this profile actually presents.
     *
     * At least two rows stay visible so a single-SIM profile can grow into a
     * second one without hunting for a control, and the list grows to the
     * highest populated slot or the declared slot count, whichever is larger.
     * Changing "SIM slots" re-renders the category, which is what makes the
     * rows appear and disappear.
     */
    private fun phoneRows(telephony: TelephonyIdentity): Array<FieldSpec> {
        val highest = telephony.phoneNumbers.indexOfLast { it.isNotBlank() } + 1
        val wanted = maxOf(2, highest, telephony.simSlots)
            .coerceIn(1, TelephonyIdentity.MAX_SIM_SLOTS)

        return Array(wanted) { slot ->
            FieldSpec(
                IdentityFields.TELEPHONY_PHONES[slot],
                "Phone number ${slot + 1} (SIM ${slot + 1})",
                telephony.phoneNumber(slot),
                mono = true,
                placeholder = if (slot == 0) "generated from the carrier" else "optional SIM ${slot + 1}",
                hint = if (slot == 0) {
                    "E.164 form, e.g. +8801XXXXXXXXX. Empty leaves SIM 1 without a number."
                } else {
                    "E.164 form. Set the earlier slots first; leave empty for fewer SIMs."
                },
            )
        }
    }

    private fun baseCategoriesFor(
        artifact: DoppeliskArtifact,
        existingProfileCount: Int,
        screenInches: String,
    ): List<FieldCategory> {
        val build = artifact.identity.build
        val ids = artifact.identity.ids
        val display = artifact.identity.display
        val hardware = artifact.identity.hardware
        val networkIds = artifact.identity.networkIds
        val telephony = artifact.identity.telephony
        val container = artifact.container
        val network = artifact.network
        val location = artifact.location

        val diagonal = display.screenInches.ifBlank { screenInches }

        return listOf(
            FieldCategory(
                id = "device",
                title = "Device & build",
                subtitle = "Model, fingerprint, and build metadata",
                fields = listOf(
                    FieldSpec("label", "Profile name", artifact.label),
                    FieldSpec(IdentityFields.BUILD_MANUFACTURER, "Manufacturer", build.manufacturer),
                    FieldSpec(IdentityFields.BUILD_BRAND, "Brand", build.brand),
                    FieldSpec(IdentityFields.BUILD_MODEL, "Model", build.model),
                    FieldSpec(IdentityFields.BUILD_DEVICE, "Device codename", build.device, mono = true),
                    FieldSpec(IdentityFields.BUILD_PRODUCT, "Product", build.product, mono = true),
                    FieldSpec(IdentityFields.BUILD_BOARD, "Board", build.board, mono = true),
                    FieldSpec(IdentityFields.BUILD_HARDWARE, "Hardware", build.hardware, mono = true),
                    FieldSpec(IdentityFields.BUILD_BOOTLOADER, "Bootloader", build.bootloader, mono = true),
                    FieldSpec(IdentityFields.BUILD_BASEBAND, "Baseband", build.baseband, mono = true),
                    FieldSpec(
                        IdentityFields.BUILD_FINGERPRINT,
                        "Build fingerprint",
                        build.fingerprint,
                        mono = true,
                        hint = "brand/product/device:release/build-id/incremental:type/tags",
                        multiline = true,
                    ),
                    FieldSpec(IdentityFields.BUILD_ID, "Build ID", build.id, mono = true),
                    FieldSpec(IdentityFields.BUILD_DISPLAY_ID, "Build display ID", build.displayId, mono = true),
                    FieldSpec(IdentityFields.BUILD_INCREMENTAL, "Incremental", build.incremental, mono = true),
                    FieldSpec(IdentityFields.BUILD_RELEASE, "Android release", build.release),
                    FieldSpec(IdentityFields.BUILD_SECURITY_PATCH, "Security patch", build.securityPatch),
                    FieldSpec(IdentityFields.BUILD_TYPE, "Build type", build.type, FieldKind.SELECT, BUILD_TYPES),
                    FieldSpec(IdentityFields.BUILD_TAGS, "Build tags", build.tags, FieldKind.SELECT, BUILD_TAGS),
                    FieldSpec(IdentityFields.BUILD_HOST, "Build host", build.host, mono = true),
                    FieldSpec(IdentityFields.BUILD_USER, "Build user", build.user, mono = true),
                    FieldSpec(
                        IdentityFields.BUILD_ABIS,
                        "Supported ABIs",
                        build.supportedAbis.joinToString(", "),
                        mono = true,
                        hint = "Comma-separated, e.g. arm64-v8a, armeabi-v7a",
                    ),
                    FieldSpec("chassis.id", "Chassis", artifact.chassis.id, FieldKind.READONLY, mono = true),
                ),
            ),
            FieldCategory(
                id = "ids",
                title = "Identifiers",
                subtitle = "Advertising, install, and device IDs",
                fields = listOf(
                    FieldSpec(IdentityFields.ID_SSAID, "Android ID (SSAID)", ids.ssaid, mono = true),
                    FieldSpec(IdentityFields.ID_GAID, "Advertising ID (GAID)", ids.gaid, mono = true),
                    FieldSpec(
                        IdentityFields.ID_APP_SET,
                        "Google App Set ID",
                        ids.appSetId,
                        mono = true,
                        hint = "UUIDv4. Empty uses a profile-scoped derived value.",
                    ),
                    FieldSpec(IdentityFields.ID_SERIAL, "Hardware serial", ids.serial, mono = true),
                    FieldSpec(IdentityFields.ID_BOOT, "Boot ID", ids.bootId, mono = true),
                    FieldSpec(
                        IdentityFields.ID_IMEI,
                        "IMEI",
                        ids.imei.orEmpty(),
                        mono = true,
                        placeholder = "empty",
                    ),
                ),
            ),
            FieldCategory(
                id = "display",
                title = "Display",
                subtitle = "Resolution, density, and refresh rate",
                fields = listOf(
                    FieldSpec(IdentityFields.DISPLAY_WIDTH, "Width", display.width.toString(), FieldKind.INTEGER),
                    FieldSpec(IdentityFields.DISPLAY_HEIGHT, "Height", display.height.toString(), FieldKind.INTEGER),
                    FieldSpec(IdentityFields.DISPLAY_DENSITY, "Density (dpi)", display.density.toString(), FieldKind.INTEGER),
                    FieldSpec(IdentityFields.DISPLAY_REFRESH, "Refresh rate (Hz)", display.refresh.toString(), FieldKind.INTEGER),
                    FieldSpec(
                        IdentityFields.DISPLAY_INCHES,
                        "Screen size (inches)",
                        diagonal,
                        hint = "Diagonal in inches, e.g. 6.7",
                    ),
                ),
            ),
            FieldCategory(
                id = "hardware",
                title = "Hardware",
                subtitle = "SoC, GPU, kernel, and camera surface",
                fields = listOf(
                    FieldSpec(IdentityFields.HARDWARE_SOC, "SoC", hardware.soc, mono = true),
                    FieldSpec(IdentityFields.HARDWARE_GPU, "GPU renderer", hardware.gpuRenderer),
                    FieldSpec(IdentityFields.HARDWARE_CPU, "CPU hardware", hardware.cpuHardware, mono = true),
                    FieldSpec(IdentityFields.HARDWARE_KERNEL, "Kernel", hardware.kernel, mono = true, multiline = true),
                    FieldSpec(
                        IdentityFields.HARDWARE_CAMERAS,
                        "Camera IDs",
                        hardware.cameraIds.joinToString(", "),
                        mono = true,
                        hint = "Comma-separated camera ids",
                    ),
                ),
            ),
            FieldCategory(
                id = "network_ids",
                title = "Network IDs",
                subtitle = "Wi-Fi and Bluetooth addresses",
                fields = listOf(
                    FieldSpec(IdentityFields.NETWORK_WIFI_MAC, "Wi-Fi MAC", networkIds.wifiMac, mono = true),
                    FieldSpec(IdentityFields.NETWORK_BT_MAC, "Bluetooth MAC", networkIds.bluetoothMac, mono = true),
                    FieldSpec(IdentityFields.NETWORK_OUI, "OUI vendor", networkIds.macOuiVendor),
                ),
            ),
            FieldCategory(
                id = "region_carrier",
                title = "Region and Carrier",
                subtitle = "Country, operator, phone numbers, timezone, and location",
                fields = listOf(
                    FieldSpec(
                        "region.country",
                        "Country / region",
                        RegionPresets.describe(artifact),
                        hint = "Fills carrier, MCC/MNC, timezone, a representative location, and a phone number.",
                    ),
                    FieldSpec(
                        IdentityFields.TELEPHONY_CARRIER,
                        "Carrier",
                        CarrierPresets.describe(artifact),
                        hint = "Choose one of the country's operators, or type a custom name.",
                    ),
                    FieldSpec(IdentityFields.TELEPHONY_MCC, "MCC", telephony.mcc, mono = true),
                    FieldSpec(IdentityFields.TELEPHONY_MNC, "MNC", telephony.mnc, mono = true),
                    FieldSpec(IdentityFields.TELEPHONY_COUNTRY, "Country ISO", telephony.countryIso, mono = true),
                    *phoneRows(telephony),
                    FieldSpec(
                        IdentityFields.TELEPHONY_SIM_SLOTS,
                        "SIM slots",
                        if (telephony.simSlots == 0) "auto" else telephony.simSlots.toString(),
                        FieldKind.SELECT,
                        listOf("auto") + (1..TelephonyIdentity.MAX_SIM_SLOTS).map(Int::toString),
                        hint = "Auto presents one slot per number; choose a count to present empty slots too.",
                    ),
                    FieldSpec(
                        "location.timezone",
                        "Timezone",
                        location.timezone.orEmpty(),
                        mono = true,
                        hint = "IANA id, e.g. America/New_York. Empty uses the country map.",
                    ),
                    FieldSpec(
                        "location.mode",
                        "Location mode",
                        location.mode,
                        FieldKind.SELECT,
                        LOCATION_OPTIONS,
                        hint = "Off keeps the real location. Fixed, jitter, and route use the coordinates below.",
                    ),
                    FieldSpec("location.lat", "Latitude", location.lat?.toString().orEmpty(), mono = true),
                    FieldSpec("location.lon", "Longitude", location.lon?.toString().orEmpty(), mono = true),
                    FieldSpec("location.accuracy", "Accuracy (m)", location.accuracy?.toString().orEmpty(), mono = true),
                ),
            ),
            FieldCategory(
                id = "container",
                title = "Container",
                subtitle = "Per-profile app data and keystore separation",
                fields = listOf(
                    FieldSpec(
                        "container.mode",
                        "Container mode",
                        container.mode,
                        FieldKind.SELECT,
                        CONTAINER_OPTIONS,
                        hint = if (existingProfileCount > 0) {
                            "Shared is unavailable when this app already has another profile."
                        } else {
                            "Shared is available while this is the app's only profile."
                        },
                    ),
                    FieldSpec(
                        "container.keystoreNamespace",
                        "Keystore namespace",
                        container.keystoreNamespace.orEmpty(),
                        mono = true,
                        placeholder = "derived automatically",
                    ),
                    FieldSpec(
                        "container.isolate",
                        "Isolated paths",
                        container.isolate.joinToString(", "),
                        mono = true,
                        placeholder = "empty",
                    ),
                ),
            ),
            FieldCategory(
                id = "network",
                title = "Network policy",
                subtitle = "Direct, SOCKS5, or blocked traffic",
                fields = listOf(
                    FieldSpec("network.mode", "Mode", network.mode, FieldKind.SELECT, NETWORK_OPTIONS),
                    FieldSpec("network.exit", "SOCKS5 exit", network.exit.orEmpty(), mono = true, placeholder = "host:port"),
                    FieldSpec("network.killSwitch", "Kill switch", network.killSwitch.toString(), FieldKind.BOOLEAN),
                    FieldSpec("network.dns", "DNS", network.dns.orEmpty(), mono = true, placeholder = "empty or block"),
                    FieldSpec("network.ipv6", "IPv6", network.ipv6, FieldKind.SELECT, IPV6_OPTIONS),
                    FieldSpec(
                        "network.vpn",
                        "VPN visibility",
                        network.vpn,
                        FieldKind.SELECT,
                        VPN_OPTIONS,
                        hint = "Default reports the real state; Off hides a real VPN; On always claims one; Reversed inverts it.",
                    ),
                ),
            ),
            FieldCategory(
                id = "profile",
                title = "Profile",
                subtitle = "Identity binding and policy",
                fields = listOf(
                    FieldSpec("target.package", "Target package", artifact.target.packageName, FieldKind.READONLY, mono = true),
                    FieldSpec("id", "Profile ID", artifact.id, FieldKind.READONLY, mono = true),
                    FieldSpec("policy.preset", "Policy preset", artifact.policy.preset, FieldKind.SELECT, POLICY_PRESETS),
                    FieldSpec(
                        "policy.rules",
                        "Field rules",
                        "${artifact.policy.rules.size} rules",
                        FieldKind.READONLY,
                    ),
                ),
            ),
        )
    }

    /**
     * The artifact with unpinned telephony fields filled from [realArtifact].
     *
     * Region description depends on telephony as the app will actually see it,
     * which is the profile's value only for pinned fields.
     */
    private fun effectiveRegionArtifact(
        artifact: DoppeliskArtifact,
        realArtifact: DoppeliskArtifact,
    ): DoppeliskArtifact {
        val draft = artifact.identity.telephony
        val real = realArtifact.identity.telephony
        val telephony = draft.copy(
            mcc = if (artifact.applies(IdentityFields.TELEPHONY_MCC)) draft.mcc else real.mcc,
            mnc = if (artifact.applies(IdentityFields.TELEPHONY_MNC)) draft.mnc else real.mnc,
            carrier = if (artifact.applies(IdentityFields.TELEPHONY_CARRIER)) draft.carrier else real.carrier,
            countryIso = if (artifact.applies(IdentityFields.TELEPHONY_COUNTRY)) {
                draft.countryIso
            } else {
                real.countryIso
            },
        )
        return artifact.copy(identity = artifact.identity.copy(telephony = telephony))
    }

    /** The raw write for one key, or null when the key is not writable. */
    private fun applyRaw(artifact: DoppeliskArtifact, key: String, value: String): DoppeliskArtifact? {
        val identity = artifact.identity
        val build = identity.build
        val display = identity.display
        val hardware = identity.hardware
        val networkIds = identity.networkIds
        val telephony = identity.telephony

        fun withBuild(updated: com.doppelisk.core.model.BuildIdentity) =
            artifact.copy(identity = identity.copy(build = updated))

        fun withIds(updated: com.doppelisk.core.model.Ids) =
            artifact.copy(identity = identity.copy(ids = updated))

        fun withIdentity(updated: Identity) = artifact.copy(identity = updated)

        fun withPhone(slot: Int, raw: String): DoppeliskArtifact {
            val number = PhoneNumbers.normalize(raw, telephony.countryIso) ?: raw.trim()
            val numbers = telephony.phoneNumbers.toMutableList()
            while (numbers.size <= slot) numbers.add("")
            numbers[slot] = number
            while (numbers.isNotEmpty() && numbers.last().isBlank()) numbers.removeAt(numbers.lastIndex)
            return withIdentity(identity.copy(telephony = telephony.copy(phoneNumbers = numbers)))
        }

        return when (key) {
            "label" -> artifact.copy(label = value)

            "region.country" -> {
                val preset = RegionPresets.forCountry(value).firstOrNull() ?: return artifact
                RegionPresets.apply(artifact, preset)
            }

            // ---------------------------------------------------- build
            IdentityFields.BUILD_MANUFACTURER -> withBuild(build.copy(manufacturer = value))
            IdentityFields.BUILD_BRAND -> withBuild(build.copy(brand = value))
            IdentityFields.BUILD_MODEL -> withBuild(build.copy(model = value))
            IdentityFields.BUILD_DEVICE -> withBuild(build.copy(device = value))
            IdentityFields.BUILD_PRODUCT -> withBuild(build.copy(product = value))
            IdentityFields.BUILD_BOARD -> withBuild(build.copy(board = value))
            IdentityFields.BUILD_HARDWARE -> withBuild(build.copy(hardware = value))
            IdentityFields.BUILD_BOOTLOADER -> withBuild(build.copy(bootloader = value))
            IdentityFields.BUILD_BASEBAND -> withBuild(build.copy(baseband = value))
            IdentityFields.BUILD_FINGERPRINT -> withBuild(build.copy(fingerprint = value))
            IdentityFields.BUILD_ID -> withBuild(build.copy(id = value))
            IdentityFields.BUILD_DISPLAY_ID -> withBuild(build.copy(displayId = value))
            IdentityFields.BUILD_INCREMENTAL -> withBuild(build.copy(incremental = value))
            IdentityFields.BUILD_RELEASE -> withBuild(build.copy(release = value))
            IdentityFields.BUILD_SECURITY_PATCH -> withBuild(build.copy(securityPatch = value))
            IdentityFields.BUILD_TYPE -> withBuild(build.copy(type = value))
            IdentityFields.BUILD_TAGS -> withBuild(build.copy(tags = value))
            IdentityFields.BUILD_HOST -> withBuild(build.copy(host = value))
            IdentityFields.BUILD_USER -> withBuild(build.copy(user = value))
            IdentityFields.BUILD_ABIS -> withBuild(build.copy(supportedAbis = splitList(value)))

            // ------------------------------------------------------ ids
            IdentityFields.ID_SSAID -> withIds(identity.ids.copy(ssaid = value))
            IdentityFields.ID_GAID -> withIds(identity.ids.copy(gaid = value))
            IdentityFields.ID_APP_SET -> withIds(identity.ids.copy(appSetId = value))
            IdentityFields.ID_SERIAL -> withIds(identity.ids.copy(serial = value))
            IdentityFields.ID_BOOT -> withIds(identity.ids.copy(bootId = value))
            IdentityFields.ID_IMEI -> withIds(identity.ids.copy(imei = value.ifBlank { null }))

            // -------------------------------------------------- display
            IdentityFields.DISPLAY_WIDTH -> withIdentity(identity.copy(display = display.copy(width = value.toIntOrNull() ?: display.width)))
            IdentityFields.DISPLAY_HEIGHT -> withIdentity(identity.copy(display = display.copy(height = value.toIntOrNull() ?: display.height)))
            IdentityFields.DISPLAY_DENSITY -> withIdentity(identity.copy(display = display.copy(density = value.toIntOrNull() ?: display.density)))
            IdentityFields.DISPLAY_REFRESH -> withIdentity(identity.copy(display = display.copy(refresh = value.toIntOrNull() ?: display.refresh)))
            IdentityFields.DISPLAY_INCHES -> withIdentity(identity.copy(display = display.copy(screenInches = value)))

            // ------------------------------------------------- hardware
            IdentityFields.HARDWARE_SOC -> withIdentity(identity.copy(hardware = hardware.copy(soc = value)))
            IdentityFields.HARDWARE_GPU -> withIdentity(identity.copy(hardware = hardware.copy(gpuRenderer = value)))
            IdentityFields.HARDWARE_CPU -> withIdentity(identity.copy(hardware = hardware.copy(cpuHardware = value)))
            IdentityFields.HARDWARE_KERNEL -> withIdentity(identity.copy(hardware = hardware.copy(kernel = value)))
            IdentityFields.HARDWARE_CAMERAS -> withIdentity(identity.copy(hardware = hardware.copy(cameraIds = splitList(value))))

            // ------------------------------------------------ network ids
            IdentityFields.NETWORK_WIFI_MAC -> withIdentity(identity.copy(networkIds = networkIds.copy(wifiMac = value)))
            IdentityFields.NETWORK_BT_MAC -> withIdentity(identity.copy(networkIds = networkIds.copy(bluetoothMac = value)))
            IdentityFields.NETWORK_OUI -> withIdentity(identity.copy(networkIds = networkIds.copy(macOuiVendor = value)))

            // ------------------------------------------------- telephony
            IdentityFields.TELEPHONY_CARRIER -> withIdentity(identity.copy(telephony = telephony.copy(carrier = value)))
            IdentityFields.TELEPHONY_MCC -> withIdentity(identity.copy(telephony = telephony.copy(mcc = value)))
            IdentityFields.TELEPHONY_MNC -> withIdentity(identity.copy(telephony = telephony.copy(mnc = value)))
            IdentityFields.TELEPHONY_COUNTRY -> withIdentity(identity.copy(telephony = telephony.copy(countryIso = value)))
            in IdentityFields.TELEPHONY_PHONES -> withPhone(
                IdentityFields.TELEPHONY_PHONES.indexOf(key),
                value,
            )
            IdentityFields.TELEPHONY_SIM_SLOTS -> withIdentity(
                identity.copy(
                    telephony = telephony.copy(
                        simSlots = when (value.lowercase(Locale.ROOT)) {
                            "auto", "" -> 0
                            else -> value.toIntOrNull()
                                ?.coerceIn(0, TelephonyIdentity.MAX_SIM_SLOTS)
                                ?: 0
                        },
                    ),
                ),
            )

            // ------------------------------------------------ location
            "location.timezone" -> artifact.copy(location = artifact.location.copy(timezone = value.ifBlank { null }))
            "location.mode" -> artifact.copy(location = artifact.location.copy(mode = value))
            "location.lat" -> artifact.copy(location = artifact.location.copy(lat = toDoubleLenient(value)))
            "location.lon" -> artifact.copy(location = artifact.location.copy(lon = toDoubleLenient(value)))
            "location.accuracy" -> artifact.copy(location = artifact.location.copy(accuracy = toDoubleLenient(value)))

            // ----------------------------------------------- container
            "container.mode" -> artifact.copy(container = artifact.container.copy(mode = value))
            "container.keystoreNamespace" -> artifact.copy(
                container = artifact.container.copy(keystoreNamespace = value.ifBlank { null }),
            )
            "container.isolate" -> artifact.copy(container = artifact.container.copy(isolate = splitList(value)))

            // ------------------------------------------------- network
            "network.mode" -> artifact.copy(network = artifact.network.copy(mode = value))
            "network.exit" -> artifact.copy(network = artifact.network.copy(exit = value.ifBlank { null }))
            "network.killSwitch" -> artifact.copy(
                network = artifact.network.copy(killSwitch = value.toBooleanStrictOrNull() ?: false),
            )
            "network.dns" -> artifact.copy(network = artifact.network.copy(dns = value.ifBlank { null }))
            "network.ipv6" -> artifact.copy(network = artifact.network.copy(ipv6 = value))
            "network.vpn" -> artifact.copy(network = artifact.network.copy(vpn = value))

            // -------------------------------------------------- policy
            "policy.preset" -> artifact.copy(policy = artifact.policy.copy(preset = value))

            else -> null
        }
    }

    private fun splitList(value: String): List<String> =
        value.split(',').map { it.trim() }.filter { it.isNotEmpty() }

    /** Accepts both `.` and `,` as the decimal separator, as the UI did. */
    private fun toDoubleLenient(value: String): Double? =
        value.trim().replace('\uFF0C', '.').replace(',', '.').toDoubleOrNull()
}
