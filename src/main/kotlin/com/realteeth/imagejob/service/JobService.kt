package com.realteeth.imagejob.service

import com.realteeth.imagejob.config.AppProperties
import com.realteeth.imagejob.domain.Job
import com.realteeth.imagejob.domain.JobStatus
import com.realteeth.imagejob.dto.CreateJobRequest
import com.realteeth.imagejob.dto.JobResponse
import com.realteeth.imagejob.dto.JobSummaryResponse
import com.realteeth.imagejob.dto.PagedResponse
import com.realteeth.imagejob.exception.JobNotFoundException
import com.realteeth.imagejob.repository.JobRepository
import org.slf4j.LoggerFactory
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

@Service
class JobService(
    private val jobRepository: JobRepository,
    private val props: AppProperties,
    private val jobInsertHelper: JobInsertHelper
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun createJob(request: CreateJobRequest, idempotencyKey: String?): Pair<JobResponse, Boolean> {
        val normalizedUrl = normalizeUrl(request.imageUrl)
        val payloadHash = sha256(normalizedUrl)

        if (idempotencyKey != null) {
            val existing = jobRepository.findByIdempotencyKey(idempotencyKey)
            if (existing != null) {
                log.info("Idempotent return for key={}", idempotencyKey)
                return Pair(JobResponse.from(existing), false)
            }
        }

        if (idempotencyKey == null) {
            val existing = jobRepository.findActiveByPayloadHash(payloadHash)
            if (existing != null) {
                log.info("Dedup by payload_hash={}, existing jobId={}", payloadHash, existing.id)
                return Pair(JobResponse.from(existing), false)
            }
        }

        val insertedId = jobInsertHelper.tryInsert(
            idempotencyKey = idempotencyKey,
            payloadHash = payloadHash,
            imageUrl = normalizedUrl,
            maxAttempts = props.worker.maxAttempts,
            maxPollFailures = props.worker.maxPollFailures
        )
        if (insertedId != null) {
            val job = jobRepository.findById(insertedId).orElseThrow {
                IllegalStateException("Job not found after insert: $insertedId")
            }
            return Pair(JobResponse.from(job), true)
        }

        val existing = if (idempotencyKey != null) {
            jobRepository.findByIdempotencyKey(idempotencyKey)
        } else {
            jobRepository.findActiveByPayloadHash(payloadHash)
        }
        val resolved = existing ?: throw IllegalStateException("Conflict but no existing job found for hash=$payloadHash")
        log.info("Concurrent create conflict resolved, returning jobId={}", resolved.id)
        return Pair(JobResponse.from(resolved), false)
    }

    @Transactional(readOnly = true)
    fun getJob(jobId: UUID): JobResponse =
        JobResponse.from(jobRepository.findById(jobId).orElseThrow { JobNotFoundException(jobId) })

    @Transactional(readOnly = true)
    fun listJobs(page: Int, size: Int): PagedResponse<JobSummaryResponse> {
        val pageResult = jobRepository.findAllByOrderByCreatedAtDesc(PageRequest.of(page, size))
        return PagedResponse(
            content = pageResult.content.map { JobSummaryResponse.from(it) },
            page = pageResult.number,
            size = pageResult.size,
            totalElements = pageResult.totalElements,
            totalPages = pageResult.totalPages
        )
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun claimQueuedJobs(): List<Job> {
        val now = Instant.now()
        val jobs = jobRepository.findQueuedForProcessing(now, props.worker.submitBatchSize)
        jobs.forEach { job ->
            job.transitionTo(JobStatus.DISPATCHING)
            job.lockedUntil = now.plusSeconds(props.worker.leaseSeconds)
            job.updatedAt = now
        }
        return jobRepository.saveAll(jobs)
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun onSubmitSuccess(jobId: UUID, externalJobId: String, workerStatus: String) {
        val job = jobRepository.findById(jobId).orElseThrow { JobNotFoundException(jobId) }
        job.externalJobId = externalJobId
        job.attemptCount++
        job.lockedUntil = null

        when (workerStatus) {
            "PROCESSING" -> {
                job.transitionTo(JobStatus.PROCESSING)
                job.pollDueAt = Instant.now().plusSeconds(props.worker.pollInitialDelaySeconds)
            }
            "COMPLETED" -> {
                job.transitionTo(JobStatus.COMPLETED)
                job.lastErrorCode = null
                job.lastErrorMessage = null
                log.info("Job immediately completed: jobId={}", jobId)
            }
            "FAILED" -> {
                job.transitionTo(JobStatus.FAILED)
                job.lastErrorCode = "WORKER_FAILED"
                job.lastErrorMessage = "Mock Worker returned FAILED immediately"
                log.warn("Job immediately failed by worker: jobId={}", jobId)
            }
            else -> {
                log.error("Unknown worker status '{}' on submit for jobId={}, marking FAILED", workerStatus, jobId)
                job.transitionTo(JobStatus.FAILED)
                job.lastErrorCode = "UNKNOWN_STATUS"
                job.lastErrorMessage = "Mock Worker returned unexpected status: $workerStatus"
            }
        }
        jobRepository.save(job)
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun onSubmitSkipped(jobId: UUID) {
        val job = jobRepository.findById(jobId).orElseThrow { JobNotFoundException(jobId) }
        val now = Instant.now()
        job.transitionTo(JobStatus.QUEUED)
        job.lockedUntil = null
        job.nextAttemptAt = now.plusSeconds(BULKHEAD_BACKOFF_SECONDS)
        job.updatedAt = now
        jobRepository.save(job)
        log.warn("Submit skipped (bulkhead full), released to QUEUED with {}s backoff: jobId={}", BULKHEAD_BACKOFF_SECONDS, jobId)
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun onSubmitFailure(jobId: UUID, errorCode: String, errorMessage: String?) {
        val job = jobRepository.findById(jobId).orElseThrow { JobNotFoundException(jobId) }
        job.attemptCount++
        job.lockedUntil = null
        job.lastErrorCode = errorCode
        job.lastErrorMessage = errorMessage

        if (job.isExhausted()) {
            job.transitionTo(JobStatus.DEAD_LETTER)
            log.error("Job exhausted max attempts: jobId={}, attempts={}", jobId, job.attemptCount)
        } else {
            job.transitionTo(JobStatus.RETRY_WAIT)
            job.nextAttemptAt = calculateBackoff(job.attemptCount)
            log.warn("Job will retry: jobId={}, attempt={}, nextAt={}", jobId, job.attemptCount, job.nextAttemptAt)
        }
        jobRepository.save(job)
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun onSubmitNonRetryableFailure(jobId: UUID, errorCode: String, errorMessage: String?) {
        val job = jobRepository.findById(jobId).orElseThrow { JobNotFoundException(jobId) }
        job.lockedUntil = null
        job.lastErrorCode = errorCode
        job.lastErrorMessage = errorMessage
        job.transitionTo(JobStatus.FAILED)
        jobRepository.save(job)
        log.warn("Job failed non-retryably: jobId={}, code={}", jobId, errorCode)
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun claimProcessingJobs(): List<Job> {
        val now = Instant.now()
        val jobs = jobRepository.findProcessingForPoll(now, props.worker.pollBatchSize)
        jobs.forEach { job ->
            job.lockedUntil = now.plusSeconds(props.worker.leaseSeconds)
            job.updatedAt = now
        }
        return jobRepository.saveAll(jobs)
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun onPollResult(jobId: UUID, workerStatus: String, result: String?) {
        val job = jobRepository.findById(jobId).orElseThrow { JobNotFoundException(jobId) }
        job.lockedUntil = null

        when (workerStatus) {
            "COMPLETED" -> {
                job.transitionTo(JobStatus.COMPLETED)
                job.result = result
                job.lastErrorCode = null
                job.lastErrorMessage = null
                log.info("Job completed: jobId={}", jobId)
            }
            "FAILED" -> {
                job.transitionTo(JobStatus.FAILED)
                job.lastErrorCode = "WORKER_FAILED"
                job.lastErrorMessage = "Mock Worker processing failed"
                log.warn("Job failed by worker: jobId={}", jobId)
            }
            "PROCESSING" -> {
                val delaySecs = minOf(
                    (props.worker.pollInitialDelaySeconds * Math.pow(props.worker.pollBackoffMultiplier, job.pollFailureCount.toDouble())).toLong(),
                    props.worker.pollMaxDelaySeconds
                )
                job.pollDueAt = Instant.now().plusSeconds(delaySecs)
            }
            else -> {
                log.error("Unknown worker status '{}' on poll for jobId={}, scheduling next poll", workerStatus, jobId)
                job.pollDueAt = Instant.now().plusSeconds(props.worker.pollInitialDelaySeconds)
            }
        }
        jobRepository.save(job)
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun onPollRetryableFailure(jobId: UUID, errorCode: String, errorMessage: String?) {
        val job = jobRepository.findById(jobId).orElseThrow { JobNotFoundException(jobId) }
        job.pollFailureCount++
        job.lockedUntil = null
        job.lastErrorCode = errorCode
        job.lastErrorMessage = errorMessage

        if (job.isPollExhausted()) {
            job.transitionTo(JobStatus.DEAD_LETTER)
            log.error("Job exhausted poll failures: jobId={}, pollFailures={}", jobId, job.pollFailureCount)
        } else {
            val delaySecs = minOf(
                (props.worker.pollInitialDelaySeconds * Math.pow(props.worker.pollBackoffMultiplier, job.pollFailureCount.toDouble())).toLong(),
                props.worker.pollMaxDelaySeconds
            )
            job.pollDueAt = Instant.now().plusSeconds(delaySecs)
            job.updatedAt = Instant.now()
            log.warn("Poll failed, will retry: jobId={}, pollFailures={}, nextAt={}", jobId, job.pollFailureCount, job.pollDueAt)
        }
        jobRepository.save(job)
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun onPollNonRetryableFailure(jobId: UUID, errorCode: String, errorMessage: String?) {
        val job = jobRepository.findById(jobId).orElseThrow { JobNotFoundException(jobId) }
        job.lockedUntil = null
        job.lastErrorCode = errorCode
        job.lastErrorMessage = errorMessage
        job.transitionTo(JobStatus.FAILED)
        jobRepository.save(job)
        log.warn("Poll failed non-retryably: jobId={}, code={}", jobId, errorCode)
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun requeueRetryWaitJobs() {
        val now = Instant.now()
        val jobs = jobRepository.findRetryWaitReadyToQueue(now)
        jobs.forEach { job ->
            job.transitionTo(JobStatus.QUEUED)
            job.nextAttemptAt = null
            job.updatedAt = now
        }
        if (jobs.isNotEmpty()) {
            jobRepository.saveAll(jobs)
            log.info("Requeued {} RETRY_WAIT jobs", jobs.size)
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun recoverLeaseExpiredJobs() {
        val now = Instant.now()
        val expiredJobs = jobRepository.findLeaseExpired(now)

        expiredJobs.forEach { job ->
            when (job.status) {
                JobStatus.DISPATCHING -> {
                    log.warn("Recovering DISPATCHING job with expired lease: jobId={}", job.id)
                    job.transitionTo(JobStatus.QUEUED)
                    job.lockedUntil = null
                    job.nextAttemptAt = null
                }
                JobStatus.PROCESSING -> {
                    log.warn("Resetting locked_until for stale PROCESSING job: jobId={}", job.id)
                    job.lockedUntil = null
                    job.pollDueAt = now
                    job.updatedAt = now
                }
                else -> {  }
            }
            jobRepository.save(job)
        }
    }

    companion object {
        private const val BULKHEAD_BACKOFF_SECONDS = 10L
    }

    private fun normalizeUrl(url: String): String {
        val trimmed = url.trim()
        val schemeEnd = trimmed.indexOf("://")
        if (schemeEnd == -1) return trimmed

        val scheme = trimmed.substring(0, schemeEnd).lowercase()
        val afterScheme = trimmed.substring(schemeEnd + 3)
        val hostEnd = afterScheme.indexOfFirst { it == '/' || it == '?' || it == '#' }
        val host = if (hostEnd == -1) afterScheme.lowercase()
                   else afterScheme.substring(0, hostEnd).lowercase()
        val pathAndQuery = if (hostEnd == -1) "" else afterScheme.substring(hostEnd).trimEnd('/')

        return "$scheme://$host$pathAndQuery"
    }

    private fun sha256(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun calculateBackoff(attempt: Int): Instant {
        val delaySecs = minOf(
            (2.0.pow(attempt)).toLong() * 5,
            300L
        )
        return Instant.now().plusSeconds(delaySecs)
    }

    private fun Double.pow(n: Int): Double = Math.pow(this, n.toDouble())
}
