package com.doppelisk.app.ui.fields

/** How an editor row accepts input. */
enum class FieldKind {
    TEXT,
    INTEGER,
    SELECT,
    BOOLEAN,
    READONLY,
}

/**
 * One editable row in the profile editor.
 *
 * [isOverridden] is the difference between "this profile pins the value" and
 * "this profile inherits the value from the real device". A row that is not
 * overridden shows the real device's value greyed out, and typing into it pins
 * it.
 */
data class FieldSpec(
    val key: String,
    val label: String,
    val value: String,
    val kind: FieldKind = FieldKind.TEXT,
    val options: List<String> = emptyList(),
    val mono: Boolean = false,
    val hint: String? = null,
    val placeholder: String? = null,
    val multiline: Boolean = false,
    val isDefault: Boolean = false,
    val isOverridden: Boolean = false,
)

/** A titled group of [FieldSpec]s. */
data class FieldCategory(
    val id: String,
    val title: String,
    val subtitle: String,
    val fields: List<FieldSpec>,
)
