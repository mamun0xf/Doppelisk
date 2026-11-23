package com.doppelisk.hook.generic

import com.doppelisk.core.model.IdentityFields
import com.doppelisk.hook.api.HookContext
import com.doppelisk.hook.api.anyApplies

/**
 * Rewrites user-agent strings so they describe the artifact instead of the
 * phone.
 *
 * This is the one piece of identity that leaks *outside* the Java API surface:
 * it travels in HTTP headers to the app's own servers, where it is logged,
 * fingerprinted and compared against the `Build` values the app reported. A
 * spoofed `Build` with a real user agent is the easiest contradiction to spot,
 * which is why every UA-bearing path is rewritten with the same function.
 */
internal object UserAgent {

    /** True when the profile controls any field that appears in a user agent. */
    fun isActive(context: HookContext): Boolean = context.anyApplies(
        IdentityFields.BUILD_MODEL,
        IdentityFields.BUILD_RELEASE,
        IdentityFields.BUILD_ID,
    )

    /**
     * True when [ua] still contains a real identifier.
     *
     * Some apps build a deliberately custom UA; rewriting one that carries no
     * real value would be pointless and might break server-side parsing, so the
     * per-instance hooks only touch a UA that actually leaks something.
     */
    fun carriesRealIdentity(context: HookContext, ua: String): Boolean {
        val real = context.realBuild
        if (real.model.isNotBlank() && ua.contains(real.model)) return true
        if (real.buildId.isNotBlank() && ua.contains("Build/${real.buildId}")) return true
        return real.release.isNotBlank() && ua.contains("Android ${real.release}")
    }

    /** Replaces the real model, release and build id with the artifact's. */
    fun rewrite(context: HookContext, ua: String?): String? {
        if (ua.isNullOrBlank()) return ua

        val target = context.artifact.identity.build
        val source = context.realBuild

        var out = ua
        if (source.release.isNotBlank() && target.release.isNotBlank()) {
            out = out.replace("Android ${source.release}", "Android ${target.release}")
        }
        if (source.model.isNotBlank() && target.model.isNotBlank()) {
            out = out.replace(source.model, target.model)
        }
        if (target.id.isNotBlank()) {
            if (source.buildId.isNotBlank()) {
                out = out.replace("Build/${source.buildId}", "Build/${target.id}")
            }
            // A UA often carries two `Build/…` tokens (the app's and the
            // system's); the second one is only replaced by pattern.
            out = BUILD_TOKEN.replace(out, "Build/${target.id}")
        }
        return out
    }

    /** Matches the `Build/<id>` token, whatever id is currently in it. */
    private val BUILD_TOKEN = Regex("Build/[A-Za-z0-9._-]+")
}
