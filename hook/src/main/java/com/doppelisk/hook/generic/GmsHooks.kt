package com.doppelisk.hook.generic

import com.doppelisk.core.identity.ScopedIds
import com.doppelisk.core.model.IdentityFields
import com.doppelisk.hook.api.HookContext
import com.doppelisk.hook.api.PolicyMode
import com.doppelisk.hook.api.applies
import com.doppelisk.hook.generic.HookSupport.asString
import com.doppelisk.hook.generic.HookSupport.hook
import com.doppelisk.hook.generic.HookSupport.record
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Executable

/**
 * Rewrites the two Google Play Services identifiers that are read through
 * `Info.getId()`.
 *
 * They are optional: an app that has Play Services uses them for ad attribution,
 * an app that does not simply never loads the class. Missing classes are
 * therefore expected, not an error.
 */
object GmsHooks {

    fun install(xposed: XposedInterface, context: HookContext, classLoader: ClassLoader?) {
        val ids = context.artifact.identity.ids

        if (context.applies(IdentityFields.ID_GAID)) {
            hookInfo(
                xposed,
                context,
                classLoader,
                "com.google.android.gms.ads.identifier.AdvertisingIdClient\$Info",
                "advertising_id",
                ids.gaid,
            )
        }

        if (context.applies(IdentityFields.ID_APP_SET)) {
            val appSetId = ids.appSetId.ifBlank {
                ScopedIds.uuid(context.artifact.id, "app_set_id")
            }
            hookInfo(
                xposed,
                context,
                classLoader,
                "com.google.android.gms.appset.AppSetIdInfo",
                "app_set_id",
                appSetId,
            )
        }
    }

    private fun hookInfo(
        xposed: XposedInterface,
        context: HookContext,
        classLoader: ClassLoader?,
        className: String,
        field: String,
        value: String,
    ) {
        if (context.modeFor("ads", field) == PolicyMode.REAL) return

        val infoClass = findClass(className, classLoader) ?: return
        val getId: Executable? = runCatching { infoClass.getMethod("getId") }.getOrNull()

        hook(xposed, context, "GMS $field", getId) { chain ->
            val real = runCatching { chain.proceed() }.getOrNull()
            context.boundary.record("ads", field, asString(real), value)
            value
        }
    }

    /**
     * Finds [name] through the app's loader, then the thread context loader, then
     * the system loader.
     *
     * GMS is a separate APK, so the class is not on the hooked app's loader; it is
     * only visible through the context loader once the app has touched GMS.
     */
    private fun findClass(name: String, classLoader: ClassLoader?): Class<*>? {
        val candidates = listOfNotNull(classLoader, Thread.currentThread().contextClassLoader)
        for (candidate in candidates) {
            runCatching { Class.forName(name, false, candidate) }.getOrNull()?.let { return it }
        }
        return runCatching { Class.forName(name) }.getOrNull()
    }
}
