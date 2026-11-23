package com.doppelisk.core.container

import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Persists [ContainerJournal]s under `<root>/containers/journals`.
 *
 * One file per (artifact, package) pair, rewritten atomically as the operation
 * progresses. The store is intentionally dumb: it never decides anything, it
 * only remembers, so a crash at any point leaves a readable record.
 */
class JournalStore(private val root: String = ContainerPaths.DEFAULT_ROOT) {

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    val directory: File = File(ContainerPaths.journalDir(root))

    /** Path of the journal for one pair. */
    fun pathFor(doppeliskId: String, packageName: String): File =
        File(ContainerPaths.journalPath(doppeliskId, packageName, root))

    /** Writes [journal] atomically and returns the file it landed in. */
    fun save(journal: ContainerJournal): File {
        directory.mkdirs()
        val target = pathFor(journal.plan.doppeliskId, journal.plan.packageName)
        val temp = File(target.parentFile, target.name + ".tmp")
        val bytes = json.encodeToString(ContainerJournal.serializer(), journal).toByteArray(Charsets.UTF_8)
        FileOutputStream(temp).use { out ->
            out.write(bytes)
            out.fd.sync()
        }
        try {
            Files.move(
                temp.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        return target
    }

    /** Reads one journal file, or null when it is absent or unreadable. */
    fun load(file: File): ContainerJournal? = try {
        if (!file.isFile) {
            null
        } else {
            json.decodeFromString(ContainerJournal.serializer(), file.readText())
        }
    } catch (_: java.io.IOException) {
        null
    } catch (_: RuntimeException) {
        null
    }

    /** Reads the journal for one pair, or null when there is none. */
    fun load(doppeliskId: String, packageName: String): ContainerJournal? =
        load(pathFor(doppeliskId, packageName))

    /** Every readable journal, paired with its file, in file-name order. */
    fun list(): List<Pair<File, ContainerJournal>> {
        val files = directory.listFiles { f -> f.isFile && f.name.endsWith(".json") } ?: return emptyList()
        return files
            .sortedBy(File::getName)
            .mapNotNull { file -> load(file)?.let { file to it } }
    }

    /** Deletes [journal]'s file and any leftover temp file. */
    fun delete(journal: ContainerJournal) =
        delete(journal.plan.doppeliskId, journal.plan.packageName)

    /** Deletes the journal for one pair. */
    fun delete(doppeliskId: String, packageName: String) {
        val file = pathFor(doppeliskId, packageName)
        file.delete()
        File(file.parentFile, file.name + ".tmp").delete()
    }
}
