# image-processing-service

## 1. 요약

이 프로젝트는 클라이언트로부터 이미지 처리 요청을 받아 비동기 작업으로 등록하고, 외부 시스템인 Mock Worker에 실제 처리를 위임한 뒤, 작업 상태와 결과를 조회할 수 있도록 만든 Kotlin + Spring Boot 기반 서버입니다.

스케줄러 기반 submit / poll / lease recovery 워커를 통해 외부 시스템과 연동합니다.

핵심 목표는 다음 4가지입니다.

- 요청 시점에 즉시 완료되지 않는 장기 실행 작업을 안전하게 관리할 것
- 외부 시스템의 지연, 실패, 일시 장애를 고려해 재시도 가능한 구조를 만들 것
- 중복 요청과 동시 요청 상황에서 데이터 정합성을 최대한 보장할 것
- 서버 재시작 이후에도 진행 중이던 작업을 다시 이어갈 수 있을 것

---

## 2. 요구사항 대응

요구사항과 구현 대응은 다음과 같습니다.

- 이미지 처리 요청 API 제공
  - `POST /api/v1/jobs`
  - 응답으로 내부 `jobId`를 반환하여 클라이언트가 작업을 추적할 수 있도록 했습니다.

- 작업 상태 조회 API 제공
  - `GET /api/v1/jobs/{jobId}`
  - `GET /api/v1/jobs?page=0&size=20`

- Mock Worker 연동
  - 시작 시 API Key 자동 발급
  - 작업 등록 시 `/mock/process` 호출
  - 작업 진행 중 `/mock/process/{jobId}` polling

---

## 3. 시스템 구성

전체 흐름은 아래와 같습니다.

```text
Client
  -> POST /api/v1/jobs
  -> Image Processing Service
       -> MySQL에 job 저장
       -> SubmitWorker가 Mock Worker로 작업 위임
       -> PollWorker가 외부 job 상태 polling
       -> 결과/실패를 MySQL에 반영
  -> GET /api/v1/jobs/{jobId}
  -> GET /api/v1/jobs
```

구성 요소는 다음과 같습니다.

- API Layer
  - 작업 생성, 단건 조회, 목록 조회
- Service Layer
  - 상태 전이, 중복 처리, 재시도, lease recovery 비즈니스 규칙
- Worker Layer
  - submit worker
  - poll worker
  - retry-wait requeue worker
  - lease recovery worker
- External Integration
  - Mock Worker API Key 발급
  - Mock Worker submit / poll 호출

---

## 4. 실행 방법

### 4.1 요구 환경

- Java 17
- Docker

### 4.2 로컬 실행

MySQL 실행:

```bash
docker compose up -d mysql
```

애플리케이션 실행:

```bash
./gradlew bootRun
```

기본 포트:

- 애플리케이션: `8080`
- MySQL: `3306`

### 4.3 Docker Compose 실행

```bash
docker compose up --build
```

### 4.4 자격 증명 관련

별도의 상용 계정이나 사전 발급 키를 준비할 필요는 없습니다.

애플리케이션은 시작 시 `MOCK_WORKER_API_KEY`가 비어 있으면 Mock Worker의 `/mock/auth/issue-key`를 호출해 API Key를 자동 발급받습니다. 따라서 외부 네트워크로 `https://dev.realteeth.ai`에 접근 가능한 환경이면 추가 준비 없이 실행 가능합니다.

---

## 5. 환경 변수

주요 환경 변수는 다음과 같습니다.

- `DB_URL`
- `DB_USER`
- `DB_PASSWORD`
- `MOCK_WORKER_BASE_URL`
- `MOCK_WORKER_API_KEY`
- `MOCK_WORKER_CANDIDATE_NAME`
- `MOCK_WORKER_EMAIL`

기본값은 `src/main/resources/application.yml`에 정의되어 있습니다.

---

## 6. API 명세

### 6.1 작업 생성

`POST /api/v1/jobs`

요청 헤더:

- `Content-Type: application/json`
- `Idempotency-Key: optional`

요청 본문:

```json
{
  "imageUrl": "https://example.com/image.jpg"
}
```

응답:

- `201 Created`: 새 작업 생성
- `200 OK`: 기존 활성 작업 또는 idempotent 요청에 대한 기존 작업 반환

예시 응답:

