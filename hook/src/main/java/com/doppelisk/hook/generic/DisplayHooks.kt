package com.doppelisk.hook.generic

import android.app.Activity
import android.content.Context
import android.graphics.Point
import android.graphics.Rect
import android.util.DisplayMetrics
import android.view.Display
import com.doppelisk.core.identity.ChassisCatalog
import com.doppelisk.core.model.DisplayIdentity
import com.doppelisk.core.model.IdentityFields
import com.doppelisk.hook.api.HookContext
import com.doppelisk.hook.api.anyApplies
import com.doppelisk.hook.api.applies
import com.doppelisk.hook.engine.RootHiding
import com.doppelisk.hook.generic.HookSupport.hookLazy
import com.doppelisk.hook.generic.HookSupport.intArg
import com.doppelisk.hook.generic.HookSupport.record
import io.github.libxposed.api.XposedInterface
import kotlin.math.hypot

/**
 * Makes the screen and the GPU look like the chassis.
 *
 * Display metrics are read through several unrelated APIs — `Display.getMetrics`,
 * `Display.getSize`, `WindowMetrics.getBounds`, `Display.Mode` — and every one of
 * them has to agree, because comparing two of them is a one-line check. The same
 * applies to the OpenGL strings: `GL_RENDERER` alone names the SoC family, so the
 * vendor, renderer, version and GLSL version are all replaced together.
 *
 * Screen *density* is recomputed from the spoofed pixel count and the chassis's
 * diagonal in inches, so `xdpi` stays consistent with `widthPixels`.
 */
object DisplayHooks {

    private const val EGL_VENDOR = 12371
    private const val EGL_VERSION = 12372
    private const val GL_RENDERER = 7937
    private const val GL_SHADING_LANGUAGE_VERSION = 35724
    private const val GL_VENDOR = 7936
    private const val GL_VERSION = 7938

    fun install(xposed: XposedInterface, context: HookContext) {
        val metricsFields = context.anyApplies(
            IdentityFields.DISPLAY_WIDTH,
            IdentityFields.DISPLAY_HEIGHT,
            IdentityFields.DISPLAY_DENSITY,
            IdentityFields.DISPLAY_INCHES,
        )

        if (metricsFields) {
            hookMetrics(xposed, context, "getMetrics")
            hookMetrics(xposed, context, "getRealMetrics")
            hookSize(xposed, context, "getSize")
            hookSize(xposed, context, "getRealSize")
            hookWindowMetrics(xposed, context)
        }

        if (context.anyApplies(
                IdentityFields.DISPLAY_WIDTH,
                IdentityFields.DISPLAY_HEIGHT,
                IdentityFields.DISPLAY_REFRESH,
            )
        ) {
            hookDisplayMode(xposed, context)
        }

        if (context.applies(IdentityFields.DISPLAY_REFRESH)) hookRefreshRate(xposed, context)
        if (context.applies(IdentityFields.DISPLAY_INCHES)) hookActivityResume(xposed, context)
        if (context.applies(IdentityFields.HARDWARE_GPU)) hookGles(xposed, context)
    }

    /**
     * Recomputes the cached `xdpi`/`ydpi` after an activity resumes.
     *
     * `Resources.getDisplayMetrics()` returns a cached object, so an app that read
     * it before the hooks were installed keeps stale values. `onResume` is the
     * earliest reliable point to fix it.
     */
    fun refreshCachedPhysicalDpi(app: Context, context: HookContext) {
        if (!context.applies(IdentityFields.DISPLAY_INCHES)) return

        val inches = screenInches(context) ?: return
        if (inches <= 0.0) return

        val cached = runCatching { app.resources.displayMetrics }.getOrNull() ?: return
        val pixels = hypot(cached.widthPixels.toDouble(), cached.heightPixels.toDouble())
        if (pixels <= 0.0) return

        val dpi = (pixels / inches).toFloat()
        if (dpi <= 0f || dpi == cached.xdpi) return

        record(context, "display", "resources.xdpi", cached.xdpi, dpi)
        cached.xdpi = dpi
        cached.ydpi = dpi
    }

    // -------------------------------------------------------------- metrics

