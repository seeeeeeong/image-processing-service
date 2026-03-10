package com.realteeth.imagejob.config

import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.bodyToMono

/**
 * 앱 시작 시 Mock Worker API 키가 없으면 자동으로 발급합니다.
 * MOCK_WORKER_API_KEY 환경변수가 설정되어 있으면 건너뜁니다.
 */
@Component
class ApiKeyInitializer(
    private val apiKeyProvider: ApiKeyProvider,
    private val webClient: WebClient,
    private val props: AppProperties
) : ApplicationRunner {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun run(args: ApplicationArguments) {
        if (apiKeyProvider.apiKey.isNotBlank()) {
            log.info("Mock Worker API key already configured, skipping bootstrap")
            return
        }

        log.info("MOCK_WORKER_API_KEY not set — issuing new key from Mock Worker (candidateName={})", props.mockWorker.candidateName)

        val response = webClient.post()
            .uri("/mock/auth/issue-key")
            .bodyValue(
                mapOf(
                    "candidateName" to props.mockWorker.candidateName,
                    "email" to props.mockWorker.email
                )
            )
            .retrieve()
            .bodyToMono<Map<String, String>>()
            .block() ?: error("Empty response from /mock/auth/issue-key")

        val key = response["apiKey"] ?: error("No 'apiKey' field in response: $response")
        apiKeyProvider.apiKey = key
        log.info("Mock Worker API key issued successfully")
    }
}
