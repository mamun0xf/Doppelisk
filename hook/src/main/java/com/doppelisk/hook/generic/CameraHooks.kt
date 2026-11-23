package com.doppelisk.hook.generic

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.util.Range
import android.util.Size
import android.util.SizeF
import com.doppelisk.core.model.IdentityFields
import com.doppelisk.hook.api.HookContext
import com.doppelisk.hook.api.applies
import com.doppelisk.hook.generic.HookSupport.hookLazy
import com.doppelisk.hook.generic.HookSupport.record
import io.github.libxposed.api.XposedInterface
import java.util.Collections
import java.util.WeakHashMap

/**
 * Makes the camera subsystem match the chassis.
 *
 * Camera id lists, sensor resolutions and focal lengths are a fingerprint on
 * their own: a phone that reports four cameras through `Camera2` but whose
 * `Build.MODEL` is a two-camera device is inconsistent. The profile supplies the
 * id list, and this class derives a plausible characteristic set per id.
 *
 * A `CameraCharacteristics` object is opaque, so the id it belongs to is
 * remembered in a weak map when `getCameraCharacteristics` is called and looked
 * up again when `get(key)` is intercepted.
 */
object CameraHooks {

    private val cameraIds: MutableMap<CameraCharacteristics, String> =
        Collections.synchronizedMap(WeakHashMap())

    fun install(xposed: XposedInterface, context: HookContext) {
        if (!context.applies(IdentityFields.HARDWARE_CAMERAS)) return

        hookIdList(xposed, context)
        hookCharacteristics(xposed, context)
        hookGet(xposed, context)
    }

    private fun hookIdList(xposed: XposedInterface, context: HookContext) {
        val ids = context.artifact.identity.hardware.cameraIds
        if (ids.isEmpty()) return

        hookLazy(
            xposed,
            context,
            "CameraManager.getCameraIdList",
            { CameraManager::class.java.getMethod("getCameraIdList") },
        ) { chain ->
            val real = runCatching { chain.proceed() }.getOrNull() as? Array<*>
            context.boundary.record(
                "camera",
                "getCameraIdList",
                real?.joinToString(","),
                ids.joinToString(","),
            )
            ids.toTypedArray()
        }
    }

    private fun hookCharacteristics(xposed: XposedInterface, context: HookContext) {
        val targetIds = context.artifact.identity.hardware.cameraIds

        hookLazy(
            xposed,
            context,
            "CameraManager.getCameraCharacteristics",
            { CameraManager::class.java.getMethod("getCameraCharacteristics", String::class.java) },
        ) { chain ->
            val id = chain.arg(0) as? String
            if (targetIds.isNotEmpty() && id != null && id !in targetIds) {
                throw IllegalArgumentException("Unknown camera id: $id")
            }

            val result = chain.proceed()
            if (result is CameraCharacteristics && id != null) {
                cameraIds[result] = id
            }
            result
        }
    }

    private fun hookGet(xposed: XposedInterface, context: HookContext) {
        hookLazy(
            xposed,
            context,
            "CameraCharacteristics.get",
            { CameraCharacteristics::class.java.getMethod("get", CameraCharacteristics.Key::class.java) },
        ) { chain ->
            val key = chain.arg(0) as? CameraCharacteristics.Key<*>
            val keyName = runCatching { key?.name }.getOrNull()

            val characteristics = chain.thisObject as? CameraCharacteristics
            val id = characteristics?.let { cameraIds[it] }
            val spec = id?.let { specFor(context, it) }

            val fake: Any? = when (keyName) {
                "android.sensor.orientation" -> spec?.orientation
                "android.sensor.info.activeArraySize" -> spec?.pixels?.let {
                    android.graphics.Rect(0, 0, it.width, it.height)
                }
                "android.sensor.info.pixelArraySize" -> spec?.pixels
                "android.sensor.info.sensitivityRange" -> Range(100, 6400)
                "android.info.supportedHardwareLevel" -> spec?.level
                "android.sensor.info.physicalSize" -> SizeF(6.4f, 4.8f)
                "android.lens.info.availableFocalLengths" -> spec?.focal
                "android.lens.facing" -> spec?.facing
                else -> null
            } ?: return@hookLazy chain.proceed()

            val real = runCatching { chain.proceed() }.getOrNull()
            record(context, "camera", "$id:$keyName", real, fake)
            fake
        }
    }

    /**
     * A plausible characteristic set for camera [id] on this chassis.
     *
     * The index of the id in the profile's list decides which camera it is: the
     * first entry is the wide main camera, the second the front camera, and any
     * further entries are the telephoto/ultrawide lenses. The two named models get
     * their real resolutions; everything else falls back to a generic layout.
     */
    private fun specFor(context: HookContext, id: String): Spec? {
        val model = context.artifact.identity.build.model
        val ids = context.artifact.identity.hardware.cameraIds

        val index = ids.indexOf(id).takeIf { it >= 0 } ?: id.toIntOrNull() ?: return null

        return when (model) {
            "SM-S921B" -> when (index) {
                0 -> Spec(Size(16320, 12288), 0, 3, 90, floatArrayOf(1.1f, 2.2f, 3.4f))
                1 -> Spec(Size(4032, 3024), 0, 3, 90, floatArrayOf(0.6f, 1.4f))
                2 -> Spec(Size(3648, 2736), 0, 3, 90, floatArrayOf(2.2f, 3.4f))
                3 -> Spec(Size(4032, 3024), 1, 3, 270, floatArrayOf(1.1f))
                else -> null
            }

            "Pixel 8 Pro" -> when (index) {
                0 -> Spec(Size(8160, 6144), 0, 3, 90, floatArrayOf(1.4f, 2.8f))
                1 -> Spec(Size(8064, 6048), 0, 3, 90, floatArrayOf(0.6f, 1.4f))
                2 -> Spec(Size(8064, 6048), 0, 3, 90, floatArrayOf(2.8f, 5.0f))
                3 -> Spec(Size(4000, 3000), 1, 3, 270, floatArrayOf(1.4f))
                else -> null
            }

            else -> when (index) {
                0 -> Spec(Size(4032, 3024), 0, 2, 90, floatArrayOf(1.4f, 2.8f))
                1 -> Spec(Size(4032, 3024), 1, 2, 270, floatArrayOf(1.4f))
                else -> null
            }
        }
    }

    /** One camera's characteristic values. */
    private data class Spec(
        val pixels: Size,
        val facing: Int,
        val level: Int,
        val orientation: Int,
        val focal: FloatArray,
    )
}
