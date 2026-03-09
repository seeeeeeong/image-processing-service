package com.realteeth.imagejob.domain

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource

class JobStatusTest {

    @Test
    fun `QUEUED는 DISPATCHING으로만 전이 가능`() {
        assertTrue(JobStatus.QUEUED.canTransitionTo(JobStatus.DISPATCHING))
        assertFalse(JobStatus.QUEUED.canTransitionTo(JobStatus.PROCESSING))
        assertFalse(JobStatus.QUEUED.canTransitionTo(JobStatus.COMPLETED))
    }

    @Test
    fun `DISPATCHING은 QUEUED, PROCESSING, COMPLETED, FAILED, RETRY_WAIT, DEAD_LETTER로 전이 가능`() {
        val allowed = setOf(
            JobStatus.QUEUED,
            JobStatus.PROCESSING,
            JobStatus.COMPLETED,
            JobStatus.FAILED,
            JobStatus.RETRY_WAIT,
            JobStatus.DEAD_LETTER
        )
        allowed.forEach { assertTrue(JobStatus.DISPATCHING.canTransitionTo(it), "DISPATCHING -> $it should be allowed") }
    }

    @Test
    fun `PROCESSING은 COMPLETED, FAILED, DEAD_LETTER로만 전이 가능`() {
        assertTrue(JobStatus.PROCESSING.canTransitionTo(JobStatus.COMPLETED))
        assertTrue(JobStatus.PROCESSING.canTransitionTo(JobStatus.FAILED))
        assertTrue(JobStatus.PROCESSING.canTransitionTo(JobStatus.DEAD_LETTER))

        assertFalse(JobStatus.PROCESSING.canTransitionTo(JobStatus.RETRY_WAIT))
        assertFalse(JobStatus.PROCESSING.canTransitionTo(JobStatus.QUEUED))
        assertFalse(JobStatus.PROCESSING.canTransitionTo(JobStatus.DISPATCHING))
    }

    @Test
    fun `RETRY_WAIT는 QUEUED와 DEAD_LETTER로만 전이 가능`() {
        assertTrue(JobStatus.RETRY_WAIT.canTransitionTo(JobStatus.QUEUED))
        assertTrue(JobStatus.RETRY_WAIT.canTransitionTo(JobStatus.DEAD_LETTER))
        assertFalse(JobStatus.RETRY_WAIT.canTransitionTo(JobStatus.PROCESSING))
        assertFalse(JobStatus.RETRY_WAIT.canTransitionTo(JobStatus.COMPLETED))
    }

    @ParameterizedTest
    @EnumSource(value = JobStatus::class, names = ["COMPLETED", "FAILED", "DEAD_LETTER"])
    fun `터미널 상태는 어떤 상태로도 전이 불가`(terminal: JobStatus) {
        JobStatus.entries.forEach { next ->
            assertFalse(terminal.canTransitionTo(next), "$terminal -> $next should NOT be allowed")
        }
        assertTrue(terminal.isTerminal())
    }

    @ParameterizedTest
    @EnumSource(value = JobStatus::class, names = ["QUEUED", "DISPATCHING", "PROCESSING", "RETRY_WAIT"])
    fun `비터미널 상태는 isTerminal이 false`(status: JobStatus) {
        assertFalse(status.isTerminal())
    }

    @Test
    fun `Job transitionTo는 유효한 전이를 허용`() {
        val job = Job(
            payloadHash = "abc",
            imageUrl = "https://example.com/img.jpg"
        )
        assertEquals(JobStatus.QUEUED, job.status)
        job.transitionTo(JobStatus.DISPATCHING)
        assertEquals(JobStatus.DISPATCHING, job.status)
    }

    @Test
    fun `Job transitionTo는 유효하지 않은 전이에서 예외 발생`() {
        val job = Job(
            payloadHash = "abc",
            imageUrl = "https://example.com/img.jpg"
        )
        assertThrows(IllegalArgumentException::class.java) {
            job.transitionTo(JobStatus.COMPLETED)
        }
    }
}
