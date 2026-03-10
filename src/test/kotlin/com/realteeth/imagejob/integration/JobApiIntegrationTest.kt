package com.realteeth.imagejob.integration

import com.fasterxml.jackson.databind.ObjectMapper
import com.realteeth.imagejob.domain.JobStatus
import com.realteeth.imagejob.dto.CreateJobRequest
import com.realteeth.imagejob.dto.JobResponse
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.MySQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors

/**
 * 잡 API 통합 테스트.
 *
 * 인프라:
 * - Testcontainers MySQL 8.0: 실제 DB 환경에서 SQL init 스크립트, 인덱스, 생성 컬럼 등 검증
 * - MockWebServer (OkHttp): Mock Worker HTTP 응답을 로컬에서 시뮬레이션
 * - @DynamicPropertySource: 컨테이너 포트를 Spring 프로퍼티에 동적으로 주입
 *
 * application-test.yml:
 * - 스케줄러 비활성화: 백그라운드 워커가 테스트 데이터를 변경하지 않도록
 * - Resilience4j 재시도 1회: 테스트 속도 향상
 *
 * @TestMethodOrder: 같은 DB를 공유하므로 테스트 순서를 고정해 선행 데이터 의존성 관리.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class JobApiIntegrationTest {

    @Autowired
    lateinit var restTemplate: TestRestTemplate

    @Autowired
    lateinit var objectMapper: ObjectMapper

    companion object {
        @Container
        val mysql = MySQLContainer<Nothing>("mysql:8.0").apply {
            withDatabaseName("imagejob_test")
            withUsername("test")
            withPassword("test")
        }

        lateinit var mockWebServer: MockWebServer

        @BeforeAll
        @JvmStatic
        fun startMockServer() {
            mockWebServer = MockWebServer()
            mockWebServer.start()
        }

        @AfterAll
        @JvmStatic
        fun stopMockServer() {
            mockWebServer.shutdown()
        }

        @DynamicPropertySource
        @JvmStatic
        fun overrideProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") {
                "${mysql.jdbcUrl}?serverTimezone=UTC&useSSL=false&allowPublicKeyRetrieval=true"
            }
            registry.add("spring.datasource.username", mysql::getUsername)
            registry.add("spring.datasource.password", mysql::getPassword)
            registry.add("app.mock-worker.base-url") {
                "http://localhost:${mockWebServer.port}"
            }
            registry.add("app.mock-worker.api-key") { "test-api-key" }
        }
    }

    @Test
    @Order(1)
    fun `POST jobs - 정상 등록 시 201 반환`() {
        val response = restTemplate.postForEntity(
            "/api/v1/jobs",
            HttpEntity(CreateJobRequest("https://example.com/image.jpg")),
            JobResponse::class.java
        )

        assertEquals(HttpStatus.CREATED, response.statusCode)
        assertNotNull(response.body?.jobId)
        assertEquals(JobStatus.QUEUED, response.body?.status)
    }

    @Test
    @Order(2)
    fun `POST jobs - Idempotency-Key 동일하면 200으로 기존 잡 반환`() {
        val key = UUID.randomUUID().toString()
        val headers = HttpHeaders().apply { set("Idempotency-Key", key) }
        val request = HttpEntity(CreateJobRequest("https://example.com/dedup.jpg"), headers)

        val first = restTemplate.postForEntity("/api/v1/jobs", request, JobResponse::class.java)
        val second = restTemplate.postForEntity("/api/v1/jobs", request, JobResponse::class.java)

        assertEquals(HttpStatus.CREATED, first.statusCode)
        assertEquals(HttpStatus.OK, second.statusCode)
        assertEquals(first.body?.jobId, second.body?.jobId)
    }

    @Test
    @Order(3)
    fun `POST jobs - Idempotency-Key가 달라도 활성 동일 imageUrl이면 active dedup이 우선한다`() {
        val firstHeaders = HttpHeaders().apply { set("Idempotency-Key", UUID.randomUUID().toString()) }
        val secondHeaders = HttpHeaders().apply { set("Idempotency-Key", UUID.randomUUID().toString()) }
        val imageUrl = "https://example.com/active-dedup-priority.jpg"

        val first = restTemplate.postForEntity(
            "/api/v1/jobs",
            HttpEntity(CreateJobRequest(imageUrl), firstHeaders),
            JobResponse::class.java
        )
        val second = restTemplate.postForEntity(
            "/api/v1/jobs",
            HttpEntity(CreateJobRequest(imageUrl), secondHeaders),
            JobResponse::class.java
        )

        assertEquals(HttpStatus.CREATED, first.statusCode)
        assertEquals(HttpStatus.OK, second.statusCode)
        assertEquals(first.body?.jobId, second.body?.jobId)
    }

    @Test
    @Order(4)
    fun `POST jobs - 동일 imageUrl 중복 요청 시 기존 잡 반환`() {
        val request = CreateJobRequest("https://example.com/same-image-dedup-test.jpg")

        val first = restTemplate.postForEntity("/api/v1/jobs", HttpEntity(request), JobResponse::class.java)
        val second = restTemplate.postForEntity("/api/v1/jobs", HttpEntity(request), JobResponse::class.java)

        assertEquals(HttpStatus.CREATED, first.statusCode)
        assertEquals(HttpStatus.OK, second.statusCode)
        assertEquals(first.body?.jobId, second.body?.jobId)
    }

    @Test
    @Order(5)
    fun `POST jobs - 동시에 동일 imageUrl 요청해도 단일 job만 생성`() {
        val imageUrl = "https://example.com/concurrent-${UUID.randomUUID()}.jpg"
        val executor = Executors.newFixedThreadPool(10)

        val futures = (1..10).map {
            CompletableFuture.supplyAsync({
                restTemplate.postForEntity(
                    "/api/v1/jobs",
                    HttpEntity(CreateJobRequest(imageUrl)),
                    JobResponse::class.java
                )
            }, executor)
        }

        val responses = futures.map { it.get() }


        responses.forEach {
            assertTrue(it.statusCode.is2xxSuccessful, "Expected 2xx but got ${it.statusCode}: ${it.body}")
        }

        val jobIds = responses.map {
            assertNotNull(it.body?.jobId, "Expected non-null jobId in response: ${it.body}")
            it.body?.jobId
        }.filterNotNull().toSet()

        assertEquals(1, jobIds.size)

        executor.shutdown()
    }

    @Test
    @Order(6)
    fun `POST jobs - 서로 다른 Idempotency-Key로 동시에 요청해도 활성 job은 하나만 유지`() {
        val imageUrl = "https://example.com/concurrent-idempotency-${UUID.randomUUID()}.jpg"
        val executor = Executors.newFixedThreadPool(10)

        val futures = (1..10).map {
            CompletableFuture.supplyAsync({
                val headers = HttpHeaders().apply { set("Idempotency-Key", UUID.randomUUID().toString()) }
                restTemplate.postForEntity(
                    "/api/v1/jobs",
                    HttpEntity(CreateJobRequest(imageUrl), headers),
                    JobResponse::class.java
                )
            }, executor)
        }

        val responses = futures.map { it.get() }

        responses.forEach {
            assertTrue(it.statusCode.is2xxSuccessful, "Expected 2xx but got ${it.statusCode}: ${it.body}")
        }

        val jobIds = responses.map {
            assertNotNull(it.body?.jobId, "Expected non-null jobId in response: ${it.body}")
            it.body?.jobId
        }.filterNotNull().toSet()

        assertEquals(1, jobIds.size)

        executor.shutdown()
    }

    @Test
    @Order(7)
    fun `GET jobs-id - 존재하지 않는 jobId는 404`() {
        val response = restTemplate.getForEntity(
            "/api/v1/jobs/${UUID.randomUUID()}",
            String::class.java
        )
        assertEquals(HttpStatus.NOT_FOUND, response.statusCode)
    }

    @Test
    @Order(8)
    fun `GET jobs - 목록 조회 정상 동작`() {
        val response = restTemplate.getForEntity(
            "/api/v1/jobs?page=0&size=10",
            String::class.java
        )
        assertEquals(HttpStatus.OK, response.statusCode)
        assertTrue(response.body?.contains("content") == true)
    }

    @Test
    @Order(9)
    fun `요청 검증 - imageUrl 없으면 400`() {
        val response = restTemplate.postForEntity(
            "/api/v1/jobs",
            HttpEntity(mapOf("imageUrl" to "")),
            String::class.java
        )
        assertEquals(HttpStatus.BAD_REQUEST, response.statusCode)
    }
}
