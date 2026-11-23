package com.doppelisk.hook.generic

import com.doppelisk.hook.api.HookContext
import com.doppelisk.hook.generic.HookSupport.hook
import io.github.libxposed.api.XposedInterface
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * Answers `getprop <name>` from the spoofed property table.
 *
 * `SystemProperties` is hooked, but a shell command does not go through it: the
 * child process reads the kernel's property area, which the hook cannot reach.
 * The only place to intervene is at process creation, so `Runtime.exec` and
 * `ProcessBuilder.start` are both intercepted and a fake [Process] is returned
 * with the spoofed value on stdout.
 *
 * This is a real gap-closer rather than a nicety: `getprop ro.product.model` is
 * one of the cheapest checks an SDK can run.
 */
object ShellHooks {

    fun install(xposed: XposedInterface, context: HookContext, props: Map<String, String>) {
        if (props.isEmpty()) return

        hook(
            xposed,
            context,
            "Runtime.exec(String)",
            runCatching { Runtime::class.java.getMethod("exec", String::class.java) }.getOrNull(),
        ) { chain ->
            val command = (chain.arg(0) as? String)?.trim().orEmpty()
            fakeFor(getpropKey(command), props, context) ?: chain.proceed()
        }

        hook(
            xposed,
            context,
            "Runtime.exec(String[])",
            runCatching { Runtime::class.java.getMethod("exec", Array<String>::class.java) }.getOrNull(),
        ) { chain ->
            val command = chain.arg(0) as? Array<*>
            fakeFor(getpropKey(command), props, context) ?: chain.proceed()
        }

        hook(
            xposed,
            context,
            "ProcessBuilder.start",
            runCatching { ProcessBuilder::class.java.getMethod("start") }.getOrNull(),
        ) { chain ->
            val builder = chain.thisObject as? ProcessBuilder
            val key = builder?.command()?.takeIf { it.isNotEmpty() }?.let { command ->
                if (command[0].substringAfterLast('/') == "getprop") command.getOrNull(1) else null
            }
            fakeFor(key, props, context) ?: chain.proceed()
        }
    }

    private fun fakeFor(key: String?, props: Map<String, String>, context: HookContext): Process? {
        if (key == null) return null
        val value = props[key] ?: return null
        context.boundary.record("properties", "getprop $key", null, value)
        return FakeProcess(value)
    }

    /** Parses `getprop ro.x` / `/system/bin/getprop ro.x` into `ro.x`. */
    private fun getpropKey(command: String): String? {
        val parts = command.trim().split(Regex("\\s+"), limit = 3)
        if (parts.size != 2) return null
        if (parts[0].substringAfterLast('/') != "getprop") return null
        return parts[1].ifBlank { null }
    }

    private fun getpropKey(command: Array<*>?): String? {
        if (command == null || command.size != 2) return null
        val binary = command[0] as? String ?: return null
        val name = command[1] as? String ?: return null
        if (binary.substringAfterLast('/') != "getprop") return null
        return name.ifBlank { null }
    }

    /** A process that has already exited and printed [value]. */
    private class FakeProcess(value: String) : Process() {

        private val stdout: InputStream = ByteArrayInputStream("$value\n".toByteArray(Charsets.UTF_8))
        private val stderr: InputStream = ByteArrayInputStream(ByteArray(0))
        private val stdin: OutputStream = ByteArrayOutputStream()

        override fun getOutputStream(): OutputStream = stdin

        override fun getInputStream(): InputStream = stdout

        override fun getErrorStream(): InputStream = stderr

        override fun waitFor(): Int = 0

        override fun exitValue(): Int = 0

        override fun destroy() = Unit
    }
}