```json
{
  "jobId": "2d95cf3a-9b12-4e97-9d67-8d8dc7b51f84",
  "status": "QUEUED",
  "imageUrl": "https://example.com/image.jpg",
  "result": null,
  "error": null,
  "attemptCount": 0,
  "createdAt": "2026-03-10T11:00:00Z",
  "updatedAt": "2026-03-10T11:00:00Z"
}
```

### 6.2 작업 단건 조회

`GET /api/v1/jobs/{jobId}`

### 6.3 작업 목록 조회

`GET /api/v1/jobs?page=0&size=20`

### 6.4 검증 실패 / 오류 응답

예시:

```json
{
  "code": "VALIDATION_ERROR",
  "message": "imageUrl: imageUrl은 http 또는 https URL이어야 합니다",
  "timestamp": "2026-03-10T11:00:00Z"
}
```

---

## 7. 상태 모델 설계 의도

### 7.1 상태 목록

- `QUEUED`
  - 아직 외부 시스템에 submit 되지 않은 상태
- `DISPATCHING`
  - 외부 시스템에 submit 중인 상태
- `PROCESSING`
  - Mock Worker가 처리 중이거나, 처리 완료 응답을 받았더라도 최종 확인을 위해 poll 중인 상태
- `RETRY_WAIT`
  - submit 실패 후 backoff 대기 상태
- `COMPLETED`
  - 최종 결과 확인이 끝난 성공 상태
- `FAILED`
  - 비재시도성 실패 또는 외부 시스템의 최종 실패 상태
- `DEAD_LETTER`
  - 재시도 한도를 모두 소진한 상태

### 7.2 왜 이렇게 나눴는가

핵심 의도는 "외부 호출 중인지", "외부에서 처리 중인지", "재시도 대기인지", "최종 종료인지"를 구분하는 것입니다.

- `DISPATCHING`은 네트워크 타임아웃, 외부 API 오류, 서버 크래시가 가장 민감한 구간이므로 별도 상태로 분리했습니다.
- `PROCESSING`은 외부 시스템이 아직 끝나지 않았거나, submit 시점에 `COMPLETED`를 응답하더라도 최종 결과를 확정하기 전까지는 종료 상태로 보지 않도록 설계했습니다.
- `RETRY_WAIT`을 두어 실패 직후 재시도 폭주를 방지했습니다.
- `DEAD_LETTER`를 두어 무한 재시도를 막고 운영자가 별도 관찰할 수 있도록 했습니다.

### 7.3 허용 상태 전이

```text
QUEUED -> DISPATCHING
DISPATCHING -> QUEUED | PROCESSING | COMPLETED | FAILED | RETRY_WAIT | DEAD_LETTER
PROCESSING -> COMPLETED | FAILED | DEAD_LETTER
RETRY_WAIT -> QUEUED | DEAD_LETTER
COMPLETED -> 없음
FAILED -> 없음
DEAD_LETTER -> 없음
```

주의할 점은 현재 코드상 `DISPATCHING -> COMPLETED` 전이가 허용되어 있지만, 실제 처리 로직은 submit 응답이 `COMPLETED`여도 즉시 `COMPLETED`로 끝내지 않고 `PROCESSING`으로 전환한 뒤 즉시 poll 하도록 구현했습니다. 이는 외부 시스템 응답을 한 번 더 확인해 최종 상태를 확정하기 위한 방어적 설계입니다.

### 7.4 허용되지 않는 상태 전이

예를 들면 다음 전이는 금지됩니다.

- `QUEUED -> COMPLETED`
- `PROCESSING -> RETRY_WAIT`
- `COMPLETED -> PROCESSING`
- `FAILED -> QUEUED`
- `DEAD_LETTER -> QUEUED`

이 규칙은 코드에서 강제되며, 잘못된 전이는 예외로 처리됩니다.

---

## 8. 중복 요청 처리 전략

과제의 핵심 요구사항 중 하나는 동일 요청이 여러 번 들어와도 안전하게 처리하는 것입니다. 이 프로젝트는 중복을 두 층으로 나눠 처리합니다.

### 8.1 1차: 활성 작업 기준 dedup

가장 먼저 `imageUrl`을 정규화한 뒤 해시를 계산하고, 현재 종료되지 않은 활성 작업이 이미 있는지 조회합니다.

이때 `Idempotency-Key` 유무와 관계없이 활성 작업 dedup을 먼저 적용합니다.

