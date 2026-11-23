package com.doppelisk.hook.api

/**
 * What a hook should do with one field.
 *
 * The modes are ordered from most faithful to most disruptive. [FAKE] is the
 * default because it is the only mode that keeps the disguise coherent; the
 * others exist for fields the user wants left alone or hidden outright.
 */
enum class PolicyMode {
    /** Return the platform's real value. */
    REAL,

    /** Return the artifact's value. The normal case. */
    FAKE,

    /** Return a value derived per-call, for fields that should not be stable. */
    RANDOM,

    /** Return an empty value (`""`, `0`, empty array). */
    EMPTY,

    /** Return a per-rule constant supplied by the user. */
    CUSTOM,

    /** Make the call fail as though the API were unavailable. */
    BLOCK,
}
