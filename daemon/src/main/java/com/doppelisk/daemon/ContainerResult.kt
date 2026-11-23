package com.doppelisk.daemon

import com.doppelisk.core.container.ContainerPlan

/**
 * Outcome of [ContainerManager.prepare].
 *
 * [mechanism] is what actually happened rather than what was requested: `shared`
 * when nothing was replaced, `mount` when bind mounts were applied, `swap` when
 * directories were swapped, and `refused` when the request was rejected before
 * touching anything.
 *
 * [mailboxDir] is where the hooked process should look for its IPC mailbox after
 * the container is in place. It is null for [mechanism] `shared`, because the app
 * keeps using its real mailbox.
 */
data class ContainerResult(
    val ok: Boolean,
    val error: String?,
    val plan: ContainerPlan?,
    val mechanism: String,
    val mailboxDir: String? = null,
)