이 설계를 택한 이유는 다음과 같습니다.

- 동일 이미지를 동시에 여러 번 처리하는 것은 외부 GPU 리소스를 낭비합니다.
- 사용자가 서로 다른 `Idempotency-Key`로 같은 요청을 보내더라도, 시스템 관점에서는 동일 작업으로 보는 편이 안전합니다.
- 시나리오상 외부 시스템이 무겁고 불안정할 수 있으므로, 내부에서 먼저 중복 폭주를 차단하는 것이 중요합니다.

### 8.2 2차: Idempotency-Key 기반 멱등 처리

활성 작업이 없으면 그 다음 `Idempotency-Key`를 기준으로 기존 작업을 찾습니다.

이 레이어는 "같은 클라이언트 요청의 재전송"을 처리하기 위한 것입니다. 예를 들어 클라이언트가 네트워크 타임아웃 때문에 같은 요청을 다시 보내도 동일 작업을 돌려줄 수 있습니다.

### 8.3 DB 레벨 보호

애플리케이션 레벨 조회만으로는 동시 요청 경쟁 조건을 완전히 막을 수 없기 때문에 DB 제약조건을 같이 사용했습니다.

- `idempotency_key` unique index
- `active_dedup_key` unique index

`active_dedup_key`는 종료 상태가 아닌 작업에 대해서만 `payload_hash`를 갖는 generated column입니다. 이 방식으로 "동일 이미지에 대해 활성 작업은 하나만 존재"하도록 강제했습니다.

### 8.4 한계

이 dedup은 내부 시스템 기준입니다. 외부 Mock Worker 자체는 우리 서비스의 멱등 키를 이해하지 못하므로, 네트워크 장애나 크래시 시 외부 작업은 중복 생성될 수 있습니다. 이 부분은 아래 처리 보장 모델에서 설명합니다.

---

## 9. 실패 처리 전략

외부 시스템은 느리고 불안정할 수 있다는 가정 하에 실패를 재시도 가능 / 불가능으로 나눠 처리했습니다.

### 9.1 submit 단계

- 4xx 중 `429` 제외
  - 비재시도성 실패로 판단
  - 즉시 `FAILED`
- 5xx, `429`, 네트워크 예외
  - 재시도성 실패로 판단
  - `RETRY_WAIT`로 전환 후 backoff
- bulkhead 포화
  - 외부 시스템으로 보낼 수 없는 순간적 포화로 판단
  - 시도 횟수를 소모하지 않고 `QUEUED`로 되돌린 뒤 짧은 backoff 적용

### 9.2 poll 단계

- 4xx 중 `429` 제외
  - 비재시도성 실패
  - 즉시 `FAILED`
- 5xx, `429`, 네트워크 예외
  - 재시도성 실패
  - `PROCESSING` 상태를 유지하면서 poll backoff 증가
- poll 실패 횟수 초과
  - `DEAD_LETTER`

### 9.3 외부 응답 상태 처리

- `PROCESSING`
  - `PROCESSING` 유지, 다음 poll 예약
- `COMPLETED`
  - 최종 성공 처리
- `FAILED`
  - 최종 실패 처리
- 알 수 없는 상태
  - 방어적으로 실패 또는 다음 poll 예약

### 9.4 왜 submit 시 `COMPLETED`를 바로 완료 처리하지 않았는가

이번 수정에서 가장 중요하게 본 부분입니다.

submit 응답이 `COMPLETED`라고 하더라도 외부 시스템 응답 형식이나 내부 동작이 미래에 바뀔 수 있고, 제출 시점 응답과 조회 시점 응답이 다를 가능성도 배제할 수 없습니다. 따라서 submit 단계는 "외부 작업이 생성되었고 결과가 있을 수도 있다"는 힌트 정도로만 보고, 최종 완료 판정은 poll을 통해 다시 확인하도록 만들었습니다.

이 선택은 약간의 추가 API 호출을 발생시키지만, 외부 시스템을 신뢰 경계 밖으로 보고 더 보수적으로 상태를 확정한다는 점에서 안정성 측면의 이점이 큽니다.

---

## 10. 처리 보장 모델

이 시스템은 엄밀하게는 `at-least-once` 처리 모델에 가깝다고 판단했습니다.

### 10.1 그렇게 본 이유

다음과 같은 상황이 가능합니다.

