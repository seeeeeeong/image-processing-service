package com.realteeth.imagejob.dto

import com.realteeth.imagejob.domain.Job
import com.realteeth.imagejob.domain.JobStatus
import java.time.Instant
import java.util.UUID

data class JobResponse(
    val jobId: UUID,
    val status: JobStatus,
    val imageUrl: String,
    val result: String?,
    val error: ErrorDetail?,
    val attemptCount: Int,
    val createdAt: Instant,
    val updatedAt: Instant
) {
    data class ErrorDetail(
        val code: String,
        val message: String?
    )

    companion object {
        fun from(job: Job): JobResponse = JobResponse(
            jobId = job.id!!,
            status = job.status,
            imageUrl = job.imageUrl,
            result = job.result,
            error = if (job.lastErrorCode != null) {
                ErrorDetail(code = job.lastErrorCode!!, message = job.lastErrorMessage)
            } else null,
            attemptCount = job.attemptCount,
            createdAt = job.createdAt,
            updatedAt = job.updatedAt
        )
    }
}

data class JobSummaryResponse(
    val jobId: UUID,
    val status: JobStatus,
    val imageUrl: String,
    val attemptCount: Int,
    val createdAt: Instant,
    val updatedAt: Instant
) {
    companion object {
        fun from(job: Job): JobSummaryResponse = JobSummaryResponse(
            jobId = job.id!!,
            status = job.status,
            imageUrl = job.imageUrl,
            attemptCount = job.attemptCount,
            createdAt = job.createdAt,
            updatedAt = job.updatedAt
        )
    }
}

data class PagedResponse<T>(
    val content: List<T>,
    val page: Int,
    val size: Int,
    val totalElements: Long,
    val totalPages: Int
)
