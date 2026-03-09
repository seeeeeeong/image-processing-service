package com.realteeth.imagejob.dto

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern

data class CreateJobRequest(
    @field:NotBlank(message = "imageUrl은 필수입니다")
    @field:Pattern(
        regexp = "^https?://.+",
        message = "imageUrl은 http 또는 https URL이어야 합니다"
    )
    val imageUrl: String
)
