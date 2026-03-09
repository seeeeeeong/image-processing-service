package com.realteeth.imagejob.service

import com.realteeth.imagejob.repository.JobRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.UUID

@Component
class JobInsertHelper(private val jobRepository: JobRepository) {

    private val log = LoggerFactory.getLogger(javaClass)

    fun tryInsert(
        idempotencyKey: String?,
        payloadHash: String,
        imageUrl: String,
        maxAttempts: Int,
        maxPollFailures: Int
    ): UUID? {
        val id = UUID.randomUUID()
        val inserted = jobRepository.insertIfNotExists(
            id = id.toString(),
            idempotencyKey = idempotencyKey,
            payloadHash = payloadHash,
            imageUrl = imageUrl,
            maxAttempts = maxAttempts,
            maxPollFailures = maxPollFailures
        )
        return if (inserted > 0) {
            log.info("Inserted new job: jobId={}", id)
            id
        } else {
            log.debug("INSERT skipped due to conflict: payloadHash={}", payloadHash)
            null
        }
    }
}
