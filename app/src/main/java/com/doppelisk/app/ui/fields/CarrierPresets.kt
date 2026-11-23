package com.doppelisk.app.ui.fields

import com.doppelisk.core.identity.CarrierCatalog
import com.doppelisk.core.model.Carrier
import com.doppelisk.core.model.DoppeliskArtifact
import com.doppelisk.core.model.IdentityFields
import com.doppelisk.core.model.withOverride
import java.util.Locale

/**
 * Choosing an operator, as opposed to choosing a country.
 *
 * A country preset picks a representative carrier, but a country usually has
 * several. This is the second half: the full list for the country the profile
 * already claims, with the MCC/MNC that belong to each name. Picking one writes
 * all three together, because a carrier name with another operator's numeric is
 * a contradiction the audit is designed to catch.
 */
object CarrierPresets {

    /** Every known operator of [countryIso]. */
    fun forCountry(countryIso: String): List<Carrier> = CarrierCatalog.forCountry(countryIso)

    /** The profile's carrier, as a field value. */
    fun describe(artifact: DoppeliskArtifact): String {
        val telephony = artifact.identity.telephony
        if (telephony.carrier.isBlank() && telephony.mcc.isBlank() && telephony.mnc.isBlank()) {
            return "Not set"
        }
        val name = telephony.carrier.ifBlank { "Unknown carrier" }
        return when {
            telephony.mcc.isBlank() || telephony.mnc.isBlank() -> name
            else -> "$name  ·  ${telephony.mcc}-${telephony.mnc}"
        }
    }

    /**
     * Writes [carrier] into [artifact], keeping the country it belongs to.
     *
     * [countryIso] is the profile's current country. When it is blank the
     * carrier's country is looked up in the catalog so a globally picked
     * carrier still fills the country in.
     */
    fun apply(
        artifact: DoppeliskArtifact,
        carrier: Carrier,
        countryIso: String = "",
    ): DoppeliskArtifact {
        val iso = countryIso.trim().uppercase(Locale.ROOT).ifBlank {
            CarrierCatalog.countries()
                .entries
                .firstOrNull { (_, list) -> list.any { it == carrier } }
                ?.key
                .orEmpty()
        }

        val telephony = artifact.identity.telephony.copy(
            carrier = carrier.name,
            mcc = carrier.mcc,
            mnc = carrier.mnc,
            countryIso = iso.ifBlank { artifact.identity.telephony.countryIso },
        )

        var updated = artifact.copy(identity = artifact.identity.copy(telephony = telephony))
        for (key in listOf(
            IdentityFields.TELEPHONY_CARRIER,
            IdentityFields.TELEPHONY_MCC,
            IdentityFields.TELEPHONY_MNC,
        )) {
            updated = updated.withOverride(key)
        }
        if (iso.isNotBlank()) updated = updated.withOverride(IdentityFields.TELEPHONY_COUNTRY)

        return PhoneNumbers.ensureForCountry(updated, telephony.countryIso)
    }
}
