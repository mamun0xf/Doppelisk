package com.doppelisk.hook.generic

import android.content.Context
import android.system.Os
import android.system.OsConstants
import com.doppelisk.hook.api.HookContext
import com.doppelisk.hook.api.PackChain
import com.doppelisk.hook.api.applies
import com.doppelisk.hook.engine.VirtualFiles
import com.doppelisk.hook.generic.HookSupport.hook
import com.doppelisk.hook.generic.HookSupport.hookLazy
import io.github.libxposed.api.XposedInterface
import java.io.File
import java.io.FileDescriptor
import java.io.FileFilter
import java.io.FileInputStream
import java.io.FilenameFilter
import java.util.Collections
import java.util.WeakHashMap

/**
 * Makes `/proc` and `/sys` reads return the profile's values.
 *
 * [VirtualFiles] decides *what* each file should contain; this class is the part
 * that intercepts the reads. There are three read paths on Android and all three
 * have to be covered:
 *
 *  - `libcore.io.IoBridge.open` — the native open behind `FileInputStream`;
 *  - `FileInputStream` constructors plus `read`/`skip`/`available` — used when
 *    the stream is already open;
 *  - directory listing — a faked CPU count is only believable if
 *    `/sys/devices/system/cpu` actually lists the extra `cpuN` directories.
 *
 * Where a file's whole content is known it is materialised into the app's cache
 * directory and the open is redirected there. Where only a few lines are faked,
 * the real file is read, the lines are substituted, and the result is returned
 * through a stream that is tracked per `FileInputStream` instance.
 */
object JavaFileHooks {

    /** Per-stream cursor over synthesised bytes. */
    private class StreamState(val bytes: ByteArray) {
        var position: Int = 0
        val remaining: Int get() = bytes.size - position
    }

    private val streams: MutableMap<FileInputStream, StreamState> =
        Collections.synchronizedMap(WeakHashMap())

    fun install(xposed: XposedInterface, context: HookContext) {
        val entries = VirtualFiles.forArtifact(context.artifact) { context.applies(it) }
        if (entries.isEmpty()) return

        hookIoBridge(xposed, context, entries)
        hookConstructors(xposed, context, entries)
        hookReads(xposed, context)
        hookCpuDirectory(xposed, context, cpuCount(entries))
    }

    // ------------------------------------------------------------ IoBridge

    private fun hookIoBridge(
        xposed: XposedInterface,
        context: HookContext,
        entries: Map<String, VirtualFiles.Entry>,
    ) {
        val ioBridge = runCatching { Class.forName("libcore.io.IoBridge") }.getOrNull() ?: return

        val opens = runCatching {
            ioBridge.declaredMethods.filter { method ->
                method.name == "open" &&
                    method.parameterTypes.firstOrNull() == String::class.java &&
                    method.returnType == FileDescriptor::class.java
            }
        }.getOrDefault(emptyList())

        for (method in opens) {
            hook(xposed, context, "IoBridge.open", method) { chain ->
                val path = chain.arg(0) as? String
                val entry = path?.let { entries[it] } ?: return@hook chain.proceed()

                val temp = materialize(path, entry) ?: return@hook chain.proceed()
                try {
                    val args = chain.args.copyOf()
                    args[0] = temp.absolutePath
                    chain.proceedWith(args)
                } finally {
                    temp.delete()
                }
            }
        }
    }

    /**
     * Writes [entry]'s content to a cache file and returns it.
     *
     * The cache directory is the only place the hooked app is guaranteed to be
     * able to write. The name is the content hash so two reads of the same file
     * share one temp file.
     */
    private fun materialize(path: String, entry: VirtualFiles.Entry): File? {
        val app = appContext() ?: return null

        val bytes = entry.content?.toByteArray(Charsets.UTF_8) ?: readReplaced(path, entry) ?: return null

        val file = File(app.cacheDir, "doppelisk-view-${bytes.contentHashCode()}")
        return runCatching {
            file.writeBytes(bytes)
            file
        }.getOrNull()
    }

    private fun appContext(): Context? = runCatching {
        Class.forName("android.app.AppGlobals")
            .getMethod("getInitialApplication")
            .invoke(null) as? Context
    }.getOrNull()

