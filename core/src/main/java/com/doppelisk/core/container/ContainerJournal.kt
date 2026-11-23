package com.doppelisk.core.container

import kotlinx.serialization.Serializable

/**
 * The write-ahead record of one container preparation.
 *
 * The daemon writes a journal *before* it mounts or swaps anything, then updates
 * it as it goes. If the daemon is killed mid-operation — or the device reboots —
 * the next start reads the journal and can put the filesystem back the way it
 * was. Without this, a half-applied hybrid container would leave an app with a
 * swapped `shared_prefs` and no backup path recorded.
 *
 * [pid] and [pidStartTicks] let recovery tell "the process is still running" from
 * "the pid was reused by something else".
 */
@Serializable
data class ContainerJournal(
    val schema: Int = CURRENT_SCHEMA,
    val plan: ContainerPlan,
    val pid: Int,
    val uid: Int,
    /** Kernel start time of [pid], or -1 when unknown. */
    val pidStartTicks: Long = -1L,
    val status: String = JournalStatus.INTENT,
    val mechanism: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val error: String? = null,
    val appliedMounts: List<String> = emptyList(),
    val appliedSwaps: List<String> = emptyList(),
) {
    companion object {
        const val CURRENT_SCHEMA = 1
    }

    /** Returns a copy moved to [next], with the bookkeeping fields updated. */
    fun withStatus(
        next: String,
        error: String? = this.error,
        mountTarget: String? = null,
        swapTarget: String? = null,
        now: Long = System.currentTimeMillis(),
    ): ContainerJournal = copy(
        status = next,
        error = error,
        updatedAt = now,
        appliedMounts = if (mountTarget != null) appliedMounts + mountTarget else appliedMounts,
        appliedSwaps = if (swapTarget != null) appliedSwaps + swapTarget else appliedSwaps,
    )
}
