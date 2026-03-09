package com.realteeth.imagejob.worker

import com.realteeth.imagejob.client.MockWorkerClient
import com.realteeth.imagejob.client.dto.WorkerJobResponse
import com.realteeth.imagejob.domain.Job
import com.realteeth.imagejob.domain.JobStatus
import com.realteeth.imagejob.service.JobService
import org.junit.jupiter.api.Test
import org.mockito.kotlin.*
import org.springframework.web.reactive.function.client.WebClientResponseException
import java.util.UUID

/**
 * PollWorker 단위 테스트.
 * HTTP 응답 코드별 올바른 서비스 메서드 호출 여부 검증.
 * 특히 429를 재시도 대상으로 처리하는지(SubmitWorker와 동일 정책) 확인.
 */
class PollWorkerTest {

    private val jobService: JobService = mock()
    private val mockWorkerClient: MockWorkerClient = mock()
    private val pollWorker = PollWorker(jobService, mockWorkerClient)

    @Test
    fun `정상 응답이면 onPollResult 호출`() {
        val job = createJob()
        val response = WorkerJobResponse(jobId = job.externalJobId!!, status = "COMPLETED", result = "처리 결과")
        whenever(jobService.claimProcessingJobs()).thenReturn(listOf(job))
        whenever(mockWorkerClient.poll(job.externalJobId!!)).thenReturn(response)

        pollWorker.run()

        verify(jobService).onPollResult(job.id!!, "COMPLETED", "처리 결과")
        verify(jobService, never()).onPollRetryableFailure(any(), any(), any())
        verify(jobService, never()).onPollNonRetryableFailure(any(), any(), any())
    }

    @Test
    fun `PROCESSING 응답이면 onPollResult(PROCESSING) 호출`() {
        val job = createJob()
        val response = WorkerJobResponse(jobId = job.externalJobId!!, status = "PROCESSING", result = null)
        whenever(jobService.claimProcessingJobs()).thenReturn(listOf(job))
        whenever(mockWorkerClient.poll(job.externalJobId!!)).thenReturn(response)

        pollWorker.run()

        verify(jobService).onPollResult(job.id!!, "PROCESSING", null)
    }

    @Test
    fun `4xx 오류이면 onPollNonRetryableFailure 호출`() {
        val job = createJob()
        val ex = WebClientResponseException(404, "Not Found", null, null, null)
        whenever(jobService.claimProcessingJobs()).thenReturn(listOf(job))
        whenever(mockWorkerClient.poll(job.externalJobId!!)).thenThrow(ex)

        pollWorker.run()

        verify(jobService).onPollNonRetryableFailure(eq(job.id!!), eq("CLIENT_ERROR_404"), any())
        verify(jobService, never()).onPollRetryableFailure(any(), any(), any())
    }

    @Test
    fun `5xx 오류이면 onPollRetryableFailure 호출`() {
        val job = createJob()
        val ex = WebClientResponseException(503, "Service Unavailable", null, null, null)
        whenever(jobService.claimProcessingJobs()).thenReturn(listOf(job))
        whenever(mockWorkerClient.poll(job.externalJobId!!)).thenThrow(ex)

        pollWorker.run()

        verify(jobService).onPollRetryableFailure(eq(job.id!!), eq("HTTP_503"), any())
        verify(jobService, never()).onPollNonRetryableFailure(any(), any(), any())
    }

    @Test
    fun `429 오류이면 onPollRetryableFailure 호출 (Submit과 동일 정책)`() {
        val job = createJob()
        val ex = WebClientResponseException(429, "Too Many Requests", null, null, null)
        whenever(jobService.claimProcessingJobs()).thenReturn(listOf(job))
        whenever(mockWorkerClient.poll(job.externalJobId!!)).thenThrow(ex)

        pollWorker.run()

        verify(jobService).onPollRetryableFailure(eq(job.id!!), eq("HTTP_429"), any())
        verify(jobService, never()).onPollNonRetryableFailure(any(), any(), any())
    }

    @Test
    fun `네트워크 오류이면 onPollRetryableFailure 호출`() {
        val job = createJob()
        val ex = RuntimeException("Connection refused")
        whenever(jobService.claimProcessingJobs()).thenReturn(listOf(job))
        whenever(mockWorkerClient.poll(job.externalJobId!!)).thenThrow(ex)

        pollWorker.run()

        verify(jobService).onPollRetryableFailure(eq(job.id!!), eq("POLL_FAILED"), any())
    }

    @Test
    fun `claimProcessingJobs가 비어있으면 아무것도 처리하지 않음`() {
        whenever(jobService.claimProcessingJobs()).thenReturn(emptyList())

        pollWorker.run()

        verify(mockWorkerClient, never()).poll(any())
    }

    @Test
    fun `externalJobId가 없는 잡은 건너뜀`() {
        val jobWithoutExternalId = Job(
            id = UUID.randomUUID(),
            payloadHash = "abc",
            imageUrl = "https://example.com/img.jpg",
            status = JobStatus.PROCESSING,
            externalJobId = null
        )
        whenever(jobService.claimProcessingJobs()).thenReturn(listOf(jobWithoutExternalId))

        pollWorker.run()

        verify(mockWorkerClient, never()).poll(any())
        verify(jobService, never()).onPollResult(any(), any(), anyOrNull())
    }

    @Test
    fun `한 잡 실패해도 다음 잡은 계속 처리`() {
        val job1 = createJob(externalJobId = "ext-001")
        val job2 = createJob(externalJobId = "ext-002")
        val response = WorkerJobResponse(jobId = "ext-002", status = "COMPLETED", result = null)
        val ex = WebClientResponseException(503, "Error", null, null, null)

        whenever(jobService.claimProcessingJobs()).thenReturn(listOf(job1, job2))
        whenever(mockWorkerClient.poll("ext-001")).thenThrow(ex)
        whenever(mockWorkerClient.poll("ext-002")).thenReturn(response)

        pollWorker.run()

        verify(jobService).onPollRetryableFailure(eq(job1.id!!), any(), any())
        verify(jobService).onPollResult(job2.id!!, "COMPLETED", null)
    }

    private fun createJob(externalJobId: String = "ext-123"): Job = Job(
        id = UUID.randomUUID(),
        payloadHash = "abc",
        imageUrl = "https://example.com/img.jpg",
        status = JobStatus.PROCESSING,
        externalJobId = externalJobId
    )
}
