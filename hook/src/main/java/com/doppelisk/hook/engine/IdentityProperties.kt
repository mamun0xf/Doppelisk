package com.doppelisk.hook.engine

import com.doppelisk.core.identity.GeoTime
import com.doppelisk.core.model.DoppeliskArtifact
import com.doppelisk.core.model.IdentityFields
import java.security.MessageDigest
import java.util.Locale

/**
 * Builds the system-property table a chassis implies.
 *
 * Property spoofing is the backbone of the disguise: a huge amount of
 * fingerprinting reads `ro.product.model` or `ro.build.fingerprint` directly
 * instead of going through `android.os.Build`, and the two must agree.
 *
 * Every entry is gated by [applies]. A property is only written when at least one
 * of the identity fields it derives from is engine-controlled, so a user who pins
 * only the model does not silently get a rewritten timezone as well. Entries whose
 * value is blank are skipped rather than written empty, because an empty
 * `ro.build.host` is itself a signal.
 */
object IdentityProperties {

    private val productPartitions = listOf(
        "system",
        "vendor",
        "odm",
        "product",
        "system_ext",
        "vendor_dlkm",
        "odm_dlkm",
    )

    private val buildPartitions = productPartitions + "bootimage"

    /** Every property this profile should report. */
    fun forArtifact(
        artifact: DoppeliskArtifact,
        applies: (String) -> Boolean = { true },
    ): Map<String, String> {
        val build = artifact.identity.build
        val ids = artifact.identity.ids
        val hardware = artifact.identity.hardware
        val telephony = artifact.identity.telephony
        val display = artifact.identity.display

        val baseband = RootHiding.basebandFor(build)
        val soc = RootHiding.socIdentity(hardware)
        val buildDate = RootHiding.buildDate(build)
        val ramMb = RootHiding.ramMbFor(build)

        val country = telephony.countryIso.trim().lowercase(Locale.US)
        val numeric = telephony.mcc.trim() + telephony.mnc.trim()
        val timezone = GeoTime.zoneFor(
            telephony.countryIso,
            artifact.location.timezone,
            artifact.location.lat,
            artifact.location.lon,
        )
        val locationActive = artifact.location.mode != "off"

        val props = LinkedHashMap<String, String>()

        // --- product -------------------------------------------------------
        put(props, applies, "ro.product.model", build.model, IdentityFields.BUILD_MODEL)
        put(props, applies, "ro.product.brand", build.brand, IdentityFields.BUILD_BRAND)
        put(props, applies, "ro.product.manufacturer", build.manufacturer, IdentityFields.BUILD_MANUFACTURER)
        put(props, applies, "ro.product.device", build.device, IdentityFields.BUILD_DEVICE)
        put(props, applies, "ro.product.name", build.product, IdentityFields.BUILD_PRODUCT)
        put(props, applies, "ro.product.board", build.board, IdentityFields.BUILD_BOARD)
        put(props, applies, "ro.product.hardware", build.hardware, IdentityFields.BUILD_HARDWARE)
        put(props, applies, "ro.board.platform", build.board, IdentityFields.BUILD_BOARD)
        put(props, applies, "ro.hardware", build.hardware, IdentityFields.BUILD_HARDWARE)
        put(props, applies, "ro.boot.hardware", build.hardware, IdentityFields.BUILD_HARDWARE)
        put(props, applies, "ro.boot.hardware.sku", build.model, IdentityFields.BUILD_MODEL)
        put(props, applies, "ro.boot.product.hardware.sku", build.model, IdentityFields.BUILD_MODEL)

        // --- SoC -----------------------------------------------------------
        put(props, applies, "ro.soc.manufacturer", soc.manufacturer, IdentityFields.HARDWARE_SOC)
        put(props, applies, "ro.soc.model", soc.model, IdentityFields.HARDWARE_SOC)
        put(props, applies, "ro.vendor.soc.model.name", soc.model, IdentityFields.HARDWARE_SOC)
        put(
            props,
            applies,
            "ro.vendor.soc.model.part_name",
            hardware.cpuHardware.ifBlank { build.board },
            IdentityFields.HARDWARE_CPU,
            IdentityFields.HARDWARE_SOC,
        )
        put(props, applies, "ro.unicpu.model", soc.model, IdentityFields.HARDWARE_SOC)
        put(props, applies, "ro.infinity.soc", soc.model, IdentityFields.HARDWARE_SOC)

        // --- build ---------------------------------------------------------
        put(props, applies, "ro.build.characteristics", "default", *IdentityFields.BUILD.toTypedArray())
        put(props, applies, "ro.build.fingerprint", build.fingerprint, IdentityFields.BUILD_FINGERPRINT)
        put(props, applies, "ro.build.id", build.id, IdentityFields.BUILD_ID)
        put(props, applies, "ro.build.display.id", build.displayId, IdentityFields.BUILD_DISPLAY_ID)
        put(props, applies, "ro.build.version.incremental", build.incremental, IdentityFields.BUILD_INCREMENTAL)
        put(props, applies, "ro.build.version.release", build.release, IdentityFields.BUILD_RELEASE)
        put(props, applies, "ro.build.version.release_or_codename", build.release, IdentityFields.BUILD_RELEASE)
        put(props, applies, "ro.build.version.security_patch", build.securityPatch, IdentityFields.BUILD_SECURITY_PATCH)
        put(props, applies, "ro.build.version.codename", "REL", IdentityFields.BUILD_RELEASE)
        put(props, applies, "ro.build.date", buildDate.text, IdentityFields.BUILD_SECURITY_PATCH)
        put(props, applies, "ro.build.date.utc", buildDate.epochSeconds.toString(), IdentityFields.BUILD_SECURITY_PATCH)
        put(props, applies, "ro.build.type", build.type, IdentityFields.BUILD_TYPE)
        put(props, applies, "ro.build.tags", build.tags, IdentityFields.BUILD_TAGS)
        put(props, applies, "ro.build.host", build.host, IdentityFields.BUILD_HOST)
        put(props, applies, "ro.build.user", build.user, IdentityFields.BUILD_USER)
        put(props, applies, "ro.build.product", build.device, IdentityFields.BUILD_DEVICE)
        put(
            props,
            applies,
            "ro.build.description",
            "${build.product}-user ${build.release} ${build.id} ${build.incremental} ${build.tags}",
            IdentityFields.BUILD_MODEL,
            IdentityFields.BUILD_PRODUCT,
            IdentityFields.BUILD_RELEASE,
            IdentityFields.BUILD_ID,
            IdentityFields.BUILD_INCREMENTAL,
            IdentityFields.BUILD_TAGS,
        )
        put(
            props,
            applies,
            "ro.build.flavor",
            "${build.product}-user",
            IdentityFields.BUILD_PRODUCT,
            IdentityFields.BUILD_MODEL,
        )

        // --- boot / serial -------------------------------------------------
        put(props, applies, "ro.bootloader", build.bootloader, IdentityFields.BUILD_BOOTLOADER)
        put(props, applies, "ro.boot.bootloader", build.bootloader, IdentityFields.BUILD_BOOTLOADER)
        put(props, applies, "ro.boot.serialno", ids.serial, IdentityFields.ID_SERIAL)
        put(props, applies, "ro.serialno", ids.serial, IdentityFields.ID_SERIAL)
        put(props, applies, "ro.boot.ap_serial", apSerial(ids.serial), IdentityFields.ID_SERIAL)
        put(
            props,
            applies,
            "ro.boot.dram_info",
            "FF,00,00,${ramMb / 1024}G",
            IdentityFields.HARDWARE_SOC,
            IdentityFields.BUILD_MODEL,
        )

        // --- display / locale ----------------------------------------------
        if (display.density > 0) {
            put(props, applies, "ro.sf.lcd_density", display.density.toString(), IdentityFields.DISPLAY_DENSITY)
        }
        put(props, applies, "ro.product.locale.language", "en", IdentityFields.TELEPHONY_COUNTRY)
        put(props, applies, "ro.product.locale.region", country.uppercase(Locale.US), IdentityFields.TELEPHONY_COUNTRY)
        put(
            props,
            applies,
            "ro.product.locale",
            if (country.isBlank()) "en" else "en-${country.uppercase(Locale.US)}",
            IdentityFields.TELEPHONY_COUNTRY,
        )

        if (locationActive ||
            applies(IdentityFields.TELEPHONY_COUNTRY) ||
            applies(IdentityFields.TELEPHONY_MCC) ||
            applies(IdentityFields.TELEPHONY_MNC)
        ) {
            // The timezone is not gated on a single field: it follows the whole
            // location/carrier picture, so any of those being faked moves it too.
            timezone?.let { props["persist.sys.timezone"] = it }
        }

        // --- radio ---------------------------------------------------------
        put(props, applies, "gsm.version.baseband", baseband, IdentityFields.BUILD_BASEBAND)
        put(props, applies, "gsm.version.baseband2", baseband, IdentityFields.BUILD_BASEBAND)
        put(
            props,
            applies,
            "gsm.version.ril-impl",
            rilImplementation(build.brand, hardware.soc),
            IdentityFields.BUILD_BRAND,
            IdentityFields.HARDWARE_SOC,
        )

        val manufactureDate = RootHiding.manufactureDate(build)
        for (suffix in listOf("", "2")) {
            put(props, applies, "ril.product_code$suffix", build.model, IdentityFields.BUILD_MODEL)
            put(
                props,
                applies,
                "ril.rfcal_date$suffix",
                manufactureDate,
                IdentityFields.BUILD_SECURITY_PATCH,
                IdentityFields.BUILD_MODEL,
            )
            put(props, applies, "ril.model_id$suffix", build.model, IdentityFields.BUILD_MODEL)
            put(props, applies, "ril.sw_ver$suffix", baseband, IdentityFields.BUILD_BASEBAND)
            put(props, applies, "ril.modem.board$suffix", build.board, IdentityFields.BUILD_BOARD)
            put(
                props,
                applies,
                "ril.hw_ver$suffix",
                "Rev1.0",
                IdentityFields.BUILD_MODEL,
                IdentityFields.BUILD_BASEBAND,
            )
        }
        put(
            props,
            applies,
            "ril.attach.apn0",
            "internet",
            IdentityFields.BUILD_MODEL,
            IdentityFields.BUILD_BASEBAND,
        )

        val carrier = telephony.carrier.takeIf { it.isNotBlank() }
        put(props, applies, "ril.simoperator", carrier?.let { "$it," }, IdentityFields.TELEPHONY_CARRIER)
        put(props, applies, "gsm.operator.alpha", carrier?.let { "$it," }, IdentityFields.TELEPHONY_CARRIER)
        put(props, applies, "gsm.sim.operator.alpha", telephony.carrier, IdentityFields.TELEPHONY_CARRIER)

        val countryOrNull = country.takeIf { it.isNotBlank() }
        put(props, applies, "gsm.operator.iso-country", countryOrNull?.let { "$it," }, IdentityFields.TELEPHONY_COUNTRY)
        put(props, applies, "gsm.sim.operator.iso-country", country, IdentityFields.TELEPHONY_COUNTRY)

        // A numeric MCC/MNC is only valid when both halves are present.
        val numericOrNull = numeric.takeIf { it.length >= 5 }
        put(
            props,
            applies,
            "gsm.operator.numeric",
            numericOrNull?.let { "$it," },
            IdentityFields.TELEPHONY_MCC,
            IdentityFields.TELEPHONY_MNC,
        )
        put(
            props,
            applies,
            "gsm.sim.operator.numeric",
            numericOrNull,
            IdentityFields.TELEPHONY_MCC,
            IdentityFields.TELEPHONY_MNC,
        )
        put(
            props,
            applies,
            "gsm.operator.isroaming",
            "false,false",
            IdentityFields.TELEPHONY_MCC,
            IdentityFields.TELEPHONY_MNC,
            IdentityFields.TELEPHONY_COUNTRY,
        )
        put(props, applies, "debug.tracing.mcc", telephony.mcc, IdentityFields.TELEPHONY_MCC)
        put(props, applies, "debug.tracing.mnc", telephony.mnc, IdentityFields.TELEPHONY_MNC)
        put(props, applies, "bluetooth.device.default_name", build.model, IdentityFields.BUILD_MODEL)

        // --- per-partition build props -------------------------------------
        for (partition in buildPartitions) {
            put(props, applies, "ro.$partition.build.id", build.id, IdentityFields.BUILD_ID)
            put(props, applies, "ro.$partition.build.display.id", build.displayId, IdentityFields.BUILD_DISPLAY_ID)
            put(
                props,
                applies,
                "ro.$partition.build.version.incremental",
                build.incremental,
                IdentityFields.BUILD_INCREMENTAL,
            )
            put(props, applies, "ro.$partition.build.version.release", build.release, IdentityFields.BUILD_RELEASE)
            put(
                props,
                applies,
                "ro.$partition.build.version.release_or_codename",
                build.release,
                IdentityFields.BUILD_RELEASE,
            )
            put(
                props,
                applies,
                "ro.$partition.build.version.security_patch",
                build.securityPatch,
                IdentityFields.BUILD_SECURITY_PATCH,
            )
            put(
                props,
                applies,
                "ro.$partition.build.security_patch",
                build.securityPatch,
                IdentityFields.BUILD_SECURITY_PATCH,
            )
            put(props, applies, "ro.$partition.build.date", buildDate.text, IdentityFields.BUILD_SECURITY_PATCH)
            put(
                props,
                applies,
                "ro.$partition.build.date.utc",
                buildDate.epochSeconds.toString(),
                IdentityFields.BUILD_SECURITY_PATCH,
            )
            put(props, applies, "ro.$partition.build.type", build.type, IdentityFields.BUILD_TYPE)
            put(props, applies, "ro.$partition.build.tags", build.tags, IdentityFields.BUILD_TAGS)
            put(props, applies, "ro.$partition.build.host", build.host, IdentityFields.BUILD_HOST)
            put(props, applies, "ro.$partition.build.user", build.user, IdentityFields.BUILD_USER)
            put(props, applies, "ro.$partition.build.product", build.device, IdentityFields.BUILD_DEVICE)
            put(
                props,
                applies,
                "ro.$partition.build.fingerprint",
                build.fingerprint,
                IdentityFields.BUILD_FINGERPRINT,
            )
        }

        for (partition in productPartitions) {
            put(props, applies, "ro.product.$partition.model", build.model, IdentityFields.BUILD_MODEL)
            put(props, applies, "ro.product.$partition.brand", build.brand, IdentityFields.BUILD_BRAND)
            put(
                props,
                applies,
                "ro.product.$partition.manufacturer",
                build.manufacturer,
                IdentityFields.BUILD_MANUFACTURER,
            )
            put(props, applies, "ro.product.$partition.device", build.device, IdentityFields.BUILD_DEVICE)
            put(props, applies, "ro.product.$partition.name", build.product, IdentityFields.BUILD_PRODUCT)
            put(props, applies, "ro.product.$partition.board", build.board, IdentityFields.BUILD_BOARD)
            put(props, applies, "ro.product.$partition.hardware", build.hardware, IdentityFields.BUILD_HARDWARE)
        }

        return props
    }

