package com.doppelisk.core.identity

import java.util.Locale

/**
 * Maps a country (and, where the country spans several zones, a longitude) to
 * an IANA time zone.
 *
 * The table is deliberately small: it only needs to cover the countries the
 * chassis catalog ships, and a wrong-but-plausible zone is worse than no zone at
 * all, so unknown countries return null.
 */
object GeoTime {

    /** Primary zone per ISO-3166 alpha-2 country code. */
    private val COUNTRY_ZONES: Map<String, String> = linkedMapOf(
        "US" to "America/New_York",
        "CA" to "America/Toronto",
        "MX" to "America/Mexico_City",
        "BR" to "America/Sao_Paulo",
        "AR" to "America/Argentina/Buenos_Aires",
        "GB" to "Europe/London",
        "IE" to "Europe/Dublin",
        "DE" to "Europe/Berlin",
        "FR" to "Europe/Paris",
        "ES" to "Europe/Madrid",
        "IT" to "Europe/Rome",
        "NL" to "Europe/Amsterdam",
        "SE" to "Europe/Stockholm",
        "NO" to "Europe/Oslo",
        "DK" to "Europe/Copenhagen",
        "FI" to "Europe/Helsinki",
        "PL" to "Europe/Warsaw",
        "RU" to "Europe/Moscow",
        "TR" to "Europe/Istanbul",
        "SA" to "Asia/Riyadh",
        "AE" to "Asia/Dubai",
        "IN" to "Asia/Kolkata",
        "PK" to "Asia/Karachi",
        "BD" to "Asia/Dhaka",
        "CN" to "Asia/Shanghai",
        "HK" to "Asia/Hong_Kong",
        "TW" to "Asia/Taipei",
        "JP" to "Asia/Tokyo",
        "KR" to "Asia/Seoul",
        "SG" to "Asia/Singapore",
        "MY" to "Asia/Kuala_Lumpur",
        "TH" to "Asia/Bangkok",
        "VN" to "Asia/Ho_Chi_Minh",
        "ID" to "Asia/Jakarta",
        "PH" to "Asia/Manila",
        "AU" to "Australia/Sydney",
        "NZ" to "Pacific/Auckland",
        "ZA" to "Africa/Johannesburg",
        "NG" to "Africa/Lagos",
        "KE" to "Africa/Nairobi",
        "EG" to "Africa/Cairo",
    )

    /**
     * Picks a zone for [countryIso].
     *
     * An [explicit] zone always wins and is returned verbatim (validated later
     * by [GeoValidator]); otherwise the country's primary zone is refined by
     * [lon] for countries that span several zones. Returns null when the country
     * is unknown, in which case there is nothing to check.
     */
    fun zoneFor(
        countryIso: String,
        explicit: String? = null,
        lat: Double? = null,
        lon: Double? = null,
    ): String? {
        if (explicit != null && explicit.trim().isNotEmpty()) return explicit.trim()

        val country = countryIso.trim().uppercase(Locale.ROOT)
        val base = COUNTRY_ZONES[country] ?: return null
        if (lat == null || lon == null) return base

        return when (country) {
            "US" -> when {
                lat < 25.0 && lon < -150.0 -> "Pacific/Honolulu"
                lat > 50.0 && lon < -140.0 -> "America/Anchorage"
                lon < -115.0 -> "America/Los_Angeles"
                lon < -100.0 -> "America/Denver"
                lon < -87.0 -> "America/Chicago"
                else -> "America/New_York"
            }
            "RU" -> when {
                lon < 40.0 -> "Europe/Moscow"
                lon < 80.0 -> "Asia/Yekaterinburg"
                lon < 110.0 -> "Asia/Krasnoyarsk"
                lon < 140.0 -> "Asia/Yakutsk"
                else -> "Asia/Vladivostok"
            }
            "CA" -> when {
                lon < -120.0 -> "America/Vancouver"
                lon < -95.0 -> "America/Winnipeg"
                lon < -75.0 -> "America/Toronto"
                else -> "America/Halifax"
            }
            "BR" -> if (lon < -50.0) "America/Manaus" else "America/Sao_Paulo"
            "AU" -> when {
                lon < 129.0 -> "Australia/Perth"
                lon < 141.0 -> "Australia/Adelaide"
                else -> "Australia/Sydney"
            }
            else -> base
        }
    }
}
