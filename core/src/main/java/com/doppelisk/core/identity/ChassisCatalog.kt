package com.doppelisk.core.identity

import com.doppelisk.core.model.CatalogFile
import com.doppelisk.core.model.Chassis
import com.doppelisk.core.model.JsonFormats
import com.doppelisk.core.model.Provenance
import java.security.MessageDigest
import java.util.Locale

/**
 * The shipped chassis catalog, loaded once from the classpath.
 *
 * The catalog lives in `resources` rather than `assets` because the hook and the
 * daemon both run as `app_process` with the APK on the classpath and have no
 * `Context` to reach assets through.
 *
 * Loading is lazy so a process that never generates an identity never pays for
 * parsing 21 KB of JSON. Every [Chassis] gets its [Chassis.hash] computed at
 * load time, and a chassis with no provenance of its own inherits the file-level
 * provenance.
 */
object ChassisCatalog {

    private const val RESOURCE = "/chassis/catalog.json"

    private val json = JsonFormats.catalog

    private val loaded: CatalogFile by lazy { load() }

    private val byId: Map<String, Chassis> by lazy { loaded.chassis.associateBy(Chassis::id) }

    /** Every chassis, in catalog order. */
    fun all(): List<Chassis> = loaded.chassis

    /** The chassis with [id], or null when the catalog does not know it. */
    fun find(id: String): Chassis? = byId[id]

    /** Provenance of the catalog as a whole. */
    fun provenance(): Provenance = loaded.provenance

    /** Reads and hashes the catalog. */
    fun load(): CatalogFile {
        val stream = ChassisCatalog::class.java.getResourceAsStream(RESOURCE)
            ?: throw IllegalStateException("Missing $RESOURCE resource")
        val text = stream.use { it.readBytes().toString(Charsets.UTF_8) }
        val file = json.decodeFromString(CatalogFile.serializer(), text)
        val hashed = file.chassis.map { chassis ->
            chassis.copy(
                hash = hashChassis(chassis),
                provenance = if (chassis.provenance.source.isBlank()) file.provenance else chassis.provenance,
            )
        }
        return file.copy(chassis = hashed)
    }

    /**
     * Content hash of [chassis] with its own [Chassis.hash] blanked.
     *
     * The blanking is what makes this a content hash: the stored hash must not
     * feed into its own computation.
     */
    fun hashChassis(chassis: Chassis): String {
        val canonical = json.encodeToString(Chassis.serializer(), chassis.copy(hash = ""))
        val digest = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
        return "sha256:" + digest.joinToString("") { String.format(Locale.US, "%02x", it.toInt() and 0xFF) }
    }
}
