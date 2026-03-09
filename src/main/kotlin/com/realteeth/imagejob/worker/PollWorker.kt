package com.realteeth.imagejob.worker

import com.realteeth.imagejob.client.MockWorkerClient
import com.realteeth.imagejob.service.JobService
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClientResponseException

@Component
class PollWorker(
    private val jobService: JobService,
    private val mockWorkerClient: MockWorkerClient
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelay = 10000)
    fun run() {
        val jobs = try {
            jobService.claimProcessingJobs()
        } catch (e: Exception) {
            log.error("Failed to claim processing jobs", e)
            return
        }

        if (jobs.isEmpty()) return
        log.debug("Claimed {} processing jobs for poll", jobs.size)

        jobs.forEach { job ->
            val jobId = job.id!!
            val externalJobId = job.externalJobId ?: run {
                log.error("PROCESSING job has no externalJobId: jobId={}", jobId)
                return@forEach
            }

            try {
                val response = mockWorkerClient.poll(externalJobId)
                jobService.onPollResult(jobId, response.status, response.result)
            } catch (e: WebClientResponseException) {
                if (e.statusCode.is4xxClientError && e.statusCode.value() != 429) {
                    jobService.onPollNonRetryableFailure(
                        jobId,
                        "CLIENT_ERROR_${e.statusCode.value()}",
                        e.message
                    )
                } else {
                    jobService.onPollRetryableFailure(jobId, "HTTP_${e.statusCode.value()}", e.message)
                }
            } catch (e: Exception) {
                log.error("Poll failed for jobId={}: {}", jobId, e.message)
                jobService.onPollRetryableFailure(jobId, "POLL_FAILED", e.message)
            }
        }
    }
}
