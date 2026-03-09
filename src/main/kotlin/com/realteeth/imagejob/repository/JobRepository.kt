package com.realteeth.imagejob.repository

import com.realteeth.imagejob.domain.Job
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

interface JobRepository : JpaRepository<Job, UUID> {

    fun findByIdempotencyKey(key: String): Job?

    @Query(
        value = """
            SELECT * FROM jobs
            WHERE active_dedup_key = :payloadHash
            LIMIT 1
        """,
        nativeQuery = true
    )
    fun findActiveByPayloadHash(@Param("payloadHash") payloadHash: String): Job?

    @Modifying
    @Transactional
    @Query(
        value = """
            INSERT IGNORE INTO jobs (id, idempotency_key, payload_hash, image_url, status,
                                     attempt_count, max_attempts, poll_failure_count, max_poll_failures,
                                     created_at, updated_at)
            VALUES (:id, :idempotencyKey, :payloadHash, :imageUrl, 'QUEUED',
                    0, :maxAttempts, 0, :maxPollFailures, NOW(6), NOW(6))
        """,
        nativeQuery = true
    )
    fun insertIfNotExists(
        @Param("id") id: String,
        @Param("idempotencyKey") idempotencyKey: String?,
        @Param("payloadHash") payloadHash: String,
        @Param("imageUrl") imageUrl: String,
        @Param("maxAttempts") maxAttempts: Int,
        @Param("maxPollFailures") maxPollFailures: Int
    ): Int

    @Query(
        value = """
            SELECT * FROM jobs
            WHERE status = 'QUEUED'
              AND (next_attempt_at IS NULL OR next_attempt_at <= :now)
              AND (locked_until IS NULL OR locked_until <= :now)
            ORDER BY created_at ASC
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
        """,
        nativeQuery = true
    )
    fun findQueuedForProcessing(
        @Param("now") now: Instant,
        @Param("limit") limit: Int
    ): List<Job>

    @Query(
        value = """
            SELECT * FROM jobs
            WHERE status = 'PROCESSING'
              AND poll_due_at <= :now
              AND (locked_until IS NULL OR locked_until <= :now)
            ORDER BY poll_due_at ASC
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
        """,
        nativeQuery = true
    )
    fun findProcessingForPoll(
        @Param("now") now: Instant,
        @Param("limit") limit: Int
    ): List<Job>

    @Query(
        value = """
            SELECT * FROM jobs
            WHERE status IN ('DISPATCHING', 'PROCESSING')
              AND locked_until < :now
            FOR UPDATE SKIP LOCKED
        """,
        nativeQuery = true
    )
    fun findLeaseExpired(@Param("now") now: Instant): List<Job>

    @Query(
        value = """
            SELECT * FROM jobs
            WHERE status = 'RETRY_WAIT'
              AND (next_attempt_at IS NULL OR next_attempt_at <= :now)
            FOR UPDATE SKIP LOCKED
        """,
        nativeQuery = true
    )
    fun findRetryWaitReadyToQueue(@Param("now") now: Instant): List<Job>

    fun findAllByOrderByCreatedAtDesc(pageable: Pageable): Page<Job>
}