1. 서버가 Mock Worker에 submit 요청을 보냄
2. Mock Worker는 실제로 작업을 생성함
3. 하지만 우리 서버는 네트워크 타임아웃 또는 크래시 때문에 응답을 저장하지 못함
4. lease recovery가 해당 작업을 다시 `QUEUED`로 돌림
5. 이후 동일 내부 job이 Mock Worker에 다시 submit 될 수 있음

즉, 내부 job 관점에서는 동일 작업이 두 번 이상 외부 시스템에 위임될 수 있습니다.

### 10.2 왜 exactly-once가 아닌가

- 외부 Mock Worker는 우리 서비스의 idempotency key를 이해하지 않습니다.
- submit 요청과 DB 상태 갱신을 하나의 분산 트랜잭션으로 묶을 수 없습니다.
- "외부 호출 성공"과 "내부 상태 저장 성공" 사이에는 항상 불확실 구간이 존재합니다.

따라서 정확히 한 번 처리되었다고 단정할 수 없습니다.

### 10.3 대신 어떤 성질을 보장하려고 했는가

- 내부 DB 기준으로는 동일 활성 요청의 중복 생성을 최대한 억제
- 실패 후에도 작업이 영구 유실되지 않도록 재시도 및 lease recovery 제공
- 최종 종료 상태는 DB에 남아 조회 가능

즉, "중복 가능성은 일부 허용하되, 유실 가능성을 줄이는 방향"을 선택했습니다.

---

## 11. 서버 재시작 시 동작

서버 재시작 시에도 작업 상태는 MySQL에 남아 있으므로 기본적으로 복구 가능합니다.

### 11.1 재시작 후 복구 방식

- `QUEUED`
  - submit worker가 다시 가져가 처리
- `RETRY_WAIT`
  - requeue worker가 `nextAttemptAt`이 지난 작업을 다시 `QUEUED`로 이동
- `PROCESSING`
  - poll worker가 `pollDueAt` 기준으로 다시 polling
- `DISPATCHING` 또는 `PROCESSING`인데 `lockedUntil`이 만료된 작업
  - lease recovery worker가 복구

### 11.2 lease를 둔 이유

서버가 submit / poll 도중 죽으면 작업이 중간 상태에 고정될 수 있습니다. 이를 막기 위해 `lockedUntil`을 두고, 일정 시간 이후 만료된 lease를 다른 워커가 회수할 수 있도록 했습니다.

### 11.3 데이터 정합성이 깨질 수 있는 지점

가장 중요한 위험 지점은 외부 시스템과 내부 DB 상태 사이의 비원자성입니다.

예시는 다음과 같습니다.

1. Mock Worker submit 성공 후 DB 반영 전에 서버 종료
   - 외부에서는 작업이 생성되었지만 내부에서는 모름
   - lease recovery 이후 재submit 가능
   - 외부 작업 중복 생성 가능성 존재

2. poll 성공 후 DB 업데이트 전에 서버 종료
   - 외부는 이미 완료되었지만 내부는 아직 `PROCESSING`
   - 이후 다시 poll 하며 eventually consistent 하게 수렴

3. API Key 미영속
   - 현재 API Key는 프로세스 메모리에 보관
   - 서버 재시작 시 환경변수에 없으면 다시 발급
   - 기능적으로는 문제 없지만, 운영 환경이라면 영속 저장 또는 secret manager 연동이 더 적절함

---

## 12. 동시 요청 발생 시 고려 사항

동시 요청 시 가장 큰 문제는 "중복 job 생성"과 "서로 다른 워커가 같은 job을 동시에 집는 것"입니다.

이를 줄이기 위해 다음을 사용했습니다.

- 애플리케이션 레벨 선조회
  - 활성 dedup
  - idempotency lookup
- DB 제약조건
  - unique index로 최종 방어
- `INSERT IGNORE`
  - 경쟁 상황에서 한 요청만 실제 insert 되도록 유도
- `FOR UPDATE SKIP LOCKED`
  - 워커가 같은 row를 동시에 처리하지 않도록 함
- lease
  - 워커 장애 시 영구 lock 상태 방지

즉, 동시성 제어는 "애플리케이션 선조회 + DB 제약 + row lock + lease"의 조합으로 접근했습니다.

---

## 13. 외부 시스템 연동 방식과 선택 이유

Mock Worker와는 동기 HTTP 호출로 연동하되, 내부적으로는 비동기 작업 상태 머신으로 감쌌습니다.

