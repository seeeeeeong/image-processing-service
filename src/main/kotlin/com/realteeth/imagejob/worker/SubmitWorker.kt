package com.realteeth.imagejob.worker

import com.realteeth.imagejob.client.MockWorkerClient
import com.realteeth.imagejob.service.JobService
import io.github.resilience4j.bulkhead.BulkheadFullException
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClientResponseException

@Component
class SubmitWorker(
    private val jobService: JobService,
    private val mockWorkerClient: MockWorkerClient
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelay = 5000)
    fun run() {
        val jobs = try {
            jobService.claimQueuedJobs()
        } catch (e: Exception) {
            log.error("Failed to claim queued jobs", e)
            return
        }

        if (jobs.isEmpty()) return
        log.debug("Claimed {} queued jobs for submission", jobs.size)

        jobs.forEach { job ->
            val jobId = job.id!!
            try {
                val response = mockWorkerClient.submit(job.imageUrl)
                jobService.onSubmitSuccess(jobId, response.jobId, response.status)
            } catch (e: WebClientResponseException) {
                if (e.statusCode.is4xxClientError &&
                    e.statusCode.value() != 429
                ) {
                    jobService.onSubmitNonRetryableFailure(
                        jobId,
                        "CLIENT_ERROR_${e.statusCode.value()}",
                        e.message
                    )
                } else {
                    jobService.onSubmitFailure(jobId, "HTTP_${e.statusCode.value()}", e.message)
                }
            } catch (e: BulkheadFullException) {
                log.warn("Bulkhead full for jobId={}, releasing back to QUEUED without consuming attempt", jobId)
                jobService.onSubmitSkipped(jobId)
            } catch (e: Exception) {
                log.error("Submit failed for jobId={}: {}", jobId, e.message)
                jobService.onSubmitFailure(jobId, "SUBMIT_FAILED", e.message)
            }
        }
    }
}
