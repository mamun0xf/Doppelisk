package com.doppelisk.core.container

/** What recovery should do about a journal left behind by an interrupted run. */
sealed interface RecoveryAction {

    /** Nothing to undo: either the operation never started, or it finished. */
    data object Keep : RecoveryAction

    /** Undo the recorded mounts only. */
    data class RollbackMounts(val journal: ContainerJournal) : RecoveryAction

    /** Undo the recorded swaps only. */
    data class RollbackSwaps(val journal: ContainerJournal) : RecoveryAction

    /** Undo mounts and swaps. */
    data class RollbackAll(val journal: ContainerJournal) : RecoveryAction

    /** Mounts are in place and the process is gone; just record that. */
    data class FinalizeMounted(val journal: ContainerJournal) : RecoveryAction

    /** Swaps are in place and the process is gone; just record that. */
    data class FinalizeSwapped(val journal: ContainerJournal) : RecoveryAction
}

/**
 * Decides what to do with a journal, given whether its process is still alive.
 *
 * The rule is conservative: anything that may have been half-applied is rolled
 * back, and only a fully applied journal is finalised. A live process keeps its
 * container exactly as it is.
 */
object JournalRecovery {

    fun decide(journal: ContainerJournal, pidAlive: Boolean): RecoveryAction {
        if (pidAlive) return RecoveryAction.Keep

        return when (journal.status) {
            JournalStatus.INTENT, JournalStatus.PREPARING ->
                if (journal.appliedMounts.isEmpty() && journal.appliedSwaps.isEmpty()) {
                    RecoveryAction.Keep
                } else {
                    RecoveryAction.RollbackAll(journal)
                }

            JournalStatus.MOUNTING -> RecoveryAction.RollbackMounts(journal)
            JournalStatus.SWAPPING -> RecoveryAction.RollbackAll(journal)
            JournalStatus.FAILED -> RecoveryAction.RollbackAll(journal)
            JournalStatus.MOUNTED -> RecoveryAction.FinalizeMounted(journal)
            JournalStatus.SWAPPED -> RecoveryAction.FinalizeSwapped(journal)
            JournalStatus.RECOVERED -> RecoveryAction.Keep
            else -> RecoveryAction.Keep
        }
    }

    /**
     * True when [currentStartTicks] still belongs to the process the journal was
     * written for.
     *
     * A journal with no recorded start ticks cannot be verified, so it is
     * trusted; a caller with no current ticks at all is not.
     */
    fun pidStillBelongsTo(journal: ContainerJournal, currentStartTicks: Long?): Boolean {
        if (journal.pidStartTicks <= 0) return true
        return currentStartTicks != null && currentStartTicks > 0 && journal.pidStartTicks == currentStartTicks
    }
}
