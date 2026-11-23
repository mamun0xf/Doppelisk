package com.doppelisk.hook.generic

import android.location.Location
import android.location.LocationManager
import android.os.SystemClock
import com.doppelisk.core.identity.GeoTime
import com.doppelisk.core.identity.LocationFix
import com.doppelisk.core.identity.LocationModes
import com.doppelisk.core.identity.LocationPlayback
import com.doppelisk.hook.api.HookContext
import com.doppelisk.hook.api.PackChain
import com.doppelisk.hook.generic.HookSupport.hook
import com.doppelisk.hook.generic.HookSupport.hookLazy
import io.github.libxposed.api.XposedInterface
import java.time.ZoneId
import java.util.TimeZone
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.function.Consumer

/**
 * Replaces the device's location with the profile's.
 *
 * A `Location` is faked at two levels. Individual getters are rewritten so that
 * any `Location` object the app already holds reports the fake values, and the
 * `LocationManager` entry points are replaced so a fresh fix is a fake object
 * from the start. Both are needed: hooking only the manager leaves cached
 * locations real, and hooking only the getters leaves `isFromMockProvider` and
 * the timestamp inconsistent.
 *
 * The fix is recomputed once per second from the profile seed, so repeated calls
 * within the same second return identical coordinates — a jitter that changes on
 * every call is itself suspicious.
 */
object LocationHooks {

    private val cached = AtomicReference<Cached?>(null)

    fun install(xposed: XposedInterface, context: HookContext) {
        hookTimeZone(xposed, context)

        if (LocationModes.normalize(context.artifact.location.mode) == LocationModes.OFF) return

        hookLocationGetters(xposed, context)
        hookManager(xposed, context)
    }

    // ------------------------------------------------------------- snapshot

    /**
     * The fix for the current one-second bucket.
     *
     * The cache is keyed by the bucket only, not by provider: the coordinates are
     * the same for every provider, and only the label differs, which
     * [fakeLocation] sets on the object it builds.
     */
    private fun snapshot(context: HookContext, provider: String = "gps"): LocationFix? {
        val bucket = System.currentTimeMillis() / 1000
        cached.get()?.let { if (it.bucket == bucket) return it.fix }

        val fix = LocationPlayback.fix(
            context.artifact.location,
            context.artifact.id,
            bucket * 1000,
            provider,
        ) ?: return null

        cached.set(Cached(bucket, fix))
        context.boundary.record(
            "location",
            "fix",
            null,
            "${fix.latitude},${fix.longitude}±${fix.accuracy}",
        )
        return fix
    }

    private fun fakeLocation(context: HookContext, provider: String): Location? {
        val fix = snapshot(context, provider) ?: return null

        return Location(provider).apply {
            latitude = fix.latitude
            longitude = fix.longitude
            accuracy = fix.accuracy
            time = fix.time
            elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
            if (fix.speed > 0f) speed = fix.speed
            if (fix.bearing > 0f) bearing = fix.bearing
            altitude = fix.altitude
        }
    }

    // -------------------------------------------------------------- getters

    private fun hookLocationGetters(xposed: XposedInterface, context: HookContext) {
        fun hookGetter(name: String, fallback: (Any?) -> Any?) {
            hookLazy(
                xposed,
                context,
                "Location.$name",
                { Location::class.java.getMethod(name) },
            ) { chain ->
                val real = runCatching { chain.proceed() }.getOrNull()
                runCatching { fallback(real) }.getOrElse { real }
            }
        }

        hookGetter("getLatitude") { snapshot(context)?.latitude ?: it }
        hookGetter("getLongitude") { snapshot(context)?.longitude ?: it }
        hookGetter("getAccuracy") { snapshot(context)?.accuracy ?: it }
        hookGetter("getAltitude") { snapshot(context)?.altitude ?: it }
        hookGetter("getSpeed") { snapshot(context)?.speed ?: it }
        hookGetter("getBearing") { snapshot(context)?.bearing ?: it }
        hookGetter("getTime") { System.currentTimeMillis() }
        hookGetter("getElapsedRealtimeNanos") { SystemClock.elapsedRealtimeNanos() }
        hookGetter("hasAccuracy") { true }
        hookGetter("hasAltitude") { false }
        hookGetter("hasSpeed") { (snapshot(context)?.speed ?: 0f) > 0f }
        hookGetter("hasBearing") { (snapshot(context)?.bearing ?: 0f) > 0f }
        hookGetter("isFromMockProvider") { false }
        hookGetter("isMock") { false }
        hookGetter("getProvider") { snapshot(context)?.provider ?: it }
    }

