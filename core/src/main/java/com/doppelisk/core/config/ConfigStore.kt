package com.doppelisk.core.config

import com.doppelisk.core.model.DoppeliskIndex
import com.doppelisk.core.model.IndexEntry
import com.doppelisk.core.model.JsonFormats
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream

/**
 * Owner of the on-disk configuration tree.
 *
 * Only the daemon (root) writes through this class; the manager talks to it
 * over the mailbox. That is why there is no locking beyond `synchronized` on
 * the mutating methods: there is exactly one writer process.
 *
 * Every write goes through [atomicWrite] so a killed process can never leave a
 * half-written artifact behind.
 *
 * @param root directory holding `index.json`, `doppelisks/` and `audit/`.
 */
class ConfigStore(private val root: String = ConfigPaths.ROOT) {

    private val json: Json = JsonFormats.profile

    private val rootDir = File(root)
    private val doppelisksDir = File(rootDir, "doppelisks")
    private val auditDir = File(rootDir, "audit")
    private val indexFile = File(rootDir, "index.json")

    /** Creates the directory tree and an empty index if they are missing. */
    fun ensureLayout() {
        rootDir.mkdirs()
        doppelisksDir.mkdirs()
        auditDir.mkdirs()
        if (!indexFile.exists()) writeIndex(DoppeliskIndex())
    }

    /**
     * Reads and migrates the index. A missing, unreadable or corrupt index
     * yields an empty one rather than failing: a broken index must never stop
     * the daemon from serving the artifacts that are still on disk.
     */
    fun readIndex(): DoppeliskIndex {
        if (!indexFile.exists()) return DoppeliskIndex()
        val decoded = runCatching {
            json.decodeFromString(DoppeliskIndex.serializer(), indexFile.readText())
        }.getOrNull() ?: return DoppeliskIndex()
        return ConfigMigrations.migrateIndex(decoded)
    }

    /** Writes [index] after migrating it to the current schema. */
    fun writeIndex(index: DoppeliskIndex) {
        val migrated = ConfigMigrations.migrateIndex(index)
        atomicWrite(indexFile, json.encodeToString(DoppeliskIndex.serializer(), migrated))
    }

    /** Absolute path of the artifact with [id]. */
    fun artifactPath(id: String): String = File(doppelisksDir, "${ConfigPaths.sanitize(id)}.json").path

    /** Absolute path of the audit report for [packageName]. */
    fun auditPath(packageName: String): String = File(auditDir, "${ConfigPaths.sanitize(packageName)}.json").path

    /**
     * Returns the raw, migrated artifact JSON for [id], or null when it does
     * not exist.
     *
     * The JSON is returned as text rather than a decoded object so that callers
     * can forward it verbatim over IPC, preserving fields this build does not
     * know about.
     */
    fun readArtifact(id: String): String? {
        val file = File(artifactPath(id))
        if (!file.exists()) return null
        return ConfigMigrations.migrateArtifact(file.readText())
    }

    /** Atomically replaces the artifact with [id]. */
    fun writeArtifact(id: String, artifactJson: String) {
        atomicWrite(File(artifactPath(id)), artifactJson)
    }

    /**
     * Deletes the artifact with [id] and repairs the index.
     *
     * If the artifact was the assignment for its package, the package is
     * re-pointed at its implicit default profile so it keeps working.
     *
     * @return true when the file is gone afterwards.
     */
    @Synchronized
    fun deleteArtifact(id: String): Boolean {
        val file = File(artifactPath(id))
        if (file.exists() && !file.delete()) return false

        val index = readIndex()
        val entry = index.doppelisks[id] ?: return true
        val entries = index.doppelisks.toMutableMap().apply { remove(id) }
        val assignments = index.assignments.toMutableMap()
        if (assignments[entry.packageName] == id) {
            assignments[entry.packageName] = ConfigPaths.defaultProfileId(entry.packageName)
        }
        writeIndex(index.copy(assignments = assignments, doppelisks = entries))
        return true
    }

    /** The artifact assigned to [packageName], or null when it has none. */
    fun findArtifactForPackage(packageName: String): String? {
        val id = readIndex().assignments[packageName] ?: return null
        return readArtifact(id)
    }

    /** Every artifact bound to [packageName], ordered by artifact id. */
    fun artifactsForPackage(packageName: String): List<String> =
        readIndex().doppelisks.values
            .filter { it.packageName == packageName }
            .sortedBy(IndexEntry::id)
            .mapNotNull { readArtifact(it.id) }

    /** Replaces the audit report for [packageName]. */
    fun writeAudit(packageName: String, auditJson: String) {
        atomicWrite(File(auditPath(packageName)), auditJson)
    }

    /** The raw audit report for [packageName], or null when absent. */
    fun readAudit(packageName: String): String? {
        val file = File(auditPath(packageName))
        return if (file.exists()) file.readText() else null
    }

    /**
     * Writes [content] to a sibling `.tmp` file, fsyncs it, then renames it
     * over [target].
     *
     * The fsync matters: the target lives on a filesystem that is still being
     * written by Android at the same time, and a rename without a flush can
     * leave a zero-length file after a hard reboot.
     */
    private fun atomicWrite(target: File, content: String) {
        target.parentFile?.mkdirs()
        val tmp = File(target.path + ".tmp")
        FileOutputStream(tmp).use { out ->
            out.write(content.toByteArray(Charsets.UTF_8))
            out.fd.sync()
        }
        if (!tmp.renameTo(target)) {
            target.writeText(content)
            tmp.delete()
        }
    }
}