    /**
     * Adds one property when any of [keys] is engine-controlled.
     *
     * An empty [keys] list means "unconditional" — used for properties that are
     * part of the disguise as a whole rather than of one field.
     */
    private fun put(
        props: MutableMap<String, String>,
        applies: (String) -> Boolean,
        key: String,
        value: String?,
        vararg keys: String,
    ) {
        if (keys.isNotEmpty() && keys.none(applies)) return
        if (value.isNullOrBlank()) return
        props[key] = value
    }

    /**
     * `ro.boot.ap_serial`, which is a truncated SHA-256 of the serial on real
     * devices rather than the serial itself.
     */
    private fun apSerial(serial: String): String {
        val digest = runCatching {
            MessageDigest.getInstance("SHA-256").digest(serial.toByteArray(Charsets.UTF_8))
        }.getOrNull() ?: return "0x0000000000000000"

        return "0x" + digest.take(8).joinToString("") { "%02X".format(it.toInt() and 0xFF) }
    }

    private fun rilImplementation(brand: String, soc: String): String = when {
        brand.equals("samsung", ignoreCase = true) -> "Samsung RIL v5.0"
        soc.startsWith("mediatek/", ignoreCase = true) -> "MTK RIL"
        brand.equals("google", ignoreCase = true) -> "Google RIL"
        else -> "Qualcomm RIL"
    }
}
