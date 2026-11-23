package com.doppelisk.hook.generic

import com.doppelisk.hook.api.HookContext
import com.doppelisk.hook.api.PackChain
import com.doppelisk.hook.generic.HookSupport.hook
import com.doppelisk.hook.generic.HookSupport.hookLazy
import com.doppelisk.hook.generic.HookSupport.record
import io.github.libxposed.api.XposedInterface
import java.net.URLConnection
import java.util.Locale

/**
 * Rewrites the `User-Agent` header on the two HTTP stacks Android apps use.
 *
 * The WebView hooks cover the browser; this covers the requests the app makes
 * itself. `URLConnection` is the platform path, and OkHttp is the library path —
 * OkHttp is bundled into most apps, so it is usually the one that matters.
 *
 * Both are hooked at the point where a header is *set*, not where it is sent, so
 * the rewrite happens once per request rather than once per retry, and a header
 * the app never set is never invented.
 */
object HttpHeaderHooks {

    fun install(xposed: XposedInterface, context: HookContext, classLoader: ClassLoader?) {
        if (!UserAgent.isActive(context)) return

        hookUrlConnection(xposed, context)
        hookOkHttp(xposed, context, classLoader)
    }

    // ------------------------------------------------------ URLConnection

    private fun hookUrlConnection(xposed: XposedInterface, context: HookContext) {
        hookLazy(
            xposed,
            context,
            "URLConnection.setRequestProperty",
            {
                URLConnection::class.java.getMethod(
                    "setRequestProperty",
                    String::class.java,
                    String::class.java,
                )
            },
        ) { chain ->
            rewriteHeaderCall(context, chain, "URLConnection.setRequestProperty")
        }
    }

    // ------------------------------------------------------------- OkHttp

    private fun hookOkHttp(xposed: XposedInterface, context: HookContext, classLoader: ClassLoader?) {
        // `Request.Builder.header/addHeader` is the public path; `Headers.Builder`
        // is what the request builders delegate to, and it is also called
        // directly by code that builds headers ahead of time.
        val requestBuilder = HookSupport.findClass("okhttp3.Request\$Builder", classLoader)
        if (requestBuilder != null) {
            for (name in listOf("header", "addHeader")) {
                hookAllOverloads(xposed, context, requestBuilder, name)
            }
        }

        val headersBuilder = HookSupport.findClass("okhttp3.Headers\$Builder", classLoader)
        if (headersBuilder != null) {
            for (name in listOf("add", "set", "addUnsafeNonAscii", "addLenient\$okhttp")) {
                hookAllOverloads(xposed, context, headersBuilder, name)
            }
        }
    }

    /**
     * Hooks every overload of [name] declared by [owner].
     *
     * OkHttp has several overloads per method, and the obfuscated builds in
     * release APKs keep the names but change the descriptors, so matching by
     * name and hooking each one is the only reliable approach.
     */
    private fun hookAllOverloads(
        xposed: XposedInterface,
        context: HookContext,
        owner: Class<*>,
        name: String,
    ) {
        val methods = runCatching {
            owner.declaredMethods.filter { it.name == name && !it.isSynthetic }
        }.getOrDefault(emptyList())

        if (methods.isEmpty()) {
            context.log("${owner.name}.$name not found; header hook skipped")
            return
        }

        for (method in methods) {
            hook(xposed, context, "${owner.simpleName}.$name", method) { chain ->
                rewriteHeaderCall(context, chain, "${owner.simpleName}.$name")
            }
        }
    }

    // ------------------------------------------------------------ rewrite

    private fun rewriteHeaderCall(context: HookContext, chain: PackChain, label: String): Any? {
        val name = chain.arg(0) as? String
        val value = chain.arg(1) as? String

        if (name == null || value == null) return chain.proceed()
        if (!name.equals("user-agent", ignoreCase = true)) return chain.proceed()

        val fake = UserAgent.rewrite(context, value)
        if (fake == null || fake == value) return chain.proceed()

        record(context, "ua", "headers.${name.lowercase(Locale.ROOT)}", value, fake)

        val args = chain.args.copyOf()
        args[0] = name
        args[1] = fake
        return chain.proceedWith(args)
    }
}