선택 이유는 다음과 같습니다.

- Mock Worker가 즉시 완료를 보장하지 않음
- 처리 시간이 수 초에서 수십 초까지 변동
- 외부 장애를 내부 API 응답 시간에 직접 전파하면 클라이언트 경험이 나빠짐

따라서 클라이언트 요청 시에는 내부 job만 생성하고 빠르게 응답한 뒤, 백그라운드 워커가 외부 시스템을 대신 상대하는 구조를 선택했습니다.

추가로 다음 방어 장치를 두었습니다.

- Resilience4j retry
  - 일시 장애 대응
- Resilience4j bulkhead
  - 외부 의존성 호출 폭주 방지
- polling
  - 외부 시스템이 push/webhook을 제공하지 않는 상황 대응

---

## 14. 트래픽 증가 시 병목 가능 지점

현재 구조에서 주요 병목 가능 지점은 다음과 같습니다.

### 14.1 외부 Mock Worker

가장 명확한 병목은 외부 시스템입니다.

- 응답 시간이 길 수 있음
- 순간적으로 429 또는 5xx가 증가할 수 있음
- poll 대상 job 수가 많아지면 외부 API 호출량이 선형적으로 증가함

### 14.2 데이터베이스

- 활성 dedup 조회
- 작업 상태 update
- `FOR UPDATE SKIP LOCKED`
- polling 대상 검색

트래픽이 커지면 MySQL write contention과 index hot spot이 생길 수 있습니다.

### 14.3 스케줄러 기반 polling 구조

job 수가 많아질수록 polling 비용이 커집니다. 특히 짧은 주기의 polling은 외부 API와 DB 모두에 부하를 줍니다.

### 14.4 단일 애플리케이션 인스턴스 가정

현재 구현은 다중 인스턴스에서도 DB lock으로 어느 정도 동작할 수 있지만, 배치 크기와 스케줄 주기 조정 없이 수평 확장하면 DB 경합이 먼저 문제가 될 수 있습니다.

### 14.5 개선 방향

- polling 대상 샤딩 또는 poll 전용 워커 분리
- 메시지 큐 기반 submit / poll orchestration
- dead letter 전용 운영 도구 추가
- 외부 시스템이 webhook을 지원한다면 polling 축소
- API Key 및 설정의 secret manager 연동

---

## 15. 데이터베이스 초기화 전략

애플리케이션은 Spring SQL init을 사용해 스키마를 초기화합니다.

- `init.sql`
  - 기본 테이블 및 인덱스 생성
- `add_poll_failure_count.sql`
  - poll 관련 컬럼 호환성 보정
- `update_active_dedup_key.sql`
  - 구버전 스키마의 active dedup 규칙 보정

`spring.sql.init.continue-on-error=false`로 설정해, 스키마 문제를 조용히 무시하지 않고 애플리케이션 시작 실패로 드러나게 했습니다. 운영 관점에서는 잘못된 스키마를 안고 서비스가 뜨는 것보다 빠르게 실패하는 편이 안전하다고 판단했습니다.

---

## 16. 테스트

전체 테스트 실행:

```bash
./gradlew test
```

테스트 구성:

- 단위 테스트
  - 상태 전이
  - 중복 처리
  - submit / poll 성공, 실패, 재시도
  - lease recovery
- 통합 테스트
  - Testcontainers MySQL 사용
  - MockWebServer로 외부 API 시뮬레이션
  - API 동작과 DB 초기화 검증

최근 기준으로 `./gradlew test`는 성공했습니다.

---

## 17. 마무리

이 구현에서 가장 중요하게 둔 기준은 다음 두 가지입니다.

- 외부 시스템이 불확실하더라도 내부 상태는 추적 가능해야 한다.
- 정확히 한 번 처리보다는, 유실을 줄이고 중복을 관리 가능한 수준으로 제한하는 것이 현실적이다.

시나리오상 외부 GPU 작업은 느리고 실패 가능성이 있으며, 네트워크와 프로세스 크래시로 인해 불확실 구간이 필연적으로 존재합니다. 따라서 이 프로젝트는 단순 CRUD가 아니라, 상태 머신과 재시도, 중복 억제, lease recovery를 조합해 운영 환경에 가까운 비동기 작업 처리 서버를 만드는 데 초점을 맞췄습니다.
