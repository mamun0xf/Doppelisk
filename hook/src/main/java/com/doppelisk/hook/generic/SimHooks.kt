package com.doppelisk.hook.generic

import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import com.doppelisk.core.model.TelephonyIdentity
import com.doppelisk.hook.api.HookContext
import com.doppelisk.hook.generic.HookSupport.hookAll
import com.doppelisk.hook.generic.HookSupport.hookLazy
import com.doppelisk.hook.generic.HookSupport.record
import io.github.libxposed.api.XposedInterface
import java.security.MessageDigest

/**
 * The SIM surface: presence, slots, phone numbers and the identifiers apps use
 * to bind a session to a SIM.
 *
 * Removing a SIM normally flips `getSimState()` to `ABSENT`, empties
 * `getActiveSubscriptionInfoList()` and makes `getLine1Number()` and
 * `getSubscriberId()` return nothing. Apps that bind a login to the SIM — bKash
 * is the one that prompted this — treat that as "the user changed devices" and
 * log out. A profile that claims a carrier therefore has to claim a present SIM
 * as well, with identifiers derived from the same identity so two profiles on
 * one phone do not look like the same subscriber.
 */
object SimHooks {

    fun install(xposed: XposedInterface, context: HookContext) {
        val telephony = context.artifact.identity.telephony
        val slots = telephony.effectiveSimSlots
        val numbers = telephony.phoneNumbers

        // Without a carrier numeric an IMSI cannot be built, and a bare
        // "SIM present" claim is still useful: it is the half bKash checks
        // first. Install the presence hooks either way.
        val hasNumber = numbers.any { it.isNotBlank() }

        hookAll(
            xposed,
            context,
            "TelephonyManager.getSimState",
            TelephonyManager::class.java,
            "getSimState",
        ) { chain ->
            val real = runCatching { chain.proceed() }.getOrNull()
            record(context, "telephony", "getSimState", real, TelephonyManager.SIM_STATE_READY)
            TelephonyManager.SIM_STATE_READY
        }

        hookAll(
            xposed,
            context,
            "TelephonyManager.hasIccCard",
            TelephonyManager::class.java,
            "hasIccCard",
        ) { chain ->
            val real = runCatching { chain.proceed() }.getOrNull()
            record(context, "telephony", "hasIccCard", real, true)
            true
        }

        for (method in listOf("getPhoneCount", "getActiveModemCount")) {
            hookAll(xposed, context, "TelephonyManager.$method", TelephonyManager::class.java, method) { chain ->
                val real = runCatching { chain.proceed() }.getOrNull()
                record(context, "telephony", method, real, slots)
                slots
            }
        }

        if (hasNumber) {
            hookAll(
                xposed,
                context,
                "TelephonyManager.getLine1Number",
                TelephonyManager::class.java,
                "getLine1Number",
            ) { chain ->
                val slot = subscriptionSlot(chain.arg(0))
                val fake = numbers.getOrNull(slot).orEmpty().ifBlank { numbers.firstOrNull().orEmpty() }
                if (fake.isBlank()) {
                    runCatching { chain.proceed() }.getOrNull()
                } else {
                    val real = runCatching { chain.proceed() }.getOrNull()
                    record(context, "telephony", "getLine1Number", real, fake)
                    fake
                }
            }
        }

        val subscriber = imsi(telephony, context.artifact.id, 0)
        if (subscriber.isNotBlank()) {
            hookAll(
                xposed,
                context,
                "TelephonyManager.getSubscriberId",
                TelephonyManager::class.java,
                "getSubscriberId",
            ) { chain ->
                val slot = subscriptionSlot(chain.arg(0))
                val fake = imsi(telephony, context.artifact.id, slot)
                if (fake.isBlank()) {
                    runCatching { chain.proceed() }.getOrNull()
                } else {
                    val real = runCatching { chain.proceed() }.getOrNull()
                    record(context, "telephony", "getSubscriberId", real, fake)
                    fake
                }
            }
        }

        val iccid = iccid(telephony, context.artifact.id, 0)
        if (iccid.isNotBlank()) {
            hookAll(
                xposed,
                context,
                "TelephonyManager.getSimSerialNumber",
                TelephonyManager::class.java,
                "getSimSerialNumber",
            ) { chain ->
                val slot = subscriptionSlot(chain.arg(0))
                val fake = iccid(telephony, context.artifact.id, slot)
                if (fake.isBlank()) {
                    runCatching { chain.proceed() }.getOrNull()
                } else {
                    val real = runCatching { chain.proceed() }.getOrNull()
                    record(context, "telephony", "getSimSerialNumber", real, fake)
                    fake
                }
            }
        }

        // A SIM that reports READY while the network type says "unknown" is
        // still suspicious. LTE is the safest claim for a modern profile.
        for (method in listOf("getNetworkType", "getDataNetworkType", "getVoiceNetworkType")) {
            hookAll(xposed, context, "TelephonyManager.$method", TelephonyManager::class.java, method) { chain ->
                val real = runCatching { chain.proceed() }.getOrNull()
                record(context, "telephony", method, real, TelephonyManager.NETWORK_TYPE_LTE)
                TelephonyManager.NETWORK_TYPE_LTE
            }
        }

        hookLazy(
            xposed,
            context,
            "SubscriptionManager.getActiveSubscriptionInfoCount",
            { SubscriptionManager::class.java.getMethod("getActiveSubscriptionInfoCount") },
        ) { chain ->
            val real = runCatching { chain.proceed() }.getOrNull()
            record(context, "telephony", "getActiveSubscriptionInfoCount", real, slots)
            slots
        }

        hookLazy(
            xposed,
            context,
            "SubscriptionManager.getActiveSubscriptionInfoCountMax",
            { SubscriptionManager::class.java.getMethod("getActiveSubscriptionInfoCountMax") },
        ) { chain ->
            val real = runCatching { chain.proceed() }.getOrNull()
            record(context, "telephony", "getActiveSubscriptionInfoCountMax", real, slots)
            slots
        }

        hookLazy(
            xposed,
            context,
            "SubscriptionManager.getDefaultSubscriptionId",
            { SubscriptionManager::class.java.getMethod("getDefaultSubscriptionId") },
        ) { chain ->
            val real = runCatching { chain.proceed() }.getOrNull()
            val fake = if (slots > 0) 1 else SubscriptionManager.INVALID_SUBSCRIPTION_ID
            record(context, "telephony", "getDefaultSubscriptionId", real, fake)
            fake
        }
    }

