package com.realteeth.imagejob.config

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.boot.DefaultApplicationArguments
import org.springframework.web.reactive.function.client.WebClient

/**
 * ApiKeyInitializer 계약 검증 테스트.
 *
 * 검증 대상:
 * - API 키가 없으면 POST /mock/auth/issue-key 호출, candidateName/email 바디 포함, 발급된 키 저장
 * - API 키가 이미 설정되어 있으면 아무 HTTP 호출도 하지 않음
 */
class ApiKeyInitializerTest {

    private lateinit var mockWebServer: MockWebServer
    private lateinit var webClient: WebClient

    @BeforeEach
    fun setUp() {
        mockWebServer = MockWebServer()
        mockWebServer.start()
        webClient = WebClient.builder()
            .baseUrl("http://localhost:${mockWebServer.port}")
            .defaultHeader("Content-Type", "application/json")
            .build()
    }

    @AfterEach
    fun tearDown() {
        mockWebServer.shutdown()
    }

    private fun makeProps(apiKey: String = "") = AppProperties(
        mockWorker = MockWorkerProperties(
            baseUrl = "http://localhost:${mockWebServer.port}",
            apiKey = apiKey,
            candidateName = "tester",
            email = "test@example.com"
        ),
        worker = WorkerProperties()
    )

    @Test
    fun `API 키가 없으면 issue-key를 POST로 호출하고 발급된 키를 저장한다`() {
        mockWebServer.enqueue(
            MockResponse()
                .setBody("""{"apiKey":"mock_issued_key"}""")
                .addHeader("Content-Type", "application/json")
        )

        val props = makeProps(apiKey = "")
        val apiKeyProvider = ApiKeyProvider(props)
        val initializer = ApiKeyInitializer(apiKeyProvider, webClient, props)

        initializer.run(DefaultApplicationArguments())

        val request = mockWebServer.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/mock/auth/issue-key", request.path)
        val body = request.body.readUtf8()
        assertTrue(body.contains("candidateName"), "body should contain candidateName: $body")
        assertTrue(body.contains("tester"), "body should contain candidateName value: $body")
        assertEquals("mock_issued_key", apiKeyProvider.apiKey)
    }

    @Test
    fun `API 키가 이미 설정되어 있으면 issue-key를 호출하지 않는다`() {
        val props = makeProps(apiKey = "already-set-key")
        val apiKeyProvider = ApiKeyProvider(props)
        val initializer = ApiKeyInitializer(apiKeyProvider, webClient, props)

        initializer.run(DefaultApplicationArguments())

        assertEquals(0, mockWebServer.requestCount)
        assertEquals("already-set-key", apiKeyProvider.apiKey)
    }
}
