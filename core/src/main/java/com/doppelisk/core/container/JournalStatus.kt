package com.doppelisk.core.container

/** Lifecycle states recorded in a [ContainerJournal]. */
object JournalStatus {
    /** Recorded before anything is touched. */
    const val INTENT = "intent"

    /** Container directories are being prepared. */
    const val PREPARING = "preparing"

    /** Bind mounts are being applied. */
    const val MOUNTING = "mounting"

    /** All bind mounts are in place. */
    const val MOUNTED = "mounted"

    /** Directory swaps are being applied. */
    const val SWAPPING = "swapping"

    /** Every swap is in place. */
    const val SWAPPED = "swapped"

    /** An operation failed; a rollback is required. */
    const val FAILED = "failed"

    /** A rollback already ran. */
    const val RECOVERED = "recovered"

    /**
     * Statuses that mean "an operation was under way". A journal in one of these
     * states after a restart is evidence of a crash rather than a finished job.
     */
    val IN_FLIGHT: Set<String> = setOf(INTENT, PREPARING, MOUNTING, SWAPPING)
}
