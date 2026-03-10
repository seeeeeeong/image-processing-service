package com.realteeth.imagejob.service

import com.realteeth.imagejob.config.AppProperties
import com.realteeth.imagejob.config.MockWorkerProperties
import com.realteeth.imagejob.config.WorkerProperties
import com.realteeth.imagejob.domain.Job
import com.realteeth.imagejob.domain.JobStatus
import com.realteeth.imagejob.dto.CreateJobRequest
import com.realteeth.imagejob.repository.JobRepository
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.*
import java.time.Instant
import java.util.Optional
import java.util.UUID

/**
 * JobService 비즈니스 로직 단위 테스트.
 * Mockito-kotlin으로 JobRepository, JobInsertHelper를 목킹하여
 * 트랜잭션/DB 없이 순수 로직만 검증.
 *
 * 테스트 범주:
 * - createJob: 멱등성, 중복 제거, 동시성 충돌 처리
 * - submit 결과 처리: 성공/실패/스킵(bulkhead)
 * - poll 결과 처리: 완료/진행 중/실패
 * - lease 복구: DISPATCHING/PROCESSING 만료, RETRY_WAIT 재대기열
 */
class JobServiceTest {

    private lateinit var jobRepository: JobRepository
    private lateinit var jobInsertHelper: JobInsertHelper
    private lateinit var jobService: JobService

    private val props = AppProperties(
        mockWorker = MockWorkerProperties(baseUrl = "http://mock", apiKey = "test-key"),
        worker = WorkerProperties(maxAttempts = 3)
    )

    @BeforeEach
    fun setup() {
        jobRepository = mock()
        jobInsertHelper = mock()
        jobService = JobService(jobRepository, props, jobInsertHelper)
    }



    @Test
    fun `Idempotency-Key가 있고 기존 잡이 존재하면 기존 잡을 반환`() {
        val key = "key-abc"
        val existingJob = createJob()
        whenever(jobRepository.findActiveByPayloadHash(any())).thenReturn(null)
        whenever(jobRepository.findByIdempotencyKey(key)).thenReturn(existingJob)

        val (response, created) = jobService.createJob(CreateJobRequest("https://example.com/img.jpg"), key)

        assertFalse(created)
        assertEquals(existingJob.id, response.jobId)
        verify(jobInsertHelper, never()).tryInsert(anyOrNull(), any(), any(), any(), any())
    }

    @Test
    fun `Idempotency-Key가 있어도 활성 잡이 있으면 dedup을 먼저 적용한다`() {
        val activeJob = createJob()
        whenever(jobRepository.findActiveByPayloadHash(any())).thenReturn(activeJob)

        val (response, created) = jobService.createJob(CreateJobRequest("https://example.com/img.jpg"), "key-abc")

        assertFalse(created)
        assertEquals(activeJob.id, response.jobId)
        verify(jobRepository, never()).findByIdempotencyKey(any())
        verify(jobInsertHelper, never()).tryInsert(anyOrNull(), any(), any(), any(), any())
    }

    @Test
    fun `Idempotency-Key가 없고 동일 imageUrl의 active 잡이 있으면 기존 잡을 반환`() {
        val existingJob = createJob()
        whenever(jobRepository.findActiveByPayloadHash(any())).thenReturn(existingJob)

        val (response, created) = jobService.createJob(CreateJobRequest("https://example.com/img.jpg"), null)

        assertFalse(created)
        assertEquals(existingJob.id, response.jobId)
    }

    @Test
    fun `동일 imageUrl이더라도 기존 잡이 COMPLETED이면 신규 잡 생성`() {
        whenever(jobRepository.findActiveByPayloadHash(any())).thenReturn(null)
        val newJob = createJob()
        val insertedId = newJob.id!!
        whenever(jobInsertHelper.tryInsert(anyOrNull(), any(), any(), any(), any())).thenReturn(insertedId)
        whenever(jobRepository.findById(insertedId)).thenReturn(Optional.of(newJob))

        val (_, created) = jobService.createJob(CreateJobRequest("https://example.com/img.jpg"), null)

        assertTrue(created)
        verify(jobInsertHelper).tryInsert(anyOrNull(), any(), any(), any(), any())
    }

    @Test
    fun `imageUrl은 정규화되어 동일한 hash로 처리`() {

        val job1 = createJob()
        whenever(jobInsertHelper.tryInsert(anyOrNull(), any(), any(), any(), any())).thenReturn(job1.id!!)
        whenever(jobRepository.findById(job1.id!!)).thenReturn(Optional.of(job1))

        jobService.createJob(CreateJobRequest("https://example.com/img.jpg/"), null)
        jobService.createJob(CreateJobRequest("HTTPS://EXAMPLE.COM/IMG.JPG"), null)



        verify(jobInsertHelper, times(2)).tryInsert(anyOrNull(), any(), any(), any(), any())
    }



