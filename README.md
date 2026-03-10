# Image Processing Service

비동기 이미지 처리 요청을 관리하는 Job Queue 서비스입니다.
클라이언트로부터 이미지 처리 요청을 받아 외부 Mock Worker에 위임하고, 폴링 방식으로 결과를 수집합니다.

---

## 실행 방법

### Docker Compose (권장)

```bash
docker-compose up --build
```

- 앱 서버: `http://localhost:8080`
- MySQL은 컨테이너 내부에서 자동 실행됩니다
- **API 키는 앱 시작 시 Mock Worker에서 자동 발급**됩니다 — 별도 설정 불필요

환경변수로 직접 API 키를 주입할 수도 있습니다:

```bash
MOCK_WORKER_API_KEY=mock_xxxx docker-compose up --build
```

### 로컬 실행

MySQL 8.0이 필요합니다.

```bash
# DB 생성
mysql -u root -e "CREATE DATABASE imagejob; CREATE USER 'imagejob'@'localhost' IDENTIFIED BY 'imagejob'; GRANT ALL ON imagejob.* TO 'imagejob'@'localhost';"

# 실행
./gradlew bootRun
```

### 테스트 실행

```bash
./gradlew test
```

> Testcontainers를 사용하므로 Docker가 실행 중이어야 합니다.

---

## API 엔드포인트

| Method | Path | 설명 |
|--------|------|------|
| `POST` | `/api/v1/jobs` | 이미지 처리 요청 |
| `GET` | `/api/v1/jobs/{jobId}` | 작업 상태 및 결과 조회 |
| `GET` | `/api/v1/jobs?page=0&size=20` | 작업 목록 조회 (페이지네이션) |

### POST /api/v1/jobs

```http
POST /api/v1/jobs
Content-Type: application/json
Idempotency-Key: (선택) 클라이언트 생성 UUID

{
  "imageUrl": "https://example.com/image.jpg"
}
```

응답:
- `201 Created` — 새 작업 생성
- `200 OK` — 중복 요청 (동일 Idempotency-Key 또는 동일 URL의 처리 중 작업 존재)

### GET /api/v1/jobs/{jobId}

성공 시:
```json
{
  "jobId": "uuid",
  "status": "COMPLETED",
  "imageUrl": "https://example.com/image.jpg",
  "result": "...",
  "error": null,
  "attemptCount": 1,
  "createdAt": "2024-01-01T00:00:00Z",
  "updatedAt": "2024-01-01T00:00:10Z"
}
```

실패 시:
```json
{
  "jobId": "uuid",
  "status": "FAILED",
  "imageUrl": "https://example.com/image.jpg",
  "result": null,
  "error": {
    "code": "HTTP_500",
    "message": "Internal Server Error"
  },
  "attemptCount": 3,
  "createdAt": "2024-01-01T00:00:00Z",
  "updatedAt": "2024-01-01T00:00:30Z"
}
```

---

## 설계 설명

### 상태 모델 설계 의도 (4.2)

```
QUEUED ──→ DISPATCHING ──→ PROCESSING ──→ COMPLETED
                │                │
                ↓                ↓
           RETRY_WAIT        DEAD_LETTER
                │
                ↓
             QUEUED (재큐잉)
                │
             FAILED (즉시 실패)
```

| 상태 | 의미 |
|------|------|
| `QUEUED` | Mock Worker 제출 대기 중 |
| `DISPATCHING` | Mock Worker에 제출 시도 중 (락 보유) |
| `PROCESSING` | Mock Worker가 처리 중, 폴링 대기 |
| `RETRY_WAIT` | 제출 실패 후 지수 백오프 대기 중 |
| `COMPLETED` | 처리 완료, result 필드에 결과 저장 |
| `FAILED` | 재시도 불가능한 실패 (4xx 오류, 알 수 없는 상태) |
| `DEAD_LETTER` | 최대 재시도 횟수 소진, 운영자 개입 필요 |

**허용되지 않는 전이:**
- `COMPLETED`, `FAILED`, `DEAD_LETTER` → 어떤 상태로도 전이 불가 (터미널 상태)
- `PROCESSING` → `RETRY_WAIT` 불가 (poll 실패는 pollFailureCount로 관리)
- `QUEUED` → `DISPATCHING` 이외의 상태로 직접 전이 불가

DISPATCHING을 별도 상태로 분리한 이유는 제출 중 서버 크래시 시 lease recovery가 QUEUED로 정확히 복구하기 위해서입니다. RETRY_WAIT를 별도 상태로 둔 이유는 backoff 대기 중에 SubmitWorker가 재획득하지 않도록 하기 위해서입니다.

---

### 실패 처리 전략