    // -------------------------------------------------------------- manager

    private fun hookManager(xposed: XposedInterface, context: HookContext) {
        hookLazy(
            xposed,
            context,
            "LocationManager.getLastKnownLocation",
            { LocationManager::class.java.getMethod("getLastKnownLocation", String::class.java) },
        ) { chain ->
            val provider = chain.arg(0) as? String ?: "gps"
            fakeLocation(context, provider) ?: chain.proceed()
        }

        hookLazy(
            xposed,
            context,
            "LocationManager.isLocationEnabled",
            { LocationManager::class.java.getMethod("isLocationEnabled") },
        ) { true }

        hookLazy(
            xposed,
            context,
            "LocationManager.isProviderEnabled",
            { LocationManager::class.java.getMethod("isProviderEnabled", String::class.java) },
        ) { chain ->
            val provider = chain.arg(0) as? String
            if (provider == "gps" || provider == "network" || provider == "passive") {
                true
            } else {
                (runCatching { chain.proceed() }.getOrNull() as? Boolean) == true
            }
        }

        val currentLocation = runCatching {
            LocationManager::class.java.declaredMethods.filter { it.name == "getCurrentLocation" }
        }.getOrDefault(emptyList())

        for (method in currentLocation) {
            hook(xposed, context, "LocationManager.getCurrentLocation", method) { chain ->
                interceptCurrentLocation(chain, context)
            }
        }
    }

    /**
     * Delivers a fake location through the callback of `getCurrentLocation`.
     *
     * The callback is the last `Consumer` argument. If the real call already
     * invoked it, the fake is dropped; otherwise it is posted to the executor
     * argument when there is one, so the callback still arrives off the main
     * thread the way callers expect.
     */
    private fun interceptCurrentLocation(chain: PackChain, context: HookContext): Any? {
        val provider = chain.arg(0) as? String ?: "gps"
        val fake = fakeLocation(context, provider) ?: return chain.proceed()

        val args = chain.args.toMutableList()
        val index = args.indexOfLast { it is Consumer<*> }
        if (index < 0) return chain.proceed()

        @Suppress("UNCHECKED_CAST")
        val original = args[index] as Consumer<Location>
        val delivered = AtomicBoolean(false)

        val wrapped = Consumer<Location> { location ->
            if (delivered.compareAndSet(false, true)) original.accept(location)
        }
        args[index] = wrapped

        val result = chain.proceedWith(args.toTypedArray())

        if (!delivered.get()) {
            val executor = args.getOrNull(2) as? Executor
            if (executor != null) {
                runCatching { executor.execute { wrapped.accept(fake) } }
            } else {
                runCatching { wrapped.accept(fake) }
            }
        }
        return result
    }

    // ------------------------------------------------------------- timezone

    /**
     * Makes the default time zone agree with the fake location.
     *
     * The zone is derived from the profile's country (and explicit override)
     * rather than from the phone, because `TimeZone.getDefault()` is one of the
     * cheapest ways to notice that the device "moved" without the clock moving.
     */
    private fun hookTimeZone(xposed: XposedInterface, context: HookContext) {
        val artifact = context.artifact
        val zone = GeoTime.zoneFor(
            artifact.identity.telephony.countryIso,
            artifact.location.timezone,
            artifact.location.lat,
            artifact.location.lon,
        ) ?: return

        val zoneId = runCatching { ZoneId.of(zone) }.getOrNull() ?: return

        hookLazy(
            xposed,
            context,
            "TimeZone.getDefault",
            { TimeZone::class.java.getMethod("getDefault") },
        ) { TimeZone.getTimeZone(zone) }

        hookLazy(
            xposed,
            context,
            "ZoneId.systemDefault",
            { ZoneId::class.java.getMethod("systemDefault") },
        ) { zoneId }
    }

    private data class Cached(val bucket: Long, val fix: LocationFix)
}