    /**
     * Maps an overload's argument to a slot index.
     *
     * The public API calls it a subscription id, not a slot, and the two are
     * not the same number. On a two-SIM device the first subscription is 1 and
     * the second is 2, which is the mapping used here; anything else falls back
     * to the first slot.
     */
    private fun subscriptionSlot(argument: Any?): Int {
        val id = (argument as? Number)?.toInt() ?: return 0
        return if (id > 1) 1 else 0
    }

    /**
     * A 15-digit IMSI for [slot], built from the profile's MCC/MNC.
     *
     * `MCC + MNC + subscriber` is the E.212 layout. The subscriber half is
     * derived from the profile id, so it is stable across launches but differs
     * between profiles and slots.
     */
    private fun imsi(telephony: TelephonyIdentity, seed: String, slot: Int): String {
        val mcc = telephony.mcc.filter(Char::isDigit)
        val mnc = telephony.mnc.filter(Char::isDigit)
        if (mcc.length != 3 || mnc.length !in 2..3) return ""
        val length = 15 - mcc.length - mnc.length
        return mcc + mnc + digits(seed, "imsi", slot, length)
    }

    /**
     * An ICCID for [slot]: `89` + MCC + derived digits + Luhn check digit.
     *
     * The leading `89` is the telecom issuer identifier every SIM uses. The
     * rest is not a real issuer code, but the length and check digit are
     * correct, which is what format validators look at.
     */
    private fun iccid(telephony: TelephonyIdentity, seed: String, slot: Int): String {
        val mcc = telephony.mcc.filter(Char::isDigit)
        if (mcc.length != 3) return ""
        val body = "89" + mcc + digits(seed, "iccid", slot, 13)
        return body + luhn(body)
    }

    private fun digits(seed: String, salt: String, slot: Int, count: Int): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$seed|$salt|$slot".toByteArray(Charsets.UTF_8))
        val builder = StringBuilder(count)
        var index = 0
        while (builder.length < count) {
            val byte = digest[index % digest.size].toInt() and 0xFF
            builder.append(byte % 10)
            index++
        }
        return builder.toString()
    }

    private fun luhn(body: String): Int {
        var sum = 0
        var double = true
        for (index in body.indices.reversed()) {
            var digit = body[index] - '0'
            if (double) {
                digit *= 2
                if (digit > 9) digit -= 9
            }
            sum += digit
            double = !double
        }
        return (10 - sum % 10) % 10
    }
}