```
HTTP 4xx (429 제외) ──→ FAILED (즉시 종료, 재시도 의미 없음)
HTTP 5xx / 429 / 타임아웃 / IOException
    ├─ 재시도 가능 횟수 남음 ──→ RETRY_WAIT (지수 백오프: 2^attempt × 5초, 최대 300초)
    └─ 최대 재시도 소진 ──→ DEAD_LETTER

Bulkhead 초과 ──→ QUEUED 복귀 (attempt 횟수 소모 없음, +10초 backoff)

Poll 실패 (5xx / 타임아웃)
    ├─ pollFailureCount < 5 ──→ PROCESSING 유지, pollDueAt 재스케줄 (지수 백오프, 최대 60초)
    └─ pollFailureCount ≥ 5 ──→ DEAD_LETTER
```

Resilience4j Retry가 HTTP 호출 레벨에서 최대 3회 재시도하고, 그래도 실패하면 애플리케이션 레벨의 `onSubmitFailure`가 `max_attempts`(기본 3) 기준으로 RETRY_WAIT 또는 DEAD_LETTER를 결정합니다.

---

### 중복 요청 처리 전략 (4.1)

두 가지 전략을 계층적으로 적용합니다.

**1. Idempotency-Key 헤더 (명시적 중복 제어)**
클라이언트가 `Idempotency-Key: <uuid>` 헤더를 전달하면, 동일 키로 기존 Job이 존재할 경우 새 작업 없이 기존 응답을 반환합니다. DB UNIQUE 인덱스로 보장합니다.

**2. 콘텐츠 기반 중복 제거 (묵시적 중복 제어)**
Idempotency-Key 없이 동일한 `imageUrl`을 요청하면, 해당 URL의 비터미널 상태(QUEUED~PROCESSING) Job이 이미 존재할 경우 기존 Job을 반환합니다.

구현 핵심: `active_dedup_key` GENERATED COLUMN

```sql
active_dedup_key VARCHAR(64) GENERATED ALWAYS AS (
  CASE WHEN idempotency_key IS NULL
        AND status NOT IN ('COMPLETED', 'FAILED', 'DEAD_LETTER')
       THEN payload_hash
       ELSE NULL
  END
) STORED
```

이 컬럼에 UNIQUE 제약을 걸면, 비터미널 상태의 동일 URL Job은 하나만 존재할 수 있습니다. 작업이 완료되면 이 값이 NULL이 되어 제약이 해제되므로 완료 후 동일 URL 재처리가 가능합니다.

**동시 요청 경쟁 조건:** `INSERT IGNORE`를 사용해 UNIQUE 제약 위반 시 예외 대신 0 rows affected를 반환하고, 이후 기존 레코드를 조회해 반환합니다.

---

### 처리 보장 모델 (4.3)

**At-least-once** 입니다.

근거:

1. **Submit 단계:** Mock Worker에 제출 성공 응답을 받은 직후 `onSubmitSuccess()` 트랜잭션 커밋 전에 프로세스가 종료되면, DB에는 여전히 DISPATCHING 상태로 남습니다. 재시작 후 LeaseRecovery가 이를 QUEUED로 복구하면, 같은 이미지를 Mock Worker에 다시 제출합니다 — **동일 이미지 이중 처리 가능**.

2. **Poll 단계:** Mock Worker가 COMPLETED를 응답했지만 DB 저장 전에 크래시가 나면, 재시작 후 동일 externalJobId로 재폴링합니다. Mock Worker가 멱등적으로 동일 결과를 반환하면 문제없지만, 그렇지 않으면 이중 처리 가능성이 있습니다.

Exactly-once를 달성하려면 Mock Worker와 DB 상태 변경을 분산 트랜잭션 또는 아웃박스 패턴으로 원자적으로 처리해야 하나, 외부 서비스 특성상 현실적이지 않습니다. At-least-once로 설계하되, Mock Worker 측에서 멱등성을 보장한다고 가정합니다.

---

### 서버 재시작 시 동작 (4.4)

| 재시작 시점의 상태 | 재시작 후 동작 |
|------------------|--------------|
| `QUEUED` | 영향 없음 — 다음 SubmitWorker 사이클(5초)에서 정상 처리 |
| `DISPATCHING` | lease 만료(최대 90초) 후 LeaseRecoveryWorker가 QUEUED로 복귀, 재제출 |
| `PROCESSING` | lease 만료 후 LeaseRecoveryWorker가 lockedUntil 해제 + pollDueAt = now로 즉시 폴링 재개 |
| `RETRY_WAIT` | 영향 없음 — nextAttemptAt 도달 시 LeaseRecoveryWorker가 QUEUED로 전환 |
| `COMPLETED` / `FAILED` / `DEAD_LETTER` | 영향 없음 (터미널 상태) |

**데이터 정합성이 깨질 수 있는 지점:**