    @Test
    fun `onSubmitSuccess PROCESSING 응답이면 PROCESSING으로 전환`() {
        val job = createJob(status = JobStatus.DISPATCHING)
        whenever(jobRepository.findById(job.id!!)).thenReturn(Optional.of(job))
        whenever(jobRepository.save(any<Job>())).thenReturn(job)

        jobService.onSubmitSuccess(job.id!!, "ext-job-123", "PROCESSING")

        assertEquals(JobStatus.PROCESSING, job.status)
        assertEquals("ext-job-123", job.externalJobId)
        assertNotNull(job.pollDueAt)
    }

    @Test
    fun `onSubmitSuccess COMPLETED 응답이면 PROCESSING 유지 후 즉시 poll`() {
        val job = createJob(status = JobStatus.DISPATCHING)
        whenever(jobRepository.findById(job.id!!)).thenReturn(Optional.of(job))
        whenever(jobRepository.save(any<Job>())).thenReturn(job)

        jobService.onSubmitSuccess(job.id!!, "ext-job-123", "COMPLETED")

        assertEquals(JobStatus.PROCESSING, job.status)
        assertNotNull(job.pollDueAt)
    }

    @Test
    fun `onSubmitFailure 재시도 가능하면 RETRY_WAIT으로 전환`() {
        val job = createJob(status = JobStatus.DISPATCHING, attemptCount = 0)
        whenever(jobRepository.findById(job.id!!)).thenReturn(Optional.of(job))
        whenever(jobRepository.save(any<Job>())).thenReturn(job)

        jobService.onSubmitFailure(job.id!!, "HTTP_500", "Server Error")

        assertEquals(JobStatus.RETRY_WAIT, job.status)
        assertNotNull(job.nextAttemptAt)
        assertEquals(1, job.attemptCount)
    }

    @Test
    fun `onSubmitFailure maxAttempts 초과하면 DEAD_LETTER`() {
        val job = createJob(status = JobStatus.DISPATCHING, attemptCount = 2, maxAttempts = 3)
        whenever(jobRepository.findById(job.id!!)).thenReturn(Optional.of(job))
        whenever(jobRepository.save(any<Job>())).thenReturn(job)

        jobService.onSubmitFailure(job.id!!, "HTTP_500", "Server Error")

        assertEquals(JobStatus.DEAD_LETTER, job.status)
        assertEquals(3, job.attemptCount)
    }

    @Test
    fun `onSubmitNonRetryableFailure는 즉시 FAILED`() {
        val job = createJob(status = JobStatus.DISPATCHING)
        whenever(jobRepository.findById(job.id!!)).thenReturn(Optional.of(job))
        whenever(jobRepository.save(any<Job>())).thenReturn(job)

        jobService.onSubmitNonRetryableFailure(job.id!!, "CLIENT_ERROR_400", "Bad Request")

        assertEquals(JobStatus.FAILED, job.status)
        assertEquals("CLIENT_ERROR_400", job.lastErrorCode)

        assertEquals(0, job.attemptCount)
    }



    @Test
    fun `onPollResult COMPLETED이면 COMPLETED 전환 및 error 필드 클리어`() {
        val job = createJob(status = JobStatus.PROCESSING).apply {
            lastErrorCode = "PREVIOUS_ERROR"
            lastErrorMessage = "이전 오류"
        }
        whenever(jobRepository.findById(job.id!!)).thenReturn(Optional.of(job))
        whenever(jobRepository.save(any<Job>())).thenReturn(job)

        jobService.onPollResult(job.id!!, "COMPLETED", "처리 결과")

        assertEquals(JobStatus.COMPLETED, job.status)
        assertEquals("처리 결과", job.result)
        assertNull(job.lastErrorCode)
        assertNull(job.lastErrorMessage)
    }

    @Test
    fun `onPollResult PROCESSING이면 상태 유지 + pollDueAt 갱신`() {
        val job = createJob(status = JobStatus.PROCESSING)
        whenever(jobRepository.findById(job.id!!)).thenReturn(Optional.of(job))
        whenever(jobRepository.save(any<Job>())).thenReturn(job)

        jobService.onPollResult(job.id!!, "PROCESSING", null)

        assertEquals(JobStatus.PROCESSING, job.status)
        assertNotNull(job.pollDueAt)
    }

    @Test
    fun `onPollRetryableFailure는 PROCESSING 유지 + pollFailureCount 증가 + pollDueAt 지연`() {
        val job = createJob(status = JobStatus.PROCESSING)
        whenever(jobRepository.findById(job.id!!)).thenReturn(Optional.of(job))
        whenever(jobRepository.save(any<Job>())).thenReturn(job)

        jobService.onPollRetryableFailure(job.id!!, "HTTP_500", "Server Error")

        assertEquals(JobStatus.PROCESSING, job.status)
        assertEquals(1, job.pollFailureCount)
        assertNotNull(job.pollDueAt)
        assertEquals("HTTP_500", job.lastErrorCode)
    }

