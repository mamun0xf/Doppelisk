package com.doppelisk.core.model

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json

/**
 * The JSON dialects this project speaks.
 *
 * They differ on purpose, so each one is named after the document family it
 * serves rather than being built ad hoc at call sites.
 */
@OptIn(ExperimentalSerializationApi::class)
object JsonFormats {

    /**
     * Profiles, the index and the chassis catalog.
     *
     * Human-editable, checked into a diffable tree: pretty printed with a
     * four-space indent, defaults materialised, and nulls elided so that an
     * unset optional field is simply absent from the file.
     */
    val profile: Json = Json {
        prettyPrint = true
        prettyPrintIndent = "    "
        encodeDefaults = true
        explicitNulls = false
        ignoreUnknownKeys = true
    }

    /**
     * Mailbox requests and responses.
     *
     * Single-line files where nulls are meaningful (`"payload": null` is an
     * answer, not a missing field), so they are written explicitly.
     */
    val wire: Json = Json {
        encodeDefaults = true
        explicitNulls = true
        ignoreUnknownKeys = true
    }

    /**
     * Audit reports, fixtures and migration scratch documents.
     *
     * Compact like [wire] but with nulls elided, matching the audit files the
     * daemon writes.
     */
    val compact: Json = Json {
        encodeDefaults = true
        explicitNulls = false
        ignoreUnknownKeys = true
    }

    /** Catalog parsing: never rewrite the shipped catalog, only read it. */
    val catalog: Json = Json {
        ignoreUnknownKeys = true
    }
}