1. `onSubmitSuccess()` 트랜잭션 커밋 이전 크래시 → Job은 DISPATCHING 유지 → Recovery 후 재제출 → Mock Worker가 같은 이미지를 두 번 처리할 수 있음
2. `onPollResult()` 트랜잭션 커밋 이전 크래시 → COMPLETED 결과를 Mock Worker에서 받았지만 DB는 PROCESSING → 재폴링 후 재저장 (결과가 동일하면 무해)
3. DISPATCHING 상태에서 lease 만료(90초) 전 재시작 → 최대 90초간 재처리 없이 대기

---

### 동시 요청 발생 시 고려 사항

- **DB 레벨 UNIQUE 제약 + INSERT IGNORE:** 동시에 동일 URL로 요청이 들어와도 하나의 Job만 생성됩니다
- **FOR UPDATE SKIP LOCKED:** 여러 워커 스레드(또는 인스턴스)가 동시에 QUEUED Job을 클레임해도 각 Job은 한 번만 처리됩니다
- **lockedUntil (lease):** 클레임된 Job은 90초간 다른 워커가 획득할 수 없습니다
- **멀티 인스턴스:** 공유 DB + FOR UPDATE SKIP LOCKED 기반이므로 수평 확장 시에도 정합성이 유지됩니다

---

### 트래픽 증가 시 병목 가능 지점

1. **DB 커넥션 풀 (HikariCP max=20):** SubmitWorker/PollWorker가 각각 배치 단위로 DB 트랜잭션을 잡습니다. 트래픽이 급증하면 커넥션 고갈이 첫 번째 병목이 됩니다.

2. **FOR UPDATE SKIP LOCKED 쿼리:** 배치 클레임 시 인덱스가 잘 사용되더라도, QUEUED/PROCESSING Job이 많아지면 락 경합이 증가할 수 있습니다.

3. **Bulkhead (동시 Mock Worker 호출 8개):** 트래픽이 늘어도 Mock Worker로 나가는 동시 요청은 8개로 제한됩니다. 처리량을 높이려면 이 값을 조정하거나 워커 인스턴스를 늘려야 합니다.

4. **스케줄러 배치 주기 고정:** SubmitWorker 5초, PollWorker 10초 고정 주기로 동작합니다. 요청이 급증해도 배치 크기(각 5/10개) 이상을 한 사이클에 처리하지 않으므로, 지연이 누적될 수 있습니다. 배치 크기와 주기를 조정하거나 메시지 큐(Kafka/RabbitMQ)로 교체하는 것이 확장성 있는 방향입니다.

---

### 외부 시스템 연동 방식 및 선택 이유

**Submit-then-Poll 패턴**

Mock Worker가 즉시 결과를 반환하지 않을 수 있는 비동기 시스템이므로, 제출 후 별도 폴링으로 결과를 수집하는 방식을 선택했습니다. 콜백(Webhook) 방식은 Mock Worker가 역방향 HTTP 호출을 지원해야 하나, 과제 명세상 그 보장이 없으므로 폴링을 택했습니다.

**WebClient (Reactor Netty)**

- 비동기 non-blocking HTTP 클라이언트로 커넥션 타임아웃, 읽기 타임아웃을 세밀하게 제어할 수 있습니다
- `block()`으로 호출하지만, 타임아웃 제어와 Resilience4j 연동을 위해 WebClient를 사용했습니다

**Resilience4j Retry + Bulkhead**

- **Retry:** 일시적 5xx/429/네트워크 오류에 대해 지수 백오프로 자동 재시도합니다. 4xx는 재시도하지 않아 불필요한 호출을 방지합니다
- **Bulkhead (Semaphore):** Mock Worker로 나가는 동시 요청 수를 제한해 GPU 리소스를 많이 쓰는 외부 서비스에 과부하를 주지 않습니다
- Circuit Breaker는 미적용했습니다. Mock Worker가 완전히 다운되면 Retry 소진 후 DEAD_LETTER로 전환되므로 시스템 안정성은 유지됩니다

---

## 포트 및 환경변수

| 항목 | 기본값 | 설명 |
|------|--------|------|
| 앱 포트 | `8080` | HTTP API 서버 |
| MySQL 포트 | `3306` | 로컬 노출 포트 |
| `MOCK_WORKER_API_KEY` | (자동 발급) | 비워두면 시작 시 자동 발급 |
| `MOCK_WORKER_BASE_URL` | `https://dev.realteeth.ai` | Mock Worker 기본 주소 |
| `MOCK_WORKER_CANDIDATE_NAME` | `evaluator` | API 키 발급 시 사용할 이름 |
| `MOCK_WORKER_EMAIL` | `evaluator@example.com` | API 키 발급 시 사용할 이메일 |
| `DB_URL` | `jdbc:mysql://localhost:3306/imagejob` | DB 접속 URL |
| `DB_USER` | `imagejob` | DB 사용자 |
| `DB_PASSWORD` | `imagejob` | DB 비밀번호 |
