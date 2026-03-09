package com.realteeth.imagejob.worker

import com.realteeth.imagejob.service.JobService
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
class LeaseRecoveryWorker(
    private val jobService: JobService
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelay = 60_000)
    fun recoverLeaseExpired() {
        try {
            jobService.recoverLeaseExpiredJobs()
        } catch (e: Exception) {
            log.error("Lease recovery failed", e)
        }
    }

    @Scheduled(fixedDelay = 15_000)
    fun requeueRetryWait() {
        try {
            jobService.requeueRetryWaitJobs()
        } catch (e: Exception) {
            log.error("Requeue retry-wait jobs failed", e)
        }
    }
}
