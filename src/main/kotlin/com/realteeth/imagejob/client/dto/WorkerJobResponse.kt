package com.realteeth.imagejob.client.dto

import com.fasterxml.jackson.annotation.JsonIgnoreProperties

@JsonIgnoreProperties(ignoreUnknown = true)
data class WorkerJobResponse(
    val jobId: String,
    val status: String,
    val result: String? = null
) {
    fun isProcessing(): Boolean = status == "PROCESSING"
    fun isCompleted(): Boolean = status == "COMPLETED"
    fun isFailed(): Boolean = status == "FAILED"
}
