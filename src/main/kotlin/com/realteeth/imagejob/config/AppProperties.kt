package com.realteeth.imagejob.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "app")
data class AppProperties(
    val mockWorker: MockWorkerProperties,
    val worker: WorkerProperties
)

data class MockWorkerProperties(
    val baseUrl: String,
    val apiKey: String,
    val connectTimeoutSeconds: Long = 10,
    val readTimeoutSeconds: Long = 30
)

data class WorkerProperties(
    val submitBatchSize: Int = 5,
    val pollBatchSize: Int = 10,
    val leaseSeconds: Long = 90,
    val maxAttempts: Int = 3,
    val maxPollFailures: Int = 5,
    val pollInitialDelaySeconds: Long = 5,
    val pollBackoffMultiplier: Double = 2.0,
    val pollMaxDelaySeconds: Long = 60
)
