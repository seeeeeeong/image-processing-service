package com.realteeth.imagejob.domain

enum class JobStatus {
    QUEUED,
    DISPATCHING,
    PROCESSING,
    RETRY_WAIT,
    COMPLETED,
    FAILED,
    DEAD_LETTER;

    fun isTerminal(): Boolean = this in TERMINAL_STATES

    fun canTransitionTo(next: JobStatus): Boolean =
        next in (ALLOWED_TRANSITIONS[this] ?: emptySet())

    companion object {
        private val TERMINAL_STATES = setOf(COMPLETED, FAILED, DEAD_LETTER)

        private val ALLOWED_TRANSITIONS = mapOf(
            QUEUED      to setOf(DISPATCHING),
            DISPATCHING to setOf(QUEUED, PROCESSING, COMPLETED, FAILED, RETRY_WAIT, DEAD_LETTER),
            PROCESSING  to setOf(COMPLETED, FAILED, DEAD_LETTER),
            RETRY_WAIT  to setOf(QUEUED, DEAD_LETTER),
            COMPLETED   to emptySet(),
            FAILED      to emptySet(),
            DEAD_LETTER to emptySet()
        )
    }
}
