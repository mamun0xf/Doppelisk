package com.doppelisk.app.ui.fields

import com.google.i18n.phonenumbers.PhoneNumberUtil
import com.google.i18n.phonenumbers.Phonenumber.PhoneNumber
import com.doppelisk.core.identity.CarrierSeries
import com.doppelisk.core.model.DoppeliskArtifact
import com.doppelisk.core.model.IdentityFields
import com.doppelisk.core.model.withOverride
import java.util.Locale

/**
 * Country-aware phone numbers.
 *
 * A number that does not match the country's numbering plan is its own signal:
 * `+880` followed by eight digits is not a Bangladeshi mobile, and an app that
 * checks the format will treat the profile as fake. So numbers are generated
 * from libphonenumber's per-region mobile plan, validated against it, and stored
 * in E.164 form.
 *
 * The operator matters as much as the country. Where the plan assigns a series
 * per operator (see [CarrierSeries]) the generated number uses the selected
 * carrier's prefix, so the number and the carrier name agree. The rest of the
 * number varies per profile and per slot, which keeps two profiles on one phone
 * looking like different subscribers.
 */
object PhoneNumbers {

    private val util: PhoneNumberUtil by lazy { PhoneNumberUtil.getInstance() }

    /**
     * A plausible mobile number for [countryIso], stable for [seed] and [slot].
     *
     * [carrier] narrows the number to that operator's series when the plan has
     * one. [nonce] is what makes the "randomise" action produce a different
     * number every time; leave it zero for a value that is reproducible from the
     * profile id.
     *
     * Returns an empty string when the region has no mobile numbering plan in
     * the metadata (a few territories), so callers can leave the field blank
     * instead of inventing a number.
     */
    fun generate(
        countryIso: String,
        carrier: String = "",
        seed: String = "",
        slot: Int = 0,
        nonce: Long = 0L,
    ): String {
        val region = region(countryIso) ?: return ""
        val example = runCatching {
            util.getExampleNumberForType(region, PhoneNumberUtil.PhoneNumberType.MOBILE)
        }.getOrNull() ?: return ""

        val exampleNational = example.nationalNumber.toString()
        val national = withCarrierSeries(region, carrier, exampleNational, seed, slot, nonce)
        val head = national.dropLast(SUFFIX_DIGITS)
        if (head.isEmpty()) return util.format(example, PhoneNumberUtil.PhoneNumberFormat.E164)

        repeat(ATTEMPTS) { attempt ->
            val suffix = Math.floorMod(
                seedHash(seed) + slot * 7919L + attempt * 104729L + nonce * 2654435761L,
                SUFFIX_MODULUS,
            )
            val candidate = PhoneNumber()
                .setCountryCode(example.countryCode)
                .setNationalNumber((head + suffix.toString().padStart(SUFFIX_DIGITS, '0')).toLong())
            if (runCatching { util.isValidNumber(candidate) }.getOrDefault(false)) {
                return util.format(candidate, PhoneNumberUtil.PhoneNumberFormat.E164)
            }
        }

        // A prefix that rejects every suffix is still better than no number, but
        // the country example is guaranteed valid, so fall back to it.
        return util.format(example, PhoneNumberUtil.PhoneNumberFormat.E164)
    }

    /**
     * A fresh number for [slot], different from the previous one.
     *
     * Backed by a clock nonce rather than a counter so two profiles generated in
     * the same millisecond do not collide.
     */
    fun randomize(countryIso: String, carrier: String, slot: Int): String =
        generate(countryIso, carrier, seed = "", slot = slot, nonce = System.nanoTime())

    /** Normalises [raw] to E.164, or null when it is not valid for the region. */
    fun normalize(raw: String, countryIso: String): String? {
        val text = raw.trim()
        if (text.isEmpty()) return null
        val region = region(countryIso)
        if (region == null) return text.takeIf { E164.matches(it) }
        val parsed = runCatching { util.parse(text, region) }.getOrNull() ?: return null
        if (!runCatching { util.isValidNumber(parsed) }.getOrDefault(false)) return null
        return util.format(parsed, PhoneNumberUtil.PhoneNumberFormat.E164)
    }

    /** A human message when [raw] is not acceptable, or null. */
    fun validate(raw: String, countryIso: String): String? {
        val text = raw.trim()
        if (text.isEmpty()) return null

        val region = region(countryIso)
        if (region == null) {
            return if (E164.matches(text)) {
                null
            } else {
                "Enter the number in international form, e.g. +8801XXXXXXXXX."
            }
        }

        val parsed = runCatching { util.parse(text, region) }.getOrNull()
            ?: return "That is not a phone number."
        val valid = runCatching { util.isValidNumber(parsed) }.getOrDefault(false)
        return if (valid) null else "Not a valid $region mobile number."
    }

