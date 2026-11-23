package com.doppelisk.core.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

/**
 * A mailbox request from a hooked process (or the manager) to the daemon.
 *
 * The request is written to `<ipc>/request-<id>.json`; the daemon replies with
 * `<ipc>/response-<id>.json`. [id] is filled in by the client when omitted, and
 * [pid] lets the daemon attribute the call.
 */
@Serializable
data class Request(
    /** `ping`, `put_artifact`, `get_artifact`, `list_artifacts`, ... */
    val type: String,
    val packageName: String? = null,
    /** JSON-encoded argument; meaning depends on [type]. */
    val payload: String? = null,
    val id: String? = null,
    val pid: Int? = null,
)

/**
 * The daemon's answer.
 *
 * [mailboxDir] is not serialized: it is filled in locally by whichever side
 * read the response so callers can point at the exchange directory without
 * having to know it up front.
 */
@Serializable
data class Response(
    val ok: Boolean,
    val type: String,
    val payload: String? = null,
    val error: String? = null,
    val id: String? = null,
    @Transient val mailboxDir: String? = null,
)
