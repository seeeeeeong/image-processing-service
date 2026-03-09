package com.realteeth.imagejob.domain

import jakarta.persistence.*
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "jobs")
class Job(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(length = 36)
    val id: UUID? = null,

    @Column(name = "idempotency_key")
    val idempotencyKey: String? = null,

    @Column(name = "payload_hash", nullable = false)
    val payloadHash: String,

    @Column(name = "image_url", nullable = false, columnDefinition = "TEXT")
    val imageUrl: String,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, columnDefinition = "VARCHAR(50)")
    var status: JobStatus = JobStatus.QUEUED,

    @Column(name = "external_job_id")
    var externalJobId: String? = null,

    @Column(name = "attempt_count", nullable = false)
    var attemptCount: Int = 0,

    @Column(name = "max_attempts", nullable = false)
    val maxAttempts: Int = 3,

    @Column(name = "next_attempt_at")
    var nextAttemptAt: Instant? = null,

    @Column(name = "poll_due_at")
    var pollDueAt: Instant? = null,

    @Column(name = "locked_until")
    var lockedUntil: Instant? = null,

    @Column(columnDefinition = "TEXT")
    var result: String? = null,

    @Column(name = "last_error_code")
    var lastErrorCode: String? = null,

    @Column(name = "last_error_message", columnDefinition = "TEXT")
    var lastErrorMessage: String? = null,

    @Column(name = "poll_failure_count", nullable = false)
    var pollFailureCount: Int = 0,

    @Column(name = "max_poll_failures", nullable = false)
    val maxPollFailures: Int = 5,

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now()
) {
    fun transitionTo(next: JobStatus) {
        require(status.canTransitionTo(next)) {
            "Invalid state transition: $status -> $next for job $id"
        }
        status = next
        updatedAt = Instant.now()
    }

    fun isExhausted(): Boolean = attemptCount >= maxAttempts
    fun isPollExhausted(): Boolean = pollFailureCount >= maxPollFailures
}