    @Test
    fun `onPollRetryableFailure maxPollFailures 초과하면 DEAD_LETTER`() {
        val job = createJob(status = JobStatus.PROCESSING, pollFailureCount = 4, maxPollFailures = 5)
        whenever(jobRepository.findById(job.id!!)).thenReturn(Optional.of(job))
        whenever(jobRepository.save(any<Job>())).thenReturn(job)

        jobService.onPollRetryableFailure(job.id!!, "HTTP_500", "Server Error")

        assertEquals(JobStatus.DEAD_LETTER, job.status)
        assertEquals(5, job.pollFailureCount)
    }

    @Test
    fun `onPollNonRetryableFailure는 즉시 FAILED (submit 재시도 경로 미진입)`() {
        val job = createJob(status = JobStatus.PROCESSING)
        whenever(jobRepository.findById(job.id!!)).thenReturn(Optional.of(job))
        whenever(jobRepository.save(any<Job>())).thenReturn(job)

        jobService.onPollNonRetryableFailure(job.id!!, "CLIENT_ERROR_404", "Not Found")

        assertEquals(JobStatus.FAILED, job.status)
        assertEquals("CLIENT_ERROR_404", job.lastErrorCode)
        assertEquals(0, job.pollFailureCount)
    }

    @Test
    fun `PROCESSING은 RETRY_WAIT으로 전이 불가`() {
        val job = createJob(status = JobStatus.PROCESSING)
        assertThrows(IllegalArgumentException::class.java) {
            job.transitionTo(JobStatus.RETRY_WAIT)
        }
    }



    @Test
    fun `recoverLeaseExpiredJobs - DISPATCHING lease 만료 시 QUEUED 복귀`() {
        val job = createJob(status = JobStatus.DISPATCHING).apply {
            lockedUntil = Instant.now().minusSeconds(10)
        }
        whenever(jobRepository.findLeaseExpired(any())).thenReturn(listOf(job))
        whenever(jobRepository.save(any<Job>())).thenReturn(job)

        jobService.recoverLeaseExpiredJobs()

        assertEquals(JobStatus.QUEUED, job.status)
        assertNull(job.lockedUntil)
        assertNull(job.nextAttemptAt)
    }

    @Test
    fun `recoverLeaseExpiredJobs - PROCESSING lease 만료 시 locked_until 해제 후 즉시 재폴링`() {
        val job = createJob(status = JobStatus.PROCESSING).apply {
            lockedUntil = Instant.now().minusSeconds(10)
            externalJobId = "ext-123"
        }
        whenever(jobRepository.findLeaseExpired(any())).thenReturn(listOf(job))
        whenever(jobRepository.save(any<Job>())).thenReturn(job)

        jobService.recoverLeaseExpiredJobs()

        assertEquals(JobStatus.PROCESSING, job.status)
        assertNull(job.lockedUntil)
        assertNotNull(job.pollDueAt)
    }

    @Test
    fun `requeueRetryWaitJobs - next_attempt_at 지난 RETRY_WAIT 잡을 QUEUED로 전환`() {
        val job = createJob(status = JobStatus.RETRY_WAIT).apply {
            nextAttemptAt = Instant.now().minusSeconds(30)
        }
        whenever(jobRepository.findRetryWaitReadyToQueue(any())).thenReturn(listOf(job))
        whenever(jobRepository.saveAll(any<List<Job>>())).thenReturn(listOf(job))

        jobService.requeueRetryWaitJobs()

        assertEquals(JobStatus.QUEUED, job.status)
        assertNull(job.nextAttemptAt)
    }

    @Test
    fun `onSubmitSkipped - QUEUED 복귀 후 nextAttemptAt 설정으로 즉시 재claim 방지`() {
        val job = createJob(status = JobStatus.DISPATCHING)
        whenever(jobRepository.findById(job.id!!)).thenReturn(Optional.of(job))
        whenever(jobRepository.save(any<Job>())).thenReturn(job)

        jobService.onSubmitSkipped(job.id!!)

        assertEquals(JobStatus.QUEUED, job.status)
        assertNull(job.lockedUntil)
        assertNotNull(job.nextAttemptAt)
        assertEquals(0, job.attemptCount)
    }



    private fun createJob(
        status: JobStatus = JobStatus.QUEUED,
        attemptCount: Int = 0,
        maxAttempts: Int = 3,
        pollFailureCount: Int = 0,
        maxPollFailures: Int = 5
    ): Job = Job(
        id = UUID.randomUUID(),
        payloadHash = "abc123",
        imageUrl = "https://example.com/img.jpg",
        status = status,
        attemptCount = attemptCount,
        maxAttempts = maxAttempts,
        pollFailureCount = pollFailureCount,
        maxPollFailures = maxPollFailures
    )
}
