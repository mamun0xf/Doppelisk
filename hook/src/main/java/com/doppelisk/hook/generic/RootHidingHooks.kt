package com.doppelisk.hook.generic

import android.app.ActivityManager
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.system.Os
import android.system.OsConstants
import com.doppelisk.hook.api.HookContext
import com.doppelisk.hook.api.PackChain
import com.doppelisk.hook.engine.RootHiding
import com.doppelisk.hook.generic.HookSupport.hook
import com.doppelisk.hook.generic.HookSupport.hookLazy
import io.github.libxposed.api.XposedInterface
import java.io.File
import java.io.FileFilter
import java.io.FilenameFilter
import java.io.IOException
import java.lang.reflect.Array as ReflectArray
import java.lang.reflect.Executable
import java.lang.reflect.Method

/**
 * Hides the root stack from the hooked app.
 *
 * The app is running inside a rooted phone with KernelSU, LSPosed and this module
 * installed, and every one of those leaves traces an anti-tamper SDK looks for:
 * manager packages in `PackageManager`, directories like `/data/adb`, processes
 * named `magiskd`, and `su` on `$PATH`. Hiding them is a prerequisite for the
 * identity spoofing to be believable, which is why this is a generic hook rather
 * than a pack.
 *
 * Everything is deny-listed through [RootHiding]: paths report as missing,
 * packages as not installed, and root commands as `ENOENT`.
 */
object RootHidingHooks {

    private val BOOLEAN_FILE_METHODS = listOf("exists", "isFile", "isDirectory", "canRead", "canExecute")
    private val NUMERIC_FILE_METHODS = listOf("length", "lastModified")

    private val PACKAGE_QUERIES = setOf(
        "getPackageInfo",
        "getPackageInfoAsUser",
        "getApplicationInfo",
        "getApplicationInfoAsUser",
        "getInstalledPackages",
        "getInstalledPackagesAsUser",
        "getInstalledApplications",
        "getInstalledApplicationsAsUser",
        "getPackageUid",
        "getPackageUidAsUser",
    )

    fun install(xposed: XposedInterface, context: HookContext) {
        hookFile(xposed, context)
        hookOs(xposed, context)
        hookPackageManager(xposed, context)
        hookRuntime(xposed, context)
        hookActivityManager(xposed, context)
    }

    // ---------------------------------------------------------------- files

    private fun hookFile(xposed: XposedInterface, context: HookContext) {
        for (name in BOOLEAN_FILE_METHODS) {
            hook(
                xposed,
                context,
                "File.$name",
                runCatching { File::class.java.getMethod(name) }.getOrNull(),
            ) { chain ->
                val file = chain.thisObject as? File
                if (file != null && RootHiding.isHiddenPath(file.path)) {
                    context.boundary.record("root", "File.$name", file.path, "false")
                    false
                } else {
                    chain.proceed()
                }
            }
        }

        for (name in NUMERIC_FILE_METHODS) {
            hook(
                xposed,
                context,
                "File.$name",
                runCatching { File::class.java.getMethod(name) }.getOrNull(),
            ) { chain ->
                val file = chain.thisObject as? File
                if (file != null && RootHiding.isHiddenPath(file.path)) {
                    context.boundary.record("root", "File.$name", file.path, "0")
                    0L
                } else {
                    chain.proceed()
                }
            }
        }

        val listMethods: List<Executable?> = listOf(
            runCatching { File::class.java.getMethod("list") }.getOrNull(),
            runCatching { File::class.java.getMethod("list", FilenameFilter::class.java) }.getOrNull(),
            runCatching { File::class.java.getMethod("listFiles") }.getOrNull(),
            runCatching { File::class.java.getMethod("listFiles", FilenameFilter::class.java) }.getOrNull(),
            runCatching { File::class.java.getMethod("listFiles", FileFilter::class.java) }.getOrNull(),
        )

        for (method in listMethods) {
            val name = method?.name ?: continue
            hook(xposed, context, "File.$name", method) { chain ->
                filterListing(chain, context, name)
            }
        }
    }

    /**
     * Drops hidden entries from a `list`/`listFiles` result.
     *
     * The result may be `File[]` or `String[]`; entries of a `String[]` are
     * resolved against the receiver to decide whether they are hidden.
     */
    private fun filterListing(chain: PackChain, context: HookContext, name: String): Any? {
        val file = chain.thisObject as? File
        if (file != null && RootHiding.isHiddenPath(file.path)) {
            context.boundary.record("root", "File.$name", file.path, null)
            return null
        }

        val result = chain.proceed()
        val entries = result as? kotlin.Array<*> ?: return result

        val filtered = entries.filterNot { entry ->
            when (entry) {
                is File -> RootHiding.isHiddenPath(entry.path)
                is String -> file != null && RootHiding.isHiddenPath(File(file, entry).path)
                else -> false
            }
        }

        if (filtered.size == entries.size) return result

        val component = entries.javaClass.componentType ?: Any::class.java
        val typed = ReflectArray.newInstance(component, filtered.size)
        filtered.forEachIndexed { index, item -> ReflectArray.set(typed, index, item) }
        return typed
    }

    // ------------------------------------------------------------------ os

