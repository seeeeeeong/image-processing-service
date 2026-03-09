package com.realteeth.imagejob.client

import com.realteeth.imagejob.client.dto.WorkerJobResponse
import com.realteeth.imagejob.client.dto.WorkerProcessRequest
import com.realteeth.imagejob.config.AppProperties
import io.github.resilience4j.bulkhead.annotation.Bulkhead
import io.github.resilience4j.retry.annotation.Retry
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.bodyToMono

@Component
class MockWorkerClient(
    private val webClient: WebClient,
    private val props: AppProperties
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Retry(name = "mockWorkerSubmit")
    @Bulkhead(name = "mockWorkerSubmit", type = Bulkhead.Type.SEMAPHORE)
    fun submit(imageUrl: String): WorkerJobResponse {
        log.debug("Submitting job to Mock Worker: imageUrl={}", imageUrl)
        return webClient.post()
            .uri("/process")
            .header("X-API-KEY", props.mockWorker.apiKey)
            .bodyValue(WorkerProcessRequest(imageUrl = imageUrl))
            .retrieve()
            .bodyToMono<WorkerJobResponse>()
            .block()!!
    }

    @Retry(name = "mockWorkerPoll")
    @Bulkhead(name = "mockWorkerPoll", type = Bulkhead.Type.SEMAPHORE)
    fun poll(externalJobId: String): WorkerJobResponse {
        log.debug("Polling job from Mock Worker: externalJobId={}", externalJobId)
        return webClient.get()
            .uri("/process/{jobId}", externalJobId)
            .header("X-API-KEY", props.mockWorker.apiKey)
            .retrieve()
            .bodyToMono<WorkerJobResponse>()
            .block()!!
    }
}
