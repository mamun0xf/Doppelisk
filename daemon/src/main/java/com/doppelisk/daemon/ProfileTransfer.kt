package com.doppelisk.daemon

import com.doppelisk.core.config.ConfigPaths
import com.doppelisk.core.model.DoppeliskArtifact
import com.doppelisk.core.model.JsonFormats
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Reads and writes the two portable shapes of a profile.
 *
 * A plain export is the artifact document alone, so it can be edited by hand
 * like the files under `<root>/doppelisks`. A zip export adds the container's
 * prepared data as `container/…`, which is the only directory a profile needs
 * beyond its JSON to come back exactly as it was.
 *
 * Everything streams: container data can be gigabytes, and the daemon has to
 * stay comfortable on a phone with little free RAM. Archive entry names are
 * treated as hostile — they come from a file the user picked, not from us.
 */
class ProfileTransfer(private val json: Json = JsonFormats.profile) {

    /** A decoded staging file. [zip] is true when it carried container data. */
    data class Import(val artifact: DoppeliskArtifact, val zip: Boolean)

    /** Writes [profileJson] alone, as a `.json` export. */
    fun writeJson(target: File, profileJson: String) {
        FileOutputStream(target).use { out ->
            out.write(profileJson.toByteArray(Charsets.UTF_8))
            out.fd.sync()
        }
    }

    /** Writes [profileJson], a manifest and the profile's container tree. */
    fun writeZip(target: File, profileJson: String, artifact: DoppeliskArtifact) {
        ZipOutputStream(BufferedOutputStream(FileOutputStream(target), BUFFER_SIZE)).use { zip ->
            zip.entry(PROFILE_ENTRY) { zip.write(profileJson.toByteArray(Charsets.UTF_8)) }
            zip.entry(MANIFEST_ENTRY) { zip.write(manifest(artifact).toByteArray(Charsets.UTF_8)) }

            val container = File(ConfigPaths.containerDir(artifact.id))
            writeTree(zip, container, container)
        }
    }

    /**
     * Decodes the profile in [source], whether it is a bare document or a zip.
     *
     * A zip is recognised by its magic bytes rather than its name: the manager
     * lets the user pick any file, and many file providers hand out names
     * without an extension.
     */
    fun read(source: File): Import {
        val zip = isZip(source)
        val profileJson = if (zip) readZipProfile(source) else readJson(source)
        val artifact = try {
            json.decodeFromString(DoppeliskArtifact.serializer(), profileJson)
        } catch (t: Throwable) {
            throw IllegalArgumentException("not a Doppelisk profile: ${t.message}", t)
        }
        return Import(artifact, zip)
    }

    /**
     * Restores the `container/` tree of a zip under [destination].
     *
     * The destination is emptied first because it is keyed by the id the profile
     * was given on import, and a half-restored tree is worse than none. Every
     * entry is resolved and checked against the canonical container root, so a
     * crafted archive cannot write outside it.
     */
    fun extractContainer(source: File, destination: File) {
        val root = destination.canonicalFile
        root.deleteRecursively()
        if (!root.mkdirs() && !root.isDirectory) error("cannot create the container directory")

        val rootPrefix = root.path + File.separator
        ZipInputStream(BufferedInputStream(FileInputStream(source), BUFFER_SIZE)).use { zip ->
            var entries = 0
            var total = 0L

            while (true) {
                val entry = zip.nextEntry ?: break
                if (++entries > MAX_ENTRIES) error("archive has too many entries")
                if (entry.isDirectory) continue

                val name = entry.name.replace('\\', '/')
                if (!name.startsWith(CONTAINER_PREFIX)) continue

                val relative = name.removePrefix(CONTAINER_PREFIX)
                val parts = relative.split('/')
                if (relative.isEmpty() || parts.any { it.isEmpty() || it == "." || it == ".." }) {
                    error("archive entry has an unsafe name: ${entry.name}")
                }

                val target = File(root, relative).canonicalFile
                if (!target.path.startsWith(rootPrefix)) {
                    error("archive entry escapes the container: ${entry.name}")
                }
                if (entry.size > MAX_ENTRY_BYTES) {
                    error("archive entry is too large: ${entry.name}")
                }

                target.parentFile?.mkdirs()
                FileOutputStream(target).use { out ->
                    var written = 0L
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (true) {
                        val read = zip.read(buffer)
                        if (read < 0) break
                        written += read
                        total += read
                        if (written > MAX_ENTRY_BYTES) error("archive entry is too large: ${entry.name}")
                        if (total > MAX_TOTAL_BYTES) error("archive is too large")
                        out.write(buffer, 0, read)
                    }
                }
            }
        }
    }

