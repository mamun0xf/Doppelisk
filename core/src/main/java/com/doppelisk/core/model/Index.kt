package com.doppelisk.core.model

import kotlinx.serialization.Serializable

/**
 * The artifact index at `<root>/index.json`.
 *
 * [assignments] maps package name to the artifact id currently applied to it,
 * and [doppelisks] maps artifact id to its catalog entry. Keeping the index
 * separate from the artifacts lets the manager list everything without parsing
 * every profile.
 */
@Serializable
data class DoppeliskIndex(
    val schema: Int = CURRENT_SCHEMA,
    val assignments: Map<String, String> = emptyMap(),
    val doppelisks: Map<String, IndexEntry> = emptyMap(),
) {
    companion object {
        const val CURRENT_SCHEMA = 2
    }
}

/** Catalog metadata for one artifact file. [path] is absolute. */
@Serializable
data class IndexEntry(
    val id: String,
    val label: String,
    val packageName: String,
    val path: String,
)

/**
 * A list of serialized artifacts.
 *
 * The daemon answers `list_artifacts` with this wrapper whose elements are JSON
 * *strings* rather than nested objects, so the hook side can decode each
 * artifact with its own schema-version-aware codec.
 */
@Serializable
data class ArtifactList(
    val artifacts: List<String> = emptyList(),
)