    /** True when [raw] is a valid number for the profile's country. */
    fun isValid(raw: String, countryIso: String): Boolean =
        raw.isNotBlank() && validate(raw, countryIso) == null

    /**
     * Fills the profile's phone slots with numbers that fit [countryIso].
     *
     * Existing numbers that are already valid for the country *and* belong to
     * the selected carrier's series are kept, so switching between two carriers
     * that share a series does not rewrite the user's choice. Blank, foreign, or
     * wrong-operator slots are regenerated and pinned so the hook returns them.
     */
    fun ensureForCountry(artifact: DoppeliskArtifact, countryIso: String): DoppeliskArtifact {
        val telephony = artifact.identity.telephony
        val carrier = telephony.carrier
        val wanted = when {
            telephony.simSlots in 1..2 -> telephony.simSlots
            telephony.phoneNumbers.count { it.isNotBlank() } >= 2 -> 2
            else -> 1
        }

        val numbers = (0 until wanted).map { slot ->
            val existing = telephony.phoneNumber(slot)
            if (isValid(existing, countryIso) && fitsCarrier(existing, countryIso, carrier)) {
                existing
            } else {
                generate(countryIso, carrier, artifact.id, slot)
            }
        }

        var updated = artifact.copy(
            identity = artifact.identity.copy(
                telephony = telephony.copy(phoneNumbers = numbers),
            ),
        )
        for ((slot, number) in numbers.withIndex()) {
            if (number.isBlank()) continue
            updated = updated.withOverride(
                if (slot == 0) IdentityFields.TELEPHONY_PHONE_1 else IdentityFields.TELEPHONY_PHONE_2,
            )
        }
        return updated
    }

    /**
     * Seeds [artifact] with the numbers this phone's SIMs actually report.
     *
     * A profile that claims a SIM is more convincing when it claims the number
     * that SIM really has: apps that bind a session to the SIM — mobile banking
     * is the usual one — compare the number they were registered with. Numbers
     * the device does not expose are left as they were.
     */
    fun seedFromDevice(artifact: DoppeliskArtifact, numbers: List<String>): DoppeliskArtifact {
        val telephony = artifact.identity.telephony
        val clean = numbers.take(2).map { raw ->
            raw.trim().let { normalize(it, telephony.countryIso) ?: it }
        }
        if (clean.none { it.isNotBlank() }) return artifact

        val merged = (0 until maxOf(clean.size, telephony.phoneNumbers.size)).map { slot ->
            clean.getOrNull(slot).orEmpty().ifBlank { telephony.phoneNumber(slot) }
        }

        var updated = artifact.copy(
            identity = artifact.identity.copy(
                telephony = telephony.copy(phoneNumbers = merged),
            ),
        )
        for ((slot, number) in merged.withIndex()) {
            if (number.isBlank()) continue
            updated = updated.withOverride(
                if (slot == 0) IdentityFields.TELEPHONY_PHONE_1 else IdentityFields.TELEPHONY_PHONE_2,
            )
        }
        return updated
    }

    /** The libphonenumber region for an ISO code, or null when it is not one. */
    private fun region(countryIso: String): String? {
        val code = countryIso.trim().uppercase(Locale.ROOT)
        if (code.length != 2) return null
        return code.takeIf { runCatching { util.getSupportedRegions().contains(it) }.getOrDefault(false) }
    }

    /** Replaces the leading digits with the carrier's series, when known. */
    private fun withCarrierSeries(
        region: String,
        carrier: String,
        exampleNational: String,
        seed: String,
        slot: Int,
        nonce: Long,
    ): String {
        val prefixes = CarrierSeries.prefixesFor(region, carrier)
        if (prefixes.isEmpty()) return exampleNational

        val index = Math.floorMod(seedHash(seed) + slot + nonce, prefixes.size.toLong()).toInt()
        val prefix = prefixes[index]
        if (prefix.length >= exampleNational.length) return exampleNational
        return prefix + exampleNational.drop(prefix.length)
    }

    /** True when [number] belongs to [carrier]'s series (or the series is unknown). */
    private fun fitsCarrier(number: String, countryIso: String, carrier: String): Boolean {
        val region = region(countryIso) ?: return true
        val national = runCatching { util.parse(number, region) }
            .getOrNull()
            ?.nationalNumber
            ?.toString()
            ?: return false
        return CarrierSeries.matches(region, carrier, national)
    }

    private fun seedHash(seed: String): Long {
        var hash = 1125899906842597L
        for (char in seed) hash = 31L * hash + char.code
        return hash
    }

    private val E164 = Regex("\\+[1-9]\\d{6,14}")
    private const val SUFFIX_DIGITS = 4
    private const val SUFFIX_MODULUS = 10_000L
    private const val ATTEMPTS = 40
}
