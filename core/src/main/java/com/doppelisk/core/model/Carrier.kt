package com.doppelisk.core.model

import kotlinx.serialization.Serializable

/**
 * One real mobile network operator.
 *
 * [mcc] and [mnc] are the pair the platform reports as the SIM's operator
 * numeric. A carrier name without a matching numeric is a contradiction: apps
 * that read both will see `Robi` next to `470-02` and `Airtel` next to `470-02`
 * as different devices, so the two always travel together.
 */
@Serializable
data class Carrier(
    val name: String,
    val mcc: String,
    val mnc: String,
) {
    /** The `mcc`+`mnc` concatenation the telephony API calls "numeric". */
    val numeric: String get() = mcc + mnc
}

/** The shipped `carriers/catalog.json` document. */
@Serializable
data class CarrierCatalogFile(
    val version: Int = 1,
    val provenance: Provenance = Provenance(),
    /** ISO 3166-1 alpha-2 country code to its operators, sorted by name. */
    val countries: Map<String, List<Carrier>> = emptyMap(),
)

/**
 * One operator's allocated number block.
 *
 * [prefixes] are national significant digits: no trunk `0`, no country code.
 * A number whose national part starts with one of them belongs to [carrier]'s
 * original allocation. Number portability can move a live subscriber later, but
 * the block itself never changes hands.
 */
@Serializable
data class CarrierSeriesEntry(
    val carrier: String,
    val prefixes: List<String> = emptyList(),
)

/** The shipped `carriers/series.json` document. */
@Serializable
data class CarrierSeriesFile(
    val version: Int = 1,
    val provenance: Provenance = Provenance(),
    /** ISO 3166-1 alpha-2 country code to its operators' series. */
    val regions: Map<String, List<CarrierSeriesEntry>> = emptyMap(),
)
