# Report API

로비 및 로비 내 유저 신고 기능 문서입니다.

## 개요

신고 기능은 부적절한 로비 제목 또는 로비 내 유저를 신고하기 위한 기능입니다.

현재 지원하는 신고 대상은 다음과 같습니다.

| targetType | 설명 |
|---|---|
| `LOBBY` | 로비 자체 신고 |
| `LOBBY_USER` | 특정 로비 안의 유저 신고 |

신고 상태는 다음과 같습니다.

| status | 설명 |
|---|---|
| `PENDING` | 신고 접수 후 운영자 검토 대기 |
| `RESOLVED` | 운영자가 신고를 유효한 신고로 처리 완료 |
| `DISMISSED` | 운영자가 신고를 기각 |

---

## 공통 정책

### 인증

신고 API는 JWT 인증이 필요합니다.

게스트와 정식 회원 모두 신고할 수 있습니다.

### 신고 사유 검증

`reason`은 필수입니다.

검증 규칙은 다음과 같습니다.

| 필드 | 규칙 |
|---|---|
| `reason` | 공백 불가 |
| `reason` | 최대 500자 |

서비스 레이어에서 `trim()` 정규화를 수행한 뒤 저장합니다.

### 중복 신고 방지

동일 사용자는 동일 로비의 동일 대상에 대해 `PENDING` 상태의 신고를 중복 생성할 수 없습니다.

중복 기준은 다음과 같습니다.

```text
reporter_id + lobby_id + target_type + target_id + status(PENDING)
```

중복 신고 요청 시 `409 Conflict`를 반환합니다.

### 자동 비공개 전환 정책

현재 구현에서는 신고 누적 카운트 및 임계값 판단까지만 제공합니다.

자동 비공개 전환은 아직 수행하지 않습니다.

자동 비공개 전환은 다음 처리가 함께 필요하기 때문에 후속 이슈로 분리합니다.

```text
1. DB game_lobby.is_private 변경
2. Redis lobby:{code}.is_private 변경
3. Redis 공개 로비 인덱스 제거
4. 로비 목록 refresh 이벤트 전파
5. 운영 로그 기록
```

---

## API

## 1. 로비 신고

```http
POST /api/lobbies/{code}/reports
```

### Path Variable

| 이름 | 타입 | 설명 |
|---|---|---|
| `code` | string | 로비 초대 코드 |

### Request Body

```json
{
  "reason": "부적절한 로비 제목입니다."
}
```

### Response

```http
201 Created
```

```json
{
  "reportId": 1,
  "reporterId": 3,
  "lobbyId": 10,
  "targetType": "LOBBY",
  "targetId": 10,
  "reason": "부적절한 로비 제목입니다.",
  "status": "PENDING",
  "createdAt": "2026-05-23T21:20:00"
}
```

---

## 2. 로비 내 유저 신고

```http
POST /api/lobbies/{code}/users/{targetUserId}/reports
```

### Path Variable

| 이름 | 타입 | 설명 |
|---|---|---|
| `code` | string | 로비 초대 코드 |
| `targetUserId` | number | 신고 대상 users.id |

### Request Body

```json
{
  "reason": "채팅 도배를 반복했습니다."
}
```

### Response

```http
201 Created
```

```json
{
  "reportId": 2,
  "reporterId": 3,
  "lobbyId": 10,
  "targetType": "LOBBY_USER",
  "targetId": 7,
  "reason": "채팅 도배를 반복했습니다.",
  "status": "PENDING",
  "createdAt": "2026-05-23T21:25:00"
}
```

---

## 에러 응답 정책

| 상황 | HTTP Status | 메시지 |
|---|---:|---|
| 인증 정보 없음 또는 유효하지 않음 | `401 Unauthorized` | `유효하지 않은 인증 정보입니다. 다시 로그인해주세요.` |
| 신고자 없음 | `404 Not Found` | `신고자를 찾을 수 없습니다.` |
| 로비 없음 | `404 Not Found` | `신고 대상 로비를 찾을 수 없습니다.` |
| 삭제된 로비 신고 | `409 Conflict` | `삭제된 로비는 신고할 수 없습니다.` |
| 신고 대상 유저 없음 | `404 Not Found` | `신고 대상 유저를 찾을 수 없습니다.` |
| 자기 자신 신고 | `400 Bad Request` | `자기 자신은 신고할 수 없습니다.` |
| 신고 사유 공백 | `400 Bad Request` | `신고 사유는 비어 있을 수 없습니다.` |
| 중복 신고 | `409 Conflict` | `이미 접수된 신고입니다.` |

---

## 신고 누적 정책

신고 누적 정책은 설정값으로 관리합니다.

```properties
report.policy.lobby-review-threshold=${REPORT_POLICY_LOBBY_REVIEW_THRESHOLD:5}
report.policy.auto-private-enabled=${REPORT_POLICY_AUTO_PRIVATE_ENABLED:false}
```

| 설정 | 기본값 | 설명 |
|---|---:|---|
| `report.policy.lobby-review-threshold` | `5` | 특정 로비의 PENDING 신고 수가 이 값 이상이면 관리자 검토 대상 |
| `report.policy.auto-private-enabled` | `false` | 자동 비공개 전환 후보 판단 여부 |

현재 단계에서는 자동 비공개 전환을 실제 수행하지 않습니다.

---

## Swagger 확인

개발 서버 실행 후 Swagger UI에서 확인할 수 있습니다.

```text
http://localhost:8080/swagger-ui/index.html
```

확인 대상 API:

```text
POST /api/lobbies/{code}/reports
POST /api/lobbies/{code}/users/{targetUserId}/reports
```

---

## 테스트 방법

### 1. 컴파일

```bash
./gradlew compileJava
```

### 2. 서비스 테스트

```bash
./gradlew test --tests "io.github.ascrew.monomatbe.domain.report.service.ReportServiceTest"
```

### 3. 서버 실행

```bash
./gradlew bootRun
```

### 4. DB 확인

```sql
USE MonomatDB;

SHOW TABLES LIKE 'report';

DESC report;

SHOW INDEX FROM report;

SELECT installed_rank, version, description, success
FROM flyway_schema_history
ORDER BY installed_rank DESC;
```

정상 상태:

```text
report 테이블 존재
version = 4
description = create report table
success = 1
```
