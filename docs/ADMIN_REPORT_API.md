# 관리자 신고 API

## 개요

관리자 권한을 가진 사용자가 접수된 신고를 조회하고 처리하는 API입니다.

관리자 권한은 `users.role = ADMIN` 기준으로 판단합니다.  
JWT Access Token에는 `userRole` claim이 포함되며, Spring Security에서는 `ROLE_ADMIN` 권한으로 변환됩니다.

---

## 공통 권한

모든 관리자 신고 API는 다음 권한이 필요합니다.

```text
ROLE_ADMIN
````

일반 사용자 또는 게스트가 접근하면 `403 Forbidden`이 반환됩니다.

---

## 1. 신고 목록 조회

```http
GET /api/admin/reports
Authorization: Bearer {ADMIN_ACCESS_TOKEN}
```

### Query Parameters

| 이름         | 타입     | 필수 | 설명                     |
| ---------- | ------ | -: | ---------------------- |
| targetType | string |  N | 신고 대상 타입               |
| status     | string |  N | 신고 처리 상태               |
| page       | number |  N | 0-based 페이지 번호. 기본값 0  |
| size       | number |  N | 페이지 크기. 기본값 20, 최대 100 |

### targetType

| 값                  | 설명           |
| ------------------ | ------------ |
| LOBBY              | 로비 신고        |
| LOBBY_USER         | 로비 내 사용자 신고  |
| LOBBY_CHAT_MESSAGE | 로비 채팅 메시지 신고 |

### status

| 값         | 설명          |
| --------- | ----------- |
| PENDING   | 처리 대기       |
| RESOLVED  | 유효 신고 처리 완료 |
| DISMISSED | 기각          |

### Request Example

```http
GET /api/admin/reports?targetType=LOBBY_CHAT_MESSAGE&status=PENDING&page=0&size=20
Authorization: Bearer {ADMIN_ACCESS_TOKEN}
```

### Response Example

```json
{
  "items": [
    {
      "reportId": 1,
      "reporterId": 10,
      "reporterUsername": "reporter",
      "lobbyId": 20,
      "lobbyCode": "ABC123",
      "lobbyTitle": "신고 테스트 로비",
      "targetType": "LOBBY_CHAT_MESSAGE",
      "targetId": 20,
      "targetReference": "message-1",
      "reason": "부적절한 채팅 메시지",
      "status": "PENDING",
      "createdAt": "2026-05-31T12:00:00",
      "resolvedAt": null
    }
  ],
  "page": 0,
  "size": 20,
  "hasNext": false
}
```

### Error Responses

| HTTP Status | 상황                                 |
| ----------: | ---------------------------------- |
|         400 | page가 음수이거나 size가 1~100 범위를 벗어난 경우 |
|         401 | 인증되지 않은 요청                         |
|         403 | 관리자 권한이 없는 요청                      |

---

## 2. 신고 상세 조회

```http
GET /api/admin/reports/{reportId}
Authorization: Bearer {ADMIN_ACCESS_TOKEN}
```

### Path Parameters

| 이름       | 타입     | 설명    |
| -------- | ------ | ----- |
| reportId | number | 신고 ID |

### Request Example

```http
GET /api/admin/reports/1
Authorization: Bearer {ADMIN_ACCESS_TOKEN}
```

### Response Example - 로비/유저 신고

```json
{
  "reportId": 1,
  "reporterId": 10,
  "reporterUsername": "reporter",
  "lobbyId": 20,
  "lobbyCode": "ABC123",
  "lobbyTitle": "신고 테스트 로비",
  "targetType": "LOBBY_USER",
  "targetId": 30,
  "targetReference": null,
  "reason": "부적절한 사용자",
  "status": "PENDING",
  "createdAt": "2026-05-31T12:00:00",
  "resolvedAt": null,
  "chatMessageSnapshot": null
}
```

### Response Example - 채팅 메시지 신고

```json
{
  "reportId": 2,
  "reporterId": 10,
  "reporterUsername": "reporter",
  "lobbyId": 20,
  "lobbyCode": "ABC123",
  "lobbyTitle": "신고 테스트 로비",
  "targetType": "LOBBY_CHAT_MESSAGE",
  "targetId": 20,
  "targetReference": "message-1",
  "reason": "부적절한 채팅 메시지",
  "status": "PENDING",
  "createdAt": "2026-05-31T12:00:00",
  "resolvedAt": null,
  "chatMessageSnapshot": {
    "snapshotId": 100,
    "messageId": "message-1",
    "senderIdentifier": "sender-uuid",
    "senderId": 30,
    "senderNickname": "sender",
    "content": "신고 대상 메시지",
    "messageType": "CHAT",
    "sentAt": "2026-05-31T12:00:00",
    "createdAt": "2026-05-31T12:01:00"
  }
}
```

### Error Responses

| HTTP Status | 상황            |
| ----------: | ------------- |
|         401 | 인증되지 않은 요청    |
|         403 | 관리자 권한이 없는 요청 |
|         404 | 존재하지 않는 신고    |

---

## 3. 신고 처리 상태 변경

```http
PATCH /api/admin/reports/{reportId}/status
Authorization: Bearer {ADMIN_ACCESS_TOKEN}
Content-Type: application/json
```

### Path Parameters

| 이름       | 타입     | 설명    |
| -------- | ------ | ----- |
| reportId | number | 신고 ID |

### Request Body

| 이름     | 타입     | 필수 | 설명           |
| ------ | ------ | -: | ------------ |
| status | string |  Y | 변경할 신고 처리 상태 |

### 허용 상태

| 값         | 설명        |
| --------- | --------- |
| RESOLVED  | 유효 신고로 처리 |
| DISMISSED | 신고 기각     |

`PENDING`은 신고 접수 직후 상태이므로 관리자 처리 요청값으로 허용하지 않습니다.

### Request Example - 처리 완료

```http
PATCH /api/admin/reports/1/status
Authorization: Bearer {ADMIN_ACCESS_TOKEN}
Content-Type: application/json

{
  "status": "RESOLVED"
}
```

### Request Example - 기각

```http
PATCH /api/admin/reports/1/status
Authorization: Bearer {ADMIN_ACCESS_TOKEN}
Content-Type: application/json

{
  "status": "DISMISSED"
}
```

### Response

```http
204 No Content
```

### Error Responses

| HTTP Status | 상황                         |
| ----------: | -------------------------- |
|         400 | status가 누락되었거나 PENDING인 경우 |
|         401 | 인증되지 않은 요청                 |
|         403 | 관리자 권한이 없는 요청              |
|         404 | 존재하지 않는 신고                 |
|         409 | 이미 처리된 신고를 다시 처리하려는 경우     |

---

## 상태 전이 정책

```text
PENDING -> RESOLVED
PENDING -> DISMISSED
```

아래 전이는 허용하지 않습니다.

```text
RESOLVED -> *
DISMISSED -> *
* -> PENDING
```

---

## 수동 테스트 절차

### 1. 관리자 권한 부여

```sql
UPDATE users
SET role = 'ADMIN'
WHERE id = {USER_ID};
```

### 2. 다시 로그인

기존 Access Token에는 변경된 role이 반영되지 않습니다.
관리자 권한을 부여한 뒤 반드시 다시 로그인해야 합니다.

### 3. 관리자 API 호출

```http
GET /api/admin/reports
Authorization: Bearer {ADMIN_ACCESS_TOKEN}
```

### 4. 일반 사용자 접근 차단 확인

일반 사용자 Access Token으로 같은 API를 호출했을 때 `403 Forbidden`이 반환되어야 합니다.
