package com.doppelisk.hook.generic

import android.content.Context
import android.webkit.WebSettings
import com.doppelisk.hook.api.HookContext
import com.doppelisk.hook.generic.HookSupport.hookLazy
import com.doppelisk.hook.generic.HookSupport.record
import io.github.libxposed.api.XposedInterface

/**
 * Rewrites the WebView user agent.
 *
 * The user agent contains `Android <release>`, the model and `Build/<id>`, so a
 * spoofed `Build` with a real user agent is an immediate contradiction. Two
 * getters are hooked because one returns the default UA and the other the UA of
 * a particular `WebSettings` instance.
 */
object WebViewHooks {

    fun install(xposed: XposedInterface, context: HookContext) {
        if (!UserAgent.isActive(context)) return

        hookLazy(
            xposed,
            context,
            "WebSettings.getDefaultUserAgent",
            { WebSettings::class.java.getMethod("getDefaultUserAgent", Context::class.java) },
        ) { chain ->
            val real = runCatching { chain.proceed() }.getOrNull() as? String
            val fake = UserAgent.rewrite(context, real)
            if (real != null && fake != null) {
                record(context, "ua", "WebSettings.getDefaultUserAgent", real, fake)
            }
            fake
        }

        hookLazy(
            xposed,
            context,
            "WebSettings.getUserAgentString",
            { WebSettings::class.java.getMethod("getUserAgentString") },
        ) { chain ->
            val real = runCatching { chain.proceed() }.getOrNull() as? String
            if (real != null && UserAgent.carriesRealIdentity(context, real)) {
                val fake = UserAgent.rewrite(context, real)
                if (fake != null) {
                    record(context, "ua", "WebSettings.getUserAgentString", real, fake)
                }
                fake
            } else {
                real
            }
        }
    }
}