    private fun hookMetrics(xposed: XposedInterface, context: HookContext, name: String) {
        hookLazy(
            xposed,
            context,
            "Display.$name",
            { Display::class.java.getMethod(name, DisplayMetrics::class.java) },
        ) { chain ->
            val result = chain.proceed()
            (chain.arg(0) as? DisplayMetrics)?.let { applyMetrics(it, context) }
            result
        }
    }

    private fun hookSize(xposed: XposedInterface, context: HookContext, name: String) {
        hookLazy(
            xposed,
            context,
            "Display.$name",
            { Display::class.java.getMethod(name, Point::class.java) },
        ) { chain ->
            val result = chain.proceed()
            val point = chain.arg(0) as? Point
            val display = context.artifact.identity.display

            if (point != null) {
                if (context.applies(IdentityFields.DISPLAY_WIDTH)) point.x = display.width
                if (context.applies(IdentityFields.DISPLAY_HEIGHT)) point.y = display.height
            }
            result
        }
    }

    private fun hookRefreshRate(xposed: XposedInterface, context: HookContext) {
        hookLazy(
            xposed,
            context,
            "Display.getRefreshRate",
            { Display::class.java.getMethod("getRefreshRate") },
        ) { chain ->
            val real = runCatching { chain.proceed() }.getOrNull() as? Float
            val fake = context.artifact.identity.display.refresh.toFloat()
            if (fake > 0f) {
                record(context, "display", "getRefreshRate", real, fake)
                fake
            } else {
                real
            }
        }
    }

    private fun hookDisplayMode(xposed: XposedInterface, context: HookContext) {
        val mode = runCatching { Class.forName("android.view.Display\$Mode") }.getOrNull() ?: return

        for (name in listOf("getPhysicalWidth", "getPhysicalHeight")) {
            val key = if (name == "getPhysicalWidth") {
                IdentityFields.DISPLAY_WIDTH
            } else {
                IdentityFields.DISPLAY_HEIGHT
            }
            if (!context.applies(key)) continue

            hookLazy(xposed, context, "Display.Mode.$name", { mode.getMethod(name) }) {
                val display = context.artifact.identity.display
                if (name == "getPhysicalWidth") display.width else display.height
            }
        }

        if (context.applies(IdentityFields.DISPLAY_REFRESH)) {
            hookLazy(
                xposed,
                context,
                "Display.Mode.getRefreshRate",
                { mode.getMethod("getRefreshRate") },
            ) {
                context.artifact.identity.display.refresh.toFloat()
            }
        }
    }

    private fun hookWindowMetrics(xposed: XposedInterface, context: HookContext) {
        if (!context.anyApplies(IdentityFields.DISPLAY_WIDTH, IdentityFields.DISPLAY_HEIGHT)) return

        val metricsClass = runCatching { Class.forName("android.view.WindowMetrics") }.getOrNull() ?: return

        hookLazy(
            xposed,
            context,
            "WindowMetrics.getBounds",
            { metricsClass.getMethod("getBounds") },
        ) { chain ->
            val real = runCatching { chain.proceed() }.getOrNull() as? Rect
            val display = context.artifact.identity.display

            val width = if (context.applies(IdentityFields.DISPLAY_WIDTH) && display.width > 0) {
                display.width
            } else {
                real?.width() ?: 0
            }
            val height = if (context.applies(IdentityFields.DISPLAY_HEIGHT) && display.height > 0) {
                display.height
            } else {
                real?.height() ?: 0
            }

            if (width <= 0 || height <= 0) real else Rect(0, 0, width, height)
        }
    }

    // ---------------------------------------------------------------- GL/EGL

