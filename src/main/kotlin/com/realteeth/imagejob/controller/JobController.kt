package com.realteeth.imagejob.controller

import com.realteeth.imagejob.dto.CreateJobRequest
import com.realteeth.imagejob.dto.JobResponse
import com.realteeth.imagejob.dto.JobSummaryResponse
import com.realteeth.imagejob.dto.PagedResponse
import com.realteeth.imagejob.service.JobService
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.*
import java.util.UUID

@Validated
@RestController
@RequestMapping("/api/v1/jobs")
class JobController(private val jobService: JobService) {

    @PostMapping
    fun createJob(
        @Valid @RequestBody request: CreateJobRequest,
        @RequestHeader(value = "Idempotency-Key", required = false) idempotencyKey: String?
    ): ResponseEntity<JobResponse> {
        val (response, created) = jobService.createJob(request, idempotencyKey)
        return if (created) {
            ResponseEntity.status(HttpStatus.CREATED).body(response)
        } else {
            ResponseEntity.ok(response)
        }
    }

    @GetMapping("/{jobId}")
    fun getJob(@PathVariable jobId: UUID): ResponseEntity<JobResponse> =
        ResponseEntity.ok(jobService.getJob(jobId))

    @GetMapping
    fun listJobs(
        @RequestParam(defaultValue = "0") @Min(0) page: Int,
        @RequestParam(defaultValue = "20") @Min(1) @Max(100) size: Int
    ): ResponseEntity<PagedResponse<JobSummaryResponse>> =
        ResponseEntity.ok(jobService.listJobs(page, size))
}
