package com.doppelisk.core.config

import com.doppelisk.core.model.DoppeliskArtifact
import com.doppelisk.core.model.DoppeliskIndex
import com.doppelisk.core.model.IndexEntry
import com.doppelisk.core.model.JsonFormats
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Upgrades documents written with an older schema to the current one.
 *
 * The decoders are strict on purpose: a document that is missing a field should
 * fail loudly instead of silently losing data. That makes it the wrong place to
 * handle older layouts, so migration happens on the raw JSON tree, where a
 * document that is *almost* decodable can still be repaired.
 *
 * Two kinds of change are handled here:
 *
 *  - a stored schema version below [ARTIFACT_SCHEMA] or [INDEX_SCHEMA];
 *  - a display refresh rate below what the chassis panel actually supports,
 *    which produces a profile whose own fields disagree.
 */
object ConfigMigrations {

    const val ARTIFACT_SCHEMA = DoppeliskArtifact.CURRENT_SCHEMA
    const val INDEX_SCHEMA = DoppeliskIndex.CURRENT_SCHEMA

    private val json: Json = JsonFormats.compact

    /**
     * Highest refresh rate each chassis id is known to support.
     *
     * A profile whose stored refresh is lower than the panel's real maximum is
     * repaired upward; the reverse is left alone because it can be intentional.
     */
    private val MAX_REFRESH: Map<String, Int> = mapOf(
        "pixel8pro-husky" to 120,
        "pixel8a-akita" to 120,
        "pixel7pro-cheetah" to 120,
        "galaxy-s24-e1s" to 120,
        "galaxy-a34-a34x" to 120,
        "galaxy-a53-a53x" to 120,
        "galaxy-a24-a24" to 90,
        "galaxy-a73-a73xq" to 120,
        "galaxy-m21-m21" to 60,
        "xiaomi-13-fuxi" to 120,
        "redmi-note10pro-sweet" to 120,
        "realme-c67-re5c91l1" to 90,
        "vivo-v27-v2231" to 120,
        "oneplus-nord-n200-oneplusn200" to 90,
        "oneplus-13-op5d55l1" to 120,
    )

    /**
     * Returns [raw] migrated to [ARTIFACT_SCHEMA].
     *
     * Unparseable input is returned untouched so the caller's decoder produces
     * the real error. Documents from a newer schema are also returned untouched
     * — a newer build may have written fields this one must not discard.
     */
    fun migrateArtifact(raw: String): String {
        val root = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return raw
        val schema = root["schema"]?.jsonPrimitive?.intOrNull ?: 0
        if (schema > ARTIFACT_SCHEMA) return raw

        val repaired = repairRefresh(root)
        if (schema == ARTIFACT_SCHEMA) {
            return if (repaired == root) raw else repaired.toString()
        }
        val upgraded = buildJsonObject {
            repaired.forEach { (key, value) -> put(key, value) }
            put("schema", JsonPrimitive(ARTIFACT_SCHEMA))
        }
        return upgraded.toString()
    }

    /**
     * Raises `identity.display.refresh` to the chassis maximum when it is too
     * low. Returns [obj] unchanged when the chassis is unknown, has no refresh
     * table entry, or is already at the maximum.
     */
    private fun repairRefresh(obj: JsonObject): JsonObject {
        val chassisId = obj["chassis"]?.jsonObject?.get("id")?.jsonPrimitive?.content ?: return obj
        val max = MAX_REFRESH[chassisId] ?: return obj
        val identity = obj["identity"]?.jsonObject ?: return obj
        val display = identity["display"]?.jsonObject ?: return obj
        val current = display["refresh"]?.jsonPrimitive?.intOrNull ?: 0
        if (current >= max) return obj

        val fixedDisplay = JsonObject(display + ("refresh" to JsonPrimitive(max)))
        val fixedIdentity = JsonObject(identity + ("display" to fixedDisplay))
        return JsonObject(obj + ("identity" to fixedIdentity))
    }

    /**
     * Rewrites [index] to [INDEX_SCHEMA], re-pointing every entry at its current
     * absolute path and dropping assignments that no longer resolve.
     *
     * [artifactPath] is injected so the same migration works against a real
     * root and against a temporary directory in tests.
     */
    fun migrateIndex(
        index: DoppeliskIndex,
        artifactPath: (String) -> String = ConfigPaths::artifactPath,
    ): DoppeliskIndex {
        if (index.schema > INDEX_SCHEMA) return index

        val entries = index.doppelisks.mapValues { (id, entry) ->
            entry.copy(path = artifactPath(id))
        }
        val assignments = index.assignments.filter { (packageName, id) ->
            id in entries || id == ConfigPaths.defaultProfileId(packageName)
        }
        return DoppeliskIndex(INDEX_SCHEMA, assignments, entries)
    }
}