    private fun hookGles(xposed: XposedInterface, context: HookContext) {
        val build = context.artifact.identity.build
        val hardware = context.artifact.identity.hardware
        val gl = RootHiding.glIdentity(build, hardware.gpuRenderer)

        for (className in listOf(
            "android.opengl.GLES10",
            "android.opengl.GLES20",
            "android.opengl.GLES30",
            "android.opengl.GLES31",
            "android.opengl.GLES32",
            "com.google.android.gles_jni.GLImpl",
        )) {
            val clazz = runCatching { Class.forName(className) }.getOrNull() ?: continue

            hookLazy(
                xposed,
                context,
                "$className.glGetString",
                { clazz.getMethod("glGetString", Int::class.javaPrimitiveType) },
            ) { chain ->
                val name = chain.intArg(0)
                val fake = when (name) {
                    GL_VENDOR -> gl.vendor
                    GL_RENDERER -> gl.renderer
                    GL_VERSION -> gl.version
                    GL_SHADING_LANGUAGE_VERSION -> gl.glsl
                    else -> null
                }

                if (fake.isNullOrBlank()) {
                    chain.proceed()
                } else {
                    val real = runCatching { chain.proceed() }.getOrNull() as? String
                    record(context, "gles", glField(name), real, fake)
                    fake
                }
            }
        }

        val eglDisplay = runCatching { Class.forName("android.opengl.EGLDisplay") }.getOrNull() ?: return

        for (className in listOf(
            "android.opengl.EGL14",
            "android.opengl.EGL10",
            "com.google.android.gles_jni.EGLImpl",
        )) {
            val clazz = runCatching { Class.forName(className) }.getOrNull() ?: continue

            hookLazy(
                xposed,
                context,
                "$className.eglQueryString",
                { clazz.getMethod("eglQueryString", eglDisplay, Int::class.javaPrimitiveType) },
            ) { chain ->
                val name = chain.intArg(1)
                val fake = when (name) {
                    EGL_VENDOR -> gl.eglVendor
                    EGL_VERSION -> gl.eglVersion
                    else -> null
                }

                if (fake.isNullOrBlank()) {
                    chain.proceed()
                } else {
                    val real = runCatching { chain.proceed() }.getOrNull() as? String
                    record(context, "gles", eglField(name), real, fake)
                    fake
                }
            }
        }
    }

    private fun glField(name: Int?): String = when (name) {
        GL_VENDOR -> "glGetString(GL_VENDOR)"
        GL_RENDERER -> "glGetString(GL_RENDERER)"
        GL_VERSION -> "glGetString(GL_VERSION)"
        GL_SHADING_LANGUAGE_VERSION -> "glGetString(GL_SHADING_LANGUAGE_VERSION)"
        else -> "glGetString($name)"
    }

    private fun eglField(name: Int?): String = when (name) {
        EGL_VENDOR -> "eglQueryString(EGL_VENDOR)"
        EGL_VERSION -> "eglQueryString(EGL_VERSION)"
        else -> "eglQueryString($name)"
    }

    // --------------------------------------------------------------- shared

    private fun hookActivityResume(xposed: XposedInterface, context: HookContext) {
        hookLazy(
            xposed,
            context,
            "Activity.onResume",
            { Activity::class.java.getDeclaredMethod("onResume") },
        ) { chain ->
            val result = chain.proceed()
            (chain.thisObject as? Context)?.let { refreshCachedPhysicalDpi(it, context) }
            result
        }
    }

    private fun applyMetrics(metrics: DisplayMetrics, context: HookContext) {
        val display = context.artifact.identity.display

        if (context.applies(IdentityFields.DISPLAY_WIDTH) && display.width > 0) {
            metrics.widthPixels = display.width
        }
        if (context.applies(IdentityFields.DISPLAY_HEIGHT) && display.height > 0) {
            metrics.heightPixels = display.height
        }
        if (context.applies(IdentityFields.DISPLAY_DENSITY) && display.density > 0) {
            metrics.densityDpi = display.density
            metrics.density = display.density / 160f
            metrics.scaledDensity = display.density / 160f
        }
        if (context.applies(IdentityFields.DISPLAY_INCHES)) {
            val inches = screenInches(context)
            if (inches != null && inches > 0.0) {
                val diagonal = hypot(metrics.widthPixels.toDouble(), metrics.heightPixels.toDouble())
                val dpi = (diagonal / inches).toFloat()
                metrics.xdpi = dpi
                metrics.ydpi = dpi
            }
        }
    }

    /**
     * The chassis's screen diagonal in inches.
     *
     * The profile's own value wins; the catalog entry is the fallback, because an
     * older profile may predate the field.
     */
    private fun screenInches(context: HookContext): Double? {
        val display: DisplayIdentity = context.artifact.identity.display
        display.screenInches.toDoubleOrNull()?.let { return it }

        val chassis = ChassisCatalog.find(context.artifact.chassis.id) ?: return null
        return chassis.display.screenInches.toDoubleOrNull()
    }
}