    /** The manifest that tells a human (and a future build) what the zip is. */
    private fun manifest(artifact: DoppeliskArtifact): String = json.encodeToString(
        JsonObject.serializer(),
        buildJsonObject {
            put("format", FORMAT)
            put("version", FORMAT_VERSION)
            put("id", artifact.id)
            put("package", artifact.target.packageName)
        },
    )

    private fun writeTree(zip: ZipOutputStream, base: File, dir: File) {
        val children = dir.listFiles() ?: return
        for (child in children.sortedBy(File::getName)) {
            // A link would copy whatever it points at, which may be the whole
            // filesystem: the archive follows the real tree only.
            if (Files.isSymbolicLink(child.toPath())) continue

            if (child.isDirectory) {
                writeTree(zip, base, child)
            } else if (child.isFile) {
                val relative = child.relativeTo(base).invariantSeparatorsPath
                zip.entry(CONTAINER_PREFIX + relative) {
                    FileInputStream(child).use { input -> input.copyTo(zip, BUFFER_SIZE) }
                }
            }
        }
    }

    private inline fun ZipOutputStream.entry(name: String, write: () -> Unit) {
        putNextEntry(ZipEntry(name))
        write()
        closeEntry()
    }

    private fun isZip(source: File): Boolean = runCatching {
        FileInputStream(source).use { input ->
            val head = ByteArray(2)
            input.read(head) == 2 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte()
        }
    }.getOrDefault(false)

    private fun readJson(source: File): String {
        if (source.length() > MAX_PROFILE_BYTES) error("profile is larger than $MAX_PROFILE_BYTES bytes")
        return source.readText()
    }

    private fun readZipProfile(source: File): String {
        var found: String? = null
        ZipInputStream(BufferedInputStream(FileInputStream(source), BUFFER_SIZE)).use { zip ->
            var entries = 0
            while (found == null) {
                val entry = zip.nextEntry ?: break
                if (++entries > MAX_ENTRIES) error("archive has too many entries")
                if (entry.name.replace('\\', '/').trimStart('/') == PROFILE_ENTRY) {
                    found = readEntryText(zip)
                } else {
                    zip.closeEntry()
                }
            }
        }
        return found ?: error("archive has no $PROFILE_ENTRY")
    }

    private fun readEntryText(zip: ZipInputStream): String {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(BUFFER_SIZE)
        while (true) {
            val read = zip.read(buffer)
            if (read < 0) break
            out.write(buffer, 0, read)
            if (out.size() > MAX_PROFILE_BYTES) error("profile is larger than $MAX_PROFILE_BYTES bytes")
        }
        return String(out.toByteArray(), Charsets.UTF_8)
    }

    private companion object {
        const val PROFILE_ENTRY = "profile.json"
        const val MANIFEST_ENTRY = "manifest.json"
        const val CONTAINER_PREFIX = "container/"
        const val FORMAT = "doppelisk-export"
        const val FORMAT_VERSION = 1
        const val BUFFER_SIZE = 64 * 1024

        /** Guards against a zip bomb: a phone profile never needs this many files. */
        const val MAX_ENTRIES = 100_000

        /** A profile document is a few kilobytes; anything near this is hostile. */
        const val MAX_PROFILE_BYTES = 8L * 1024 * 1024

        const val MAX_ENTRY_BYTES = 1024L * 1024 * 1024
        const val MAX_TOTAL_BYTES = 8L * 1024 * 1024 * 1024
    }
}
