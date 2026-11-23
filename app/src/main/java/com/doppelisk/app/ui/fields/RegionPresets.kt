package com.doppelisk.app.ui.fields

import com.doppelisk.core.identity.CarrierCatalog
import com.doppelisk.core.identity.LocationModes
import com.doppelisk.core.model.DoppeliskArtifact
import com.doppelisk.core.model.IdentityFields
import com.doppelisk.core.model.withOverride
import java.util.Locale

/**
 * A country's carrier surface.
 *
 * [latitude] and [longitude] are optional: a few territories have a country code
 * but no usable representative coordinate, and a preset without coordinates must
 * not silently move the profile's location.
 */
data class RegionPreset(
    val id: String,
    val label: String,
    val carrier: String,
    val countryIso: String,
    val mcc: String,
    val mnc: String,
    val timezone: String,
    val latitude: Double? = null,
    val longitude: Double? = null,
)

/**
 * Applies a [RegionPreset] to a profile.
 *
 * Region is the one part of a profile that spans three documents — telephony,
 * timezone and location — and a mismatch between them is exactly the kind of
 * contradiction the audit is meant to catch. So applying a preset writes all of
 * them at once and pins each field, rather than letting the user edit one and
 * forget the others.
 */
object RegionPresets {

    private val byCountry: Map<String, List<RegionPreset>> =
        REGION_CATALOG.groupBy { it.countryIso.trim().uppercase(Locale.ROOT) }

    fun all(): List<RegionPreset> = REGION_CATALOG

    fun forCountry(countryIso: String): List<RegionPreset> =
        byCountry[countryIso.trim().uppercase(Locale.ROOT)].orEmpty()

    /** A short description of the profile's current region, for the field row. */
    fun describe(artifact: DoppeliskArtifact): String {
        val telephony = artifact.identity.telephony
        val countryIso = telephony.countryIso.trim().uppercase(Locale.ROOT)
        if (countryIso.isEmpty()) return "Not set"

        val preset = forCountry(countryIso).firstOrNull()
        val name = preset?.label ?: countryIso

        // The carrier may be the country's default or any other operator the
        // user picked from the full list. Both are real, so both are shown.
        val carrier = CarrierCatalog.find(countryIso, telephony.carrier)?.name
            ?: CarrierCatalog.forCountry(countryIso)
                .firstOrNull { it.mcc == telephony.mcc.trim() && it.mnc == telephony.mnc.trim() }
                ?.name
            ?: telephony.carrier.trim()

        return when {
            carrier.isEmpty() -> name
            preset == null -> "$name · $carrier"
            timezoneMatches(artifact, preset) -> "$name · $carrier"
            else -> "$name (custom)"
        }
    }

    /** Writes the preset's telephony, timezone and coordinates into [artifact]. */
    fun apply(artifact: DoppeliskArtifact, preset: RegionPreset): DoppeliskArtifact {
        // Prefer the real operator from the MCC/MNC catalog over the curated
        // representative, so the name and the numeric cannot disagree.
        val chosen = CarrierCatalog.defaultFor(preset.countryIso, preset.carrier)

        val identity = artifact.identity.copy(
            telephony = artifact.identity.telephony.copy(
                mcc = chosen?.mcc ?: preset.mcc,
                mnc = chosen?.mnc ?: preset.mnc,
                carrier = chosen?.name ?: preset.carrier,
                countryIso = preset.countryIso,
            ),
        )

        val location = if (preset.latitude == null || preset.longitude == null) {
            artifact.location.copy(
                mode = LocationModes.OFF,
                lat = null,
                lon = null,
                timezone = preset.timezone,
            )
        } else {
            val accuracy = artifact.location.accuracy?.takeIf { it > 0.0 } ?: 25.0
            artifact.location.copy(
                mode = LocationModes.FIXED,
                lat = preset.latitude,
                lon = preset.longitude,
                accuracy = accuracy,
                timezone = preset.timezone,
            )
        }

        var updated = artifact.copy(identity = identity, location = location)
        for (key in listOf(
            IdentityFields.TELEPHONY_MCC,
            IdentityFields.TELEPHONY_MNC,
            IdentityFields.TELEPHONY_CARRIER,
            IdentityFields.TELEPHONY_COUNTRY,
        )) {
            updated = updated.withOverride(key)
        }

        // A phone number is only coherent inside its country's numbering plan,
        // so a country change regenerates the slots that no longer fit.
        return PhoneNumbers.ensureForCountry(updated, preset.countryIso)
    }

    /** True when the profile's telephony and timezone match [preset] exactly. */
    fun matches(artifact: DoppeliskArtifact, preset: RegionPreset): Boolean {
        val chosen = CarrierCatalog.defaultFor(preset.countryIso, preset.carrier)
        val telephony = artifact.identity.telephony
        val sameTelephony =
            telephony.mcc.trim() == (chosen?.mcc ?: preset.mcc) &&
                telephony.mnc.trim() == (chosen?.mnc ?: preset.mnc) &&
                telephony.carrier.trim().equals(chosen?.name ?: preset.carrier, ignoreCase = true) &&
                telephony.countryIso.trim().equals(preset.countryIso, ignoreCase = true)

        return sameTelephony && timezoneMatches(artifact, preset)
    }

    private fun timezoneMatches(artifact: DoppeliskArtifact, preset: RegionPreset): Boolean =
        (artifact.location.timezone ?: "").trim() == preset.timezone
}
