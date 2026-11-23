package com.doppelisk.core.identity

import com.doppelisk.core.model.Carrier
import com.doppelisk.core.model.CarrierCatalogFile
import com.doppelisk.core.model.JsonFormats
import com.doppelisk.core.model.Provenance
import java.util.Locale

/**
 * The shipped MCC/MNC catalog.
 *
 * Region presets give a country one representative carrier, but a country
 * usually has several. This catalog is the full list, keyed by ISO code, so the
 * manager can offer the real operators of the country the profile claims and
 * keep the carrier name, MCC and MNC coherent.
 *
 * The data is derived from the MIT-licensed `mcc-mnc-list` dataset (itself
 * scraped from Wikipedia's mobile country code tables) and contains national,
 * operational networks only. It lives in `resources` rather than `assets`
 * because the hook and the daemon run as `app_process` with the APK on the
 * classpath and have no `Context` to reach assets through.
 */
object CarrierCatalog {

    private const val RESOURCE = "/carriers/catalog.json"

    private val json = JsonFormats.catalog

    private val loaded: CarrierCatalogFile by lazy { load() }

    private val byCountry: Map<String, List<Carrier>> by lazy {
        loaded.countries.mapKeys { it.key.trim().uppercase(Locale.ROOT) }
    }

    /** Every country, in ISO order. */
    fun countries(): Map<String, List<Carrier>> = byCountry

    /** Every carrier of [countryIso], or an empty list when the catalog has none. */
    fun forCountry(countryIso: String): List<Carrier> =
        byCountry[countryIso.trim().uppercase(Locale.ROOT)].orEmpty()

    /**
     * The carrier a country preset should start with.
     *
     * [preferred] is the preset's curated representative; it wins when it is a
     * real carrier of that country. Otherwise the first entry is used, which is
     * still a real operator rather than an invented one.
     */
    fun defaultFor(countryIso: String, preferred: String? = null): Carrier? {
        val carriers = forCountry(countryIso)
        if (carriers.isEmpty()) return null
        val match = preferred
            ?.takeIf { it.isNotBlank() }
            ?.let { name -> carriers.firstOrNull { it.name.equals(name, ignoreCase = true) } }
        return match ?: carriers.first()
    }

    /** Finds a carrier by name within [countryIso]. */
    fun find(countryIso: String, name: String): Carrier? =
        forCountry(countryIso).firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }

    /** Finds a carrier by its MCC/MNC anywhere in the catalog. */
    fun findByNumeric(mcc: String, mnc: String): Carrier? {
        val numeric = mcc.trim() + mnc.trim()
        return byCountry.values.asSequence()
            .flatten()
            .firstOrNull { it.numeric == numeric }
    }

    /** Free-text search across every country, for the global carrier picker. */
    fun search(query: String): List<Pair<String, Carrier>> {
        val needle = query.trim()
        if (needle.isEmpty()) return emptyList()
        return byCountry.entries
            .asSequence()
            .sortedBy { it.key }
            .flatMap { (iso, carriers) ->
                carriers.asSequence()
                    .filter {
                        it.name.contains(needle, ignoreCase = true) ||
                            it.numeric.contains(needle, ignoreCase = true)
                    }
                    .map { iso to it }
            }
            .toList()
    }

    fun provenance(): Provenance = loaded.provenance

    private fun load(): CarrierCatalogFile {
        val stream = CarrierCatalog::class.java.getResourceAsStream(RESOURCE)
            ?: throw IllegalStateException("Missing $RESOURCE resource")
        val text = stream.use { it.readBytes().toString(Charsets.UTF_8) }
        return json.decodeFromString(CarrierCatalogFile.serializer(), text)
    }
}