    private fun hookOs(xposed: XposedInterface, context: HookContext) {
        val methods: List<Executable?> = listOf(
            runCatching { Os::class.java.getMethod("stat", String::class.java) }.getOrNull(),
            runCatching { Os::class.java.getMethod("lstat", String::class.java) }.getOrNull(),
            runCatching {
                Os::class.java.getMethod("access", String::class.java, Int::class.javaPrimitiveType)
            }.getOrNull(),
            runCatching { Os::class.java.getMethod("readlink", String::class.java) }.getOrNull(),
            runCatching {
                Os::class.java.getMethod(
                    "open",
                    String::class.java,
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                )
            }.getOrNull(),
        )

        for (method in methods) {
            val name = method?.name ?: continue
            hook(xposed, context, "Os.$name", method) { chain ->
                val path = chain.arg(0) as? String
                if (RootHiding.isHiddenPath(path)) {
                    context.boundary.record("root", "Os.$name", path, "ENOENT")
                    throw errnoException(name, OsConstants.ENOENT)
                }
                chain.proceed()
            }
        }
    }

    // ------------------------------------------------------- package manager

    private fun hookPackageManager(xposed: XposedInterface, context: HookContext) {
        val clazz = runCatching { Class.forName("android.app.ApplicationPackageManager") }.getOrNull() ?: return

        val methods = runCatching {
            clazz.declaredMethods.filter { it.name in PACKAGE_QUERIES }
        }.getOrDefault(emptyList())

        for (method in methods) {
            hook(xposed, context, "pm.${method.name}", method) { chain ->
                filterPackageQuery(chain, context, method)
            }
        }
    }

    private fun filterPackageQuery(chain: PackChain, context: HookContext, method: Method): Any? {
        val requested = hiddenPackageArg(chain.args)
        if (requested != null) {
            context.boundary.record("root", "pm.${method.name}", requested, "NameNotFoundException")
            throw PackageManager.NameNotFoundException(requested)
        }

        val result = chain.proceed()

        when (result) {
            is PackageInfo -> if (RootHiding.isHiddenPackage(result.packageName)) {
                context.boundary.record("root", "pm.${method.name}", result.packageName, "NameNotFoundException")
                throw PackageManager.NameNotFoundException(result.packageName)
            }

            is ApplicationInfo -> if (RootHiding.isHiddenPackage(result.packageName)) {
                context.boundary.record("root", "pm.${method.name}", result.packageName, "NameNotFoundException")
                throw PackageManager.NameNotFoundException(result.packageName)
            }

            is List<*> -> return result.filterNot { item ->
                val name = packageNameOf(item)
                name != null && RootHiding.isHiddenPackage(name)
            }
        }

        return result
    }

    /** The first package-shaped argument that names a hidden package. */
    private fun hiddenPackageArg(args: Array<Any?>): String? = args
        .filterIsInstance<String>()
        .firstOrNull { it.contains('.') && RootHiding.isHiddenPackage(it) }

    private fun packageNameOf(item: Any?): String? = when (item) {
        is PackageInfo -> item.packageName
        is ApplicationInfo -> item.packageName
        else -> null
    }

    // -------------------------------------------------------------- runtime

    private fun hookRuntime(xposed: XposedInterface, context: HookContext) {
        val execs = runCatching { Runtime::class.java.declaredMethods.filter { it.name == "exec" } }
            .getOrDefault(emptyList())

        for (method in execs) {
            hook(xposed, context, "Runtime.exec", method) { chain ->
                val command = commandOf(chain.arg(0))
                if (command != null && RootHiding.isRootCommand(command)) {
                    context.boundary.record("root", "Runtime.exec", command, "IOException")
                    throw IOException("Cannot run program \"$command\": error=2, No such file or directory")
                }
                chain.proceed()
            }
        }

        hookLazy(
            xposed,
            context,
            "ProcessBuilder.start",
            { ProcessBuilder::class.java.getMethod("start") },
        ) { chain ->
            val command = runCatching { (chain.thisObject as? ProcessBuilder)?.command()?.joinToString(" ") }
                .getOrNull()

            if (command != null && RootHiding.isRootCommand(command)) {
                context.boundary.record("root", "ProcessBuilder.start", command, "IOException")
                throw IOException("Cannot run program \"$command\": error=2, No such file or directory")
            }
            chain.proceed()
        }
    }

    private fun commandOf(first: Any?): String? = when (first) {
        is String -> first
        is kotlin.Array<*> -> first.joinToString(" ") { it?.toString() ?: "" }
        else -> null
    }

    /**
     * Builds an `ErrnoException`, which is `@hide`.
     *
     * `Os` callers catch it and read `errno`, so a `FileNotFoundException` would
     * not do: it would change control flow in code that distinguishes ENOENT from
     * other errors.
     */
    private fun errnoException(function: String, errno: Int): Throwable = runCatching {
        Class.forName("android.system.ErrnoException")
            .getConstructor(String::class.java, Int::class.javaPrimitiveType)
            .newInstance(function, errno) as Throwable
    }.getOrElse { IOException("$function: errno=$errno") }

    // ------------------------------------------------------- activity manager

    private fun hookActivityManager(xposed: XposedInterface, context: HookContext) {
        hookLazy(
            xposed,
            context,
            "ActivityManager.getRunningAppProcesses",
            { ActivityManager::class.java.getMethod("getRunningAppProcesses") },
        ) { chain ->
            val result = chain.proceed() as? List<*> ?: return@hookLazy chain.proceed()
            result.filterNot { item ->
                item is ActivityManager.RunningAppProcessInfo && RootHiding.isHiddenPackage(item.processName)
            }
        }

        hookLazy(
            xposed,
            context,
            "ActivityManager.getRunningServices",
            { ActivityManager::class.java.getMethod("getRunningServices", Int::class.javaPrimitiveType) },
        ) { chain ->
            val result = chain.proceed() as? List<*> ?: return@hookLazy chain.proceed()
            result.filterNot { item ->
                item is ActivityManager.RunningServiceInfo && RootHiding.isHiddenPackage(item.process)
            }
        }
    }
}
