package com.doppelisk.hook.generic

import com.doppelisk.hook.api.HookContext
import com.doppelisk.hook.api.PackChain
import com.doppelisk.hook.api.PackHook
import com.doppelisk.hook.engine.XposedPackChain
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Executable

/**
 * Small helpers shared by the generic hooks.
 *
 * Hooks run inside someone else's app, so every one of them is wrapped: a hook
 * that cannot be installed is logged and skipped, never thrown. That is why this
 * file exists — otherwise every hook class repeats the same six lines of
 * `runCatching`.
 */
internal object HookSupport {

    /**
     * Installs [hook] on [executable], logging [label] if it fails.
     *
     * A null [executable] means the member does not exist on this Android
     * version; that is normal and silent.
     */
    fun hook(
        xposed: XposedInterface,
        context: HookContext,
        label: String,
        executable: Executable?,
        priority: Int = XposedInterface.PRIORITY_DEFAULT,
        hook: (PackChain) -> Any?,
    ) {
        if (executable == null) return

        val error = runCatching {
            xposed.hook(executable)
                .setPriority(priority)
                .intercept { chain -> hook(XposedPackChain(chain)) }
        }.exceptionOrNull()

        if (error != null) context.log("$label hook failed: ${error.message}")
    }

    /** Same as [hook], but resolves the member lazily so a missing class is fine. */
    fun hookLazy(
        xposed: XposedInterface,
        context: HookContext,
        label: String,
        resolve: () -> Executable?,
        priority: Int = XposedInterface.PRIORITY_DEFAULT,
        hook: (PackChain) -> Any?,
    ) = hook(xposed, context, label, runCatching(resolve).getOrNull(), priority, hook)

    /** Hooks every overload of [methodName] declared by [owner]. */
    fun hookAll(
        xposed: XposedInterface,
        context: HookContext,
        label: String,
        owner: Class<*>?,
        methodName: String,
        priority: Int = XposedInterface.PRIORITY_DEFAULT,
        hook: (PackChain) -> Any?,
    ) {
        if (owner == null) return
        val methods = runCatching {
            owner.declaredMethods.filter { it.name == methodName && !it.isSynthetic }
        }.getOrDefault(emptyList())

        if (methods.isEmpty()) {
            context.log("$label hook skipped: ${owner.name}.$methodName not found")
            return
        }
        for (method in methods) hook(xposed, context, label, method, priority, hook)
    }

    /**
     * Finds a class through the app's loader, then the thread context loader,
     * then the system loader.
     *
     * Library classes such as OkHttp may be visible only through the app's
     * loader, and framework classes only through the system one, so all three
     * are tried before giving up.
     */
    fun findClass(name: String, classLoader: ClassLoader?): Class<*>? {
        val candidates = listOfNotNull(classLoader, Thread.currentThread().contextClassLoader)
        for (candidate in candidates) {
            runCatching { Class.forName(name, false, candidate) }.getOrNull()?.let { return it }
        }
        return runCatching { Class.forName(name) }.getOrNull()
    }

    /** Reads a `String` argument, or null when the app passed something else. */
    fun PackChain.stringArg(index: Int): String? = arg(index) as? String

    /** Reads an `Int` argument with a fallback for boxing differences. */
    fun PackChain.intArg(index: Int): Int? = (arg(index) as? Number)?.toInt()

    /** Reads a `Long` argument with a fallback for boxing differences. */
    fun PackChain.longArg(index: Int): Long? = (arg(index) as? Number)?.toLong()

    /** Treats a `proceed()` result as a string, or null when it was not one. */
    fun asString(value: Any?): String? = value as? String

    /** Sets a `static final` field, which is how `Build` values are spoofed. */
    fun setStaticFinal(owner: Class<*>, name: String, value: Any?) {
        runCatching {
            owner.getDeclaredField(name).apply { isAccessible = true }.set(null, value)
        }
    }

    /** Reads a `static final String` field, or null when it is absent. */
    fun staticStringField(owner: Class<*>, name: String): String? =
        runCatching { owner.getField(name).get(null) as? String }.getOrNull()

    /**
     * Records an observation.
     *
     * Equal values are kept: the report filters them out of `mismatches`, but the
     * full observation list is still useful when diagnosing a hook that fires with
     * the wrong value.
     */
    fun record(context: HookContext, category: String, field: String, real: Any?, fake: Any?) {
        context.boundary.record(category, field, real?.toString(), fake?.toString())
    }
}
