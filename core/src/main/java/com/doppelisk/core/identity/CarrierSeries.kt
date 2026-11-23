package com.doppelisk.core.identity

import com.doppelisk.core.model.CarrierSeriesEntry
import com.doppelisk.core.model.CarrierSeriesFile
import com.doppelisk.core.model.JsonFormats
import com.doppelisk.core.model.Provenance
import java.util.Locale

/**
 * The number block each operator was allocated, for every country.
 *
 * A phone number is not just "valid for the country": in a closed numbering
 * plan its leading digits name the operator. `+8801712345678` is a Grameenphone
 * number and `+8801812345678` a Robi one, and an app that compares the number
 * with the carrier it reads from the SIM sees the contradiction immediately.
 *
 * The data comes from libphonenumber's carrier metadata — the same tables a
 * carrier-lookup service uses — extracted offline into `carriers/series.json`
 * because the Java artifact ships the numbering plans without them. The names
 * in that metadata do not always match the MCC/MNC catalog's brand names, so
 * lookup is fuzzy and a miss is not an error: the caller falls back to the
 * country-wide plan.
 *
 * Portability is the caveat worth knowing. The block says which operator the
 * range was *assigned* to; a ported subscriber keeps their number on another
 * network. That is exactly the reading a fresh SIM-backed profile needs.
 */
object CarrierSeries {

    private const val RESOURCE = "/carriers/series.json"

    private val json = JsonFormats.catalog

    private val loaded: CarrierSeriesFile by lazy { load() }

    private val byRegion: Map<String, List<CarrierSeriesEntry>> by lazy {
        loaded.regions.mapKeys { it.key.trim().uppercase(Locale.ROOT) }
    }

    /**
     * Series the metadata no longer attributes correctly.
     *
     * libphonenumber still files Airtel Bangladesh's `016` block under Robi,
     * because the two merged in 2016 — but `016` is sold and shown as Airtel,
     * and the catalog lists it as its own operator. The override keeps the
     * catalog's name and the block in agreement.
     */
    private val overrides: Map<String, Map<String, List<String>>> = mapOf(
        "BD" to mapOf("airtel" to listOf("16")),
    )

    /** The national prefixes of [carrier] in [countryIso], or empty when unknown. */
    fun prefixesFor(countryIso: String, carrier: String): List<String> {
        val iso = countryIso.trim().uppercase(Locale.ROOT)
        val wanted = normalize(carrier)
        if (wanted.isEmpty()) return emptyList()

        overrides[iso]?.forEach { (name, prefixes) ->
            if (wanted.contains(name)) return prefixes
        }

        val entries = byRegion[iso].orEmpty()
        if (entries.isEmpty()) return emptyList()

        val best = entries
            .mapNotNull { entry ->
                val score = score(wanted, normalize(entry.carrier))
                if (score == 0) null else entry to score
            }
            .maxByOrNull { it.second }

        return best?.first?.prefixes.orEmpty()
    }

    /** True when [nationalNumber] starts with one of [carrier]'s blocks. */
    fun matches(countryIso: String, carrier: String, nationalNumber: String): Boolean {
        val prefixes = prefixesFor(countryIso, carrier)
        if (prefixes.isEmpty()) return true
        return prefixes.any { nationalNumber.startsWith(it) }
    }

    /** Every operator with a known block in [countryIso]. */
    fun carriers(countryIso: String): List<String> =
        byRegion[countryIso.trim().uppercase(Locale.ROOT)].orEmpty().map { it.carrier }

    fun provenance(): Provenance = loaded.provenance

    /** 3 exact, 2 metadata name contains the wanted name, 1 the reverse, 0 no match. */
    private fun score(wanted: String, candidate: String): Int {
        if (candidate.isEmpty()) return 0
        if (wanted == candidate) return 3
        if (wanted.length >= 4 && candidate.contains(wanted)) return 2
        if (candidate.length >= 4 && wanted.contains(candidate)) return 1
        return 0
    }

    /** Lowercase letters and digits only, so "Robi Axiata Ltd." matches "robi". */
    private fun normalize(text: String): String =
        text.lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }

    private fun load(): CarrierSeriesFile {
        val stream = CarrierSeries::class.java.getResourceAsStream(RESOURCE)
            ?: throw IllegalStateException("Missing $RESOURCE resource")
        val text = stream.use { it.readBytes().toString(Charsets.UTF_8) }
        return json.decodeFromString(CarrierSeriesFile.serializer(), text)
    }
}
