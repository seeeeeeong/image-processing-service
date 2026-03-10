package com.realteeth.imagejob.client

import com.realteeth.imagejob.config.ApiKeyProvider
import com.realteeth.imagejob.config.AppProperties
import com.realteeth.imagejob.config.MockWorkerProperties
import com.realteeth.imagejob.config.WorkerProperties
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.web.reactive.function.client.WebClient

/**
 * MockWorkerClient HTTP 계약 검증 테스트.
 *
 * 검증 대상:
 * - submit: POST /mock/process, X-API-KEY 헤더, imageUrl 바디 포함
 * - poll:   GET  /mock/process/{jobId}, X-API-KEY 헤더
 *
 * Resilience4j 어노테이션(@Retry, @Bulkhead)은 Spring AOP 없이는 동작하지 않으므로
 * 순수 HTTP 계약만 검증한다.
 */
class MockWorkerClientContractTest {

    private lateinit var mockWebServer: MockWebServer
    private lateinit var client: MockWorkerClient

    @BeforeEach
    fun setUp() {
        mockWebServer = MockWebServer()
        mockWebServer.start()

        val webClient = WebClient.builder()
            .baseUrl("http://localhost:${mockWebServer.port}")
            .defaultHeader("Content-Type", "application/json")
            .build()

        val props = AppProperties(
            mockWorker = MockWorkerProperties(
                baseUrl = "http://localhost:${mockWebServer.port}",
                apiKey = "test-api-key"
            ),
            worker = WorkerProperties()
        )
        val apiKeyProvider = ApiKeyProvider(props)

        client = MockWorkerClient(webClient, apiKeyProvider)
    }

    @AfterEach
    fun tearDown() {
        mockWebServer.shutdown()
    }

    @Test
    fun `submit은 POST mock-process에 X-API-KEY 헤더와 imageUrl 바디를 전송한다`() {
        mockWebServer.enqueue(
            MockResponse()
                .setBody("""{"jobId":"ext-1","status":"PROCESSING"}""")
                .addHeader("Content-Type", "application/json")
        )

        client.submit("https://example.com/image.jpg")

        val request = mockWebServer.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/mock/process", request.path)
        assertEquals("test-api-key", request.getHeader("X-API-KEY"))
        assertTrue(request.body.readUtf8().contains("imageUrl"))
    }

    @Test
    fun `poll은 GET mock-process-jobId에 X-API-KEY 헤더를 전송한다`() {
        mockWebServer.enqueue(
            MockResponse()
                .setBody("""{"jobId":"ext-abc","status":"COMPLETED","result":"done"}""")
                .addHeader("Content-Type", "application/json")
        )

        client.poll("ext-abc")

        val request = mockWebServer.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/mock/process/ext-abc", request.path)
        assertEquals("test-api-key", request.getHeader("X-API-KEY"))
    }
}
