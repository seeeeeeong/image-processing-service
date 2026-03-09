package com.realteeth.imagejob.worker

import com.realteeth.imagejob.client.MockWorkerClient
import com.realteeth.imagejob.client.dto.WorkerJobResponse
import com.realteeth.imagejob.domain.Job
import com.realteeth.imagejob.domain.JobStatus
import com.realteeth.imagejob.service.JobService
import io.github.resilience4j.bulkhead.BulkheadFullException
import org.junit.jupiter.api.Test
import org.mockito.kotlin.*
import org.springframework.web.reactive.function.client.WebClientResponseException
import java.util.UUID

/**
 * SubmitWorker 단위 테스트.
 * HTTP 응답 코드별 올바른 서비스 메서드 호출 여부 및
 * BulkheadFullException 처리(attemptCount 미소모) 검증.
 */
class SubmitWorkerTest {

    private val jobService: JobService = mock()
    private val mockWorkerClient: MockWorkerClient = mock()
    private val submitWorker = SubmitWorker(jobService, mockWorkerClient)

    @Test
    fun `정상 응답이면 onSubmitSuccess 호출`() {
        val job = createJob()
        val response = WorkerJobResponse(jobId = "ext-123", status = "PROCESSING")
        whenever(jobService.claimQueuedJobs()).thenReturn(listOf(job))
        whenever(mockWorkerClient.submit(any())).thenReturn(response)

        submitWorker.run()

        verify(jobService).onSubmitSuccess(job.id!!, "ext-123", "PROCESSING")
        verify(jobService, never()).onSubmitFailure(any(), any(), any())
    }

    @Test
    fun `4xx 오류이면 onSubmitNonRetryableFailure 호출`() {
        val job = createJob()
        val ex = WebClientResponseException(400, "Bad Request", null, null, null)
        whenever(jobService.claimQueuedJobs()).thenReturn(listOf(job))
        whenever(mockWorkerClient.submit(any())).thenThrow(ex)

        submitWorker.run()

        verify(jobService).onSubmitNonRetryableFailure(eq(job.id!!), any(), any())
        verify(jobService, never()).onSubmitSuccess(any(), any(), any())
    }

    @Test
    fun `5xx 오류이면 onSubmitFailure 호출 (재시도 소진)`() {
        val job = createJob()
        val ex = WebClientResponseException(500, "Internal Server Error", null, null, null)
        whenever(jobService.claimQueuedJobs()).thenReturn(listOf(job))
        whenever(mockWorkerClient.submit(any())).thenThrow(ex)

        submitWorker.run()

        verify(jobService).onSubmitFailure(eq(job.id!!), any(), any())
    }

    @Test
    fun `Bulkhead 포화 시 onSubmitSkipped 호출 (attemptCount 소모 없음)`() {
        val job = createJob()
        val ex = BulkheadFullException.createBulkheadFullException(
            io.github.resilience4j.bulkhead.Bulkhead.ofDefaults("test")
        )
        whenever(jobService.claimQueuedJobs()).thenReturn(listOf(job))
        whenever(mockWorkerClient.submit(any())).thenThrow(ex)

        submitWorker.run()

        verify(jobService).onSubmitSkipped(job.id!!)
        verify(jobService, never()).onSubmitFailure(any(), any(), any())
        verify(jobService, never()).onSubmitNonRetryableFailure(any(), any(), any())
    }

    @Test
    fun `claimQueuedJobs가 비어있으면 아무것도 처리하지 않음`() {
        whenever(jobService.claimQueuedJobs()).thenReturn(emptyList())

        submitWorker.run()

        verify(mockWorkerClient, never()).submit(any())
    }

    @Test
    fun `한 잡 실패해도 다음 잡은 계속 처리`() {
        val job1 = createJob(imageUrl = "https://example.com/img1.jpg")
        val job2 = createJob(imageUrl = "https://example.com/img2.jpg")
        val response = WorkerJobResponse(jobId = "ext-456", status = "PROCESSING")
        val ex = WebClientResponseException(500, "Error", null, null, null)

        whenever(jobService.claimQueuedJobs()).thenReturn(listOf(job1, job2))
        whenever(mockWorkerClient.submit(job1.imageUrl)).thenThrow(ex)
        whenever(mockWorkerClient.submit(job2.imageUrl)).thenReturn(response)

        submitWorker.run()

        verify(jobService).onSubmitFailure(eq(job1.id!!), any(), any())
        verify(jobService).onSubmitSuccess(job2.id!!, "ext-456", "PROCESSING")
    }

    private fun createJob(imageUrl: String = "https://example.com/img.jpg"): Job = Job(
        id = UUID.randomUUID(),
        payloadHash = "abc",
        imageUrl = imageUrl,
        status = JobStatus.DISPATCHING
    )
}