    // -------------------------------------------------------- constructors

    private fun hookConstructors(
        xposed: XposedInterface,
        context: HookContext,
        entries: Map<String, VirtualFiles.Entry>,
    ) {
        for (type in listOf(File::class.java, String::class.java)) {
            hookLazy(
                xposed,
                context,
                "FileInputStream(${type.simpleName})",
                { FileInputStream::class.java.getConstructor(type) },
            ) { chain ->
                val result = chain.proceed()

                val path = when (val arg = chain.arg(0)) {
                    is File -> arg.path
                    is String -> arg
                    else -> null
                }
                val entry = path?.let { entries[it] }
                val stream = chain.thisObject as? FileInputStream

                if (entry != null && stream != null) {
                    val bytes = entry.content?.toByteArray(Charsets.UTF_8) ?: readReplaced(path!!, entry)
                    if (bytes != null) streams[stream] = StreamState(bytes)
                }
                result
            }
        }
    }

    // --------------------------------------------------------------- reads

    private fun hookReads(xposed: XposedInterface, context: HookContext) {
        hookLazy(
            xposed,
            context,
            "FileInputStream.read()",
            { FileInputStream::class.java.getMethod("read") },
        ) { chain ->
            val state = stateOf(chain) ?: return@hookLazy chain.proceed()
            if (state.remaining <= 0) return@hookLazy -1
            state.bytes[state.position++].toInt() and 0xFF
        }

        hookLazy(
            xposed,
            context,
            "FileInputStream.read(byte[])",
            { FileInputStream::class.java.getMethod("read", ByteArray::class.java) },
        ) { chain ->
            val state = stateOf(chain) ?: return@hookLazy chain.proceed()
            val buffer = chain.arg(0) as ByteArray
            readInto(state, buffer, 0, buffer.size)
        }

        hookLazy(
            xposed,
            context,
            "FileInputStream.read(byte[],int,int)",
            {
                FileInputStream::class.java.getMethod(
                    "read",
                    ByteArray::class.java,
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                )
            },
        ) { chain ->
            val state = stateOf(chain) ?: return@hookLazy chain.proceed()
            readInto(
                state,
                chain.arg(0) as ByteArray,
                chain.arg(1) as Int,
                chain.arg(2) as Int,
            )
        }

        hookLazy(
            xposed,
            context,
            "FileInputStream.available",
            { FileInputStream::class.java.getMethod("available") },
        ) { chain ->
            stateOf(chain)?.remaining ?: chain.proceed()
        }

        hookLazy(
            xposed,
            context,
            "FileInputStream.skip",
            { FileInputStream::class.java.getMethod("skip", Long::class.javaPrimitiveType) },
        ) { chain ->
            val state = stateOf(chain) ?: return@hookLazy chain.proceed()
            val requested = (chain.arg(0) as Long).coerceAtLeast(0L)
            val actual = minOf(requested, state.remaining.toLong())
            state.position += actual.toInt()
            actual
        }

        hookLazy(
            xposed,
            context,
            "FileInputStream.close",
            { FileInputStream::class.java.getMethod("close") },
        ) { chain ->
            (chain.thisObject as? FileInputStream)?.let { streams.remove(it) }
            chain.proceed()
        }
    }

    private fun stateOf(chain: PackChain): StreamState? =
        (chain.thisObject as? FileInputStream)?.let { streams[it] }

    private fun readInto(state: StreamState, buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (state.remaining <= 0) return -1

        val count = minOf(length, state.remaining)
        state.bytes.copyInto(buffer, offset, state.position, state.position + count)
        state.position += count
        return count
    }

    // ------------------------------------------------------ CPU directory

    /** How many CPUs the profile has, inferred from the generated `cpuN` paths. */
    private fun cpuCount(entries: Map<String, VirtualFiles.Entry>): Int =
        entries.keys.mapNotNull { CPU_PATH.find(it)?.groupValues?.get(1)?.toIntOrNull() }
            .maxOrNull()
            ?.plus(1)
            ?: 0

    /**
     * Adds the profile's `cpuN` entries to `/sys/devices/system/cpu` listings.
     *
     * The virtual files for a CPU that does not physically exist cannot be opened
     * by the kernel, so the directory itself has to claim they exist.
     */
    private fun hookCpuDirectory(xposed: XposedInterface, context: HookContext, desired: Int) {
        if (desired <= 0) return

        val directory = "/sys/devices/system/cpu"

        hookLazy(
            xposed,
            context,
            "File.listFiles(FileFilter)",
            { File::class.java.getMethod("listFiles", FileFilter::class.java) },
        ) { chain ->
            val result = (chain.proceed() as? Array<*>)?.filterIsInstance<File>()?.toMutableList()
                ?: mutableListOf()
            appendCpuFiles(result, chain.thisObject as? File, directory, desired, chain.arg(0) as? FileFilter)
            result.toTypedArray()
        }

        hookLazy(
            xposed,
            context,
            "File.listFiles()",
            { File::class.java.getMethod("listFiles") },
        ) { chain ->
            val result = (chain.proceed() as? Array<*>)?.filterIsInstance<File>()?.toMutableList()
                ?: mutableListOf()
            appendCpuFiles(result, chain.thisObject as? File, directory, desired, null)
            result.toTypedArray()
        }

        hookLazy(
            xposed,
            context,
            "File.list(FilenameFilter)",
            { File::class.java.getMethod("list", FilenameFilter::class.java) },
        ) { chain ->
            val result = (chain.proceed() as? Array<*>)?.filterIsInstance<String>()?.toMutableList()
                ?: mutableListOf()
            appendCpuNames(result, chain.thisObject as? File, directory, desired, chain.arg(0) as? FilenameFilter)
            result.toTypedArray()
        }

        hookLazy(
            xposed,
            context,
            "File.list()",
            { File::class.java.getMethod("list") },
        ) { chain ->
            val result = (chain.proceed() as? Array<*>)?.filterIsInstance<String>()?.toMutableList()
                ?: mutableListOf()
            appendCpuNames(result, chain.thisObject as? File, directory, desired, null)
            result.toTypedArray()
        }
    }

    private fun appendCpuFiles(
        result: MutableList<File>,
        source: File?,
        directory: String,
        desired: Int,
        filter: FileFilter?,
    ) {
        if (source?.path != directory) return

        val present = result.mapTo(HashSet()) { it.name }
        for (index in 0 until desired) {
            val name = "cpu$index"
            if (name in present) continue
            val fake = File(directory, name)
            if (filter == null || filter.accept(fake)) result += fake
        }
    }

    private fun appendCpuNames(
        result: MutableList<String>,
        source: File?,
        directory: String,
        desired: Int,
        filter: FilenameFilter?,
    ) {
        if (source?.path != directory) return

        for (index in 0 until desired) {
            val name = "cpu$index"
            if (name in result) continue
            if (filter == null || filter.accept(File(directory), name)) result += name
        }
    }

    // ------------------------------------------------------------- replace

    /**
     * Reads the real [path] and substitutes `KEY=value` lines from [entry].
     *
     * This is the path for files like `battery/uevent` where most lines are
     * device-specific and only a couple reveal the truth. If the real file
     * cannot be opened, [VirtualFiles.Entry.fallback] is used instead.
     */
    private fun readReplaced(path: String, entry: VirtualFiles.Entry): ByteArray? {
        val replacements = entry.replacements
        if (replacements.isEmpty()) return null

        val fd = runCatching { Os.open(path, OsConstants.O_RDONLY, 0) }.getOrNull()
            ?: return entry.fallback?.toByteArray(Charsets.UTF_8)

        return try {
            val real = runCatching { FileInputStream(fd).use { it.readBytes() } }.getOrNull()
                ?: return entry.fallback?.toByteArray(Charsets.UTF_8)

            val text = String(real, Charsets.UTF_8)
            val replaced = text.lineSequence().joinToString("\n") { line ->
                val key = line.substringBefore('=', line)
                replacements[key]?.let { "$key=$it" } ?: line
            }
            replaced.toByteArray(Charsets.UTF_8)
        } finally {
            runCatching { Os.close(fd) }
        }
    }

    private val CPU_PATH = Regex("/sys/devices/system/cpu/cpu(\\d+)/")
}
