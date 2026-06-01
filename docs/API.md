# Monomat-BE API 명세서

## 공통 사항

* **Base URL** : `http://{서버 도메인}:8080`
* **WebSocket Endpoint** : `ws://{서버 도메인}:8080/ws` (SockJS 폴백 지원)
* **Content-Type** : `application/json`
* **REST 인증** : `Authorization: Bearer {accessToken}`
* **WebSocket 인증** : STOMP CONNECT 헤더에 `userIdentifier` 포함 필요

---

## REST API

### 시스템 (System)

#### 서버 시간 동기화 조회

```http
GET /api/system/time
```

클라이언트가 서버와의 시간 오차(delta)를 계산하기 위해 호출합니다.
인증 토큰 없이 누구나 호출할 수 있습니다.

**Response `200 OK`**

```json
{
  "serverTimeMillis": 1716500000000
}
```

---

### 인증 (Auth)

인증 API는 게스트 로그인, 회원가입, 자체 로그인, 토큰 재발급, 로그아웃을 제공합니다.

상세 요청/응답, 에러 코드, 필드별 에러 메시지, 프론트엔드 표시 문구 매핑 기준은 별도 문서를 기준으로 관리합니다.

- [인증 API 상세 문서](./AUTH_API.md)

#### 인증 API 목록

| 기능 | Method | Endpoint | 인증 필요 | 성공 상태 |
| --- | --- | --- | --- | --- |
| 게스트 로그인 | `POST` | `/api/auth/guest` | ❌ | `200 OK` |
| 회원가입 | `POST` | `/api/auth/register` | ❌ | `201 Created` |
| 자체 로그인 | `POST` | `/api/auth/login` | ❌ | `200 OK` |
| 토큰 재발급 | `POST` | `/api/auth/refresh` | ❌ | `200 OK` |
| 로그아웃 | `POST` | `/api/auth/logout` | ✅ | `200 OK` |

#### 인증 API 공통 에러 응답

인증 API의 표준 에러 응답 body는 다음 형식을 따릅니다.

```json
{
  "code": "AUTH_INVALID_CREDENTIALS",
  "message": "로그인 ID 또는 비밀번호가 올바르지 않습니다.",
  "field": null
}
````

| 필드        | 타입            | 설명                                      |
| --------- | ------------- | --------------------------------------- |
| `code`    | string        | 프론트엔드 분기 기준이 되는 인증 에러 코드                |
| `message` | string        | 사용자 표시용 메시지                             |
| `field`   | string | null | 특정 입력 필드와 연결되는 경우 해당 필드명, 전역 에러면 `null` |

> 현재 구현 기준 `httpStatus`는 응답 body에 포함하지 않습니다.
> HTTP 상태는 response status line을 기준으로 판단합니다.

#### 프론트엔드 에러 처리 기준

프론트엔드는 인증 API 에러 처리 시 `message` 문자열에 의존하지 않습니다.

에러 UI 분기 기준은 다음 순서를 따릅니다.

1. HTTP Status Code
2. `code`
3. `field`
4. `message`

처리 기준은 다음과 같습니다.

| 조건                | 처리 방식                             |
| ----------------- | --------------------------------- |
| `field`가 존재함      | 해당 입력 필드 하단에 메시지 표시               |
| `field`가 `null`임  | form 상단 또는 toast/global alert로 표시 |
| `code`를 알고 있음     | FE에서 정의한 표시 정책 우선 적용 가능           |
| `code`를 모름        | 서버 `message`를 fallback으로 표시       |
| HTTP Status `401` | 인증 실패 또는 세션 만료 처리                 |
| HTTP Status `423` | 계정 잠금 안내                          |
| HTTP Status `503` | 일시 장애 안내                          |

#### 비밀번호 확인 정책

`passwordConfirm`은 백엔드 API 요청 필드가 아닙니다.

프론트엔드는 회원가입 요청 전 `password`와 `passwordConfirm`의 일치 여부를 자체 검증합니다.
두 값이 일치하지 않으면 백엔드 요청을 보내지 않고 화면에서 즉시 안내합니다.

권장 사용자 표시 문구:

```text
비밀번호가 일치하지 않습니다.
```
---

### 사용자 (User)

로그인한 사용자의 기본 정보를 조회합니다.

#### 내 사용자 정보 조회

```http
GET /api/users/me
````

JWT Access Token이 필요합니다.

프론트엔드는 이 API를 사용해 상단 프로필 영역, 닉네임 표시, 게스트/회원 분기 처리 등에 필요한 사용자 정보를 조회할 수 있습니다.

**Request Header**

| 헤더              | 필수 | 설명                     |
| --------------- | -- | ---------------------- |
| `Authorization` | ✅  | `Bearer {accessToken}` |

**Response `200 OK`**

```json
{
  "userId": 1,
  "username": "모노유저",
  "userType": "REGISTERED",
  "status": "ACTIVE",
  "createdAt": "2026-05-29T12:00:00"
}
```

**Response Fields**

| 필드          | 타입     | 설명                                    |
| ----------- | ------ | ------------------------------------- |
| `userId`    | Long   | 사용자 고유 ID. `users.id`                 |
| `username`  | String | 서비스 표시 닉네임                            |
| `userType`  | String | 사용자 유형. `GUEST`, `REGISTERED`         |
| `status`    | String | 사용자 상태. `ACTIVE`, `BANNED`, `DELETED` |
| `createdAt` | String | 사용자 생성 시각. ISO-8601 LocalDateTime 형식  |

**Error**

| 상태 코드              | 설명                                                |
| ------------------ | ------------------------------------------------- |
| `401 Unauthorized` | 인증 정보 없음, 유효하지 않은 Access Token, DB에서 사용자를 찾을 수 없음 |
| `401 Unauthorized` | 탈퇴 또는 삭제된 사용자                                     |
| `403 Forbidden`    | 정지된 사용자                                           |
| `409 Conflict`     | 사용자 상태 값이 비정상인 경우                                 |

**프론트엔드 처리 기준**

| 상황                 | 처리 방식                                  |
| ------------------ | -------------------------------------- |
| `200 OK`           | 사용자 정보를 전역 auth/user 상태에 저장            |
| `401 Unauthorized` | 토큰 만료 또는 세션 무효 처리 후 로그인/게스트 진입 화면으로 이동 |
| `403 Forbidden`    | 정지 계정 안내 화면 또는 toast 표시                |
| `409 Conflict`     | 일시적 상태 불일치 안내 후 재로그인 유도                |

---

### 관리자 - 닉네임 금칙어 관리

닉네임 금칙어 목록을 관리자 API로 조회, 추가, 삭제합니다.

현재 프로젝트에는 별도 `ROLE_ADMIN` 권한 체계가 없으므로, 관리자 API 접근은 `admin_users` 테이블 기반으로 제한합니다.

관리자 API를 호출하려면 다음 조건을 모두 만족해야 합니다.

- JWT Access Token이 유효해야 합니다.
- JWT에서 추출한 `userId`가 `admin_users.user_id`에 등록되어 있어야 합니다.
- 해당 사용자의 `user_type`이 `REGISTERED`여야 합니다.

관리자 등록 예시:

```sql
INSERT IGNORE INTO admin_users (user_id, created_at, updated_at)
VALUES (105, NOW(), NOW());
```

**관리자 확인 예시:**
```sql
SELECT au.id, au.user_id, u.username, u.user_type
FROM admin_users au
JOIN users u ON u.id = au.user_id
WHERE au.user_id = 105;
```

**Fallback 설정**
DB 장애 또는 local/dev 긴급 접근을 위해 환경변수 fallback allow-list를 사용할 수 있습니다.
```
MONOMAT_ADMIN_USER_IDS=105
```

**Spring 설정 키:**
```
monomat.admin.user-ids=${MONOMAT_ADMIN_USER_IDS:}
```

> MONOMAT_ADMIN_USER_IDS는 운영 주 권한 저장소가 아닙니다.
기본 관리자 권한은 admin_users 테이블 기준으로 관리합니다.
userIdentifier는 로그인/세션 식별자 성격이므로 관리자 권한 기준으로 사용하지 않습니다.
---

#### 금칙어 목록 조회

```http
GET /api/admin/forbidden-nicknames
```

**Request Header**

| 헤더              | 필수 | 설명                     |
| --------------- | -- | ---------------------- |
| `Authorization` | ✅  | `Bearer {accessToken}` |

**Response `200 OK`**

```json
[
  {
    "id": 1,
    "word": "관리자",
    "normalizedWord": "관리자",
    "createdAt": "2026-05-28T19:00:00",
    "updatedAt": "2026-05-28T19:00:00"
  }
]
```

**Error**

| 상태 코드              | 설명                               |
| ------------------ | -------------------------------- |
| `401 Unauthorized` | 인증 정보 없음 또는 유효하지 않은 Access Token |
| `403 Forbidden`    | `users.id`가 관리자 allow-list에 포함되지 않은 사용자 |
---

#### 금칙어 추가

```http
POST /api/admin/forbidden-nicknames
```

**Request Header**

| 헤더              | 필수 | 설명                     |
| --------------- | -- | ---------------------- |
| `Authorization` | ✅  | `Bearer {accessToken}` |
| `Content-Type`  | ✅  | `application/json`     |

**Request Body**

```json
{
  "word": "관리자"
}
```

| 필드     | 타입     | 필수 | 설명                   |
| ------ | ------ | -- | -------------------- |
| `word` | String | ✅  | 등록할 닉네임 금칙어. 최대 100자 |

**Response `201 Created`**

```json
{
  "id": 1,
  "word": "관리자",
  "normalizedWord": "관리자",
  "createdAt": "2026-05-28T19:00:00",
  "updatedAt": "2026-05-28T19:00:00"
}
```

**정규화 정책**

금칙어는 비교 및 중복 방지를 위해 정규화됩니다.

| 입력값         | normalizedWord |
| ----------- | -------------- |
| `Admin`     | `admin`        |
| `A d m i n` | `admin`        |
| `관 리 자`     | `관리자`          |

동일한 `normalizedWord`를 가진 금칙어는 중복 등록할 수 없습니다.

**Error**

| 상태 코드              | 설명                               |
| ------------------ | -------------------------------- |
| `400 Bad Request`  | 금칙어가 비어 있음 또는 요청 형식 오류           |
| `401 Unauthorized` | 인증 정보 없음 또는 유효하지 않은 Access Token |
| `403 Forbidden`    | `users.id`가 관리자 allow-list에 포함되지 않은 사용자 |
| `409 Conflict`     | 이미 등록된 금칙어                       |

---

#### 금칙어 삭제

```http
DELETE /api/admin/forbidden-nicknames/{id}
```

**Request Header**

| 헤더              | 필수 | 설명                     |
| --------------- | -- | ---------------------- |
| `Authorization` | ✅  | `Bearer {accessToken}` |

**Path Variable**

| 이름   | 타입   | 설명         |
| ---- | ---- | ---------- |
| `id` | Long | 삭제할 금칙어 ID |

**Response `204 No Content`**

응답 본문 없음.

**Error**

| 상태 코드              | 설명                               |
| ------------------ | -------------------------------- |
| `401 Unauthorized` | 인증 정보 없음 또는 유효하지 않은 Access Token |
| `403 Forbidden`    | `users.id`가 관리자 allow-list에 포함되지 않은 사용자 |
| `404 Not Found`    | 존재하지 않는 금칙어                      |

---

#### 닉네임 검증 정책

회원가입 및 게스트 로그인에서 닉네임은 다음 정책을 따릅니다.

| 정책  | 설명                     |
| --- | ---------------------- |
| 길이  | 2자 이상 12자 이하           |
| 중복  | 기존 회원/게스트 닉네임과 중복 불가   |
| 금칙어 | 관리자 API로 등록된 금칙어 포함 불가 |

금칙어 검증은 대소문자와 공백 우회를 고려합니다.

예를 들어 `admin`이 금칙어로 등록되어 있다면 아래 닉네임은 모두 차단됩니다.

```text
admin123
Admin123
a d m i n123
```

금칙어 포함 시 응답은 `400 Bad Request`입니다.

```json
{
  "code": "AUTH_NICKNAME_FORBIDDEN_WORD",
  "message": "금칙어가 포함된 닉네임은 사용할 수 없습니다.",
  "field": "nickname"
}
```

---
### 로비 (Lobby)

#### 로비 생성

```http
POST /api/lobbies
```

로비를 생성하고 6자리 초대 코드를 발급합니다.
JWT Access Token이 필요합니다. 게스트와 정식 회원 모두 생성 가능합니다.

`mapId`는 선택 사항입니다.
로비는 맵 없이 먼저 생성할 수 있으며, 게임 시작 시점에는 선택된 맵이 있어야 합니다.
`mapId`가 전달된 경우 백엔드에서 맵 존재 여부, 삭제 여부, 접근 권한을 검증합니다.

**Request Header**

| 헤더              | 필수 | 설명                     |
| --------------- | -- | ---------------------- |
| `Authorization` | ✅  | `Bearer {accessToken}` |

**Request Body — 맵 선택 로비**

```json
{
  "title": "K-POP 퀴즈방",
  "maxPlayers": 8,
  "isPrivate": false,
  "mapId": 1,
  "roundCount": 5,
  "timeLimitSeconds": 30
}
```

**Request Body — 맵 미선택 로비**

```json
{
  "title": "K-POP 퀴즈방",
  "maxPlayers": 8,
  "isPrivate": false,
  "mapId": null,
  "roundCount": 5,
  "timeLimitSeconds": 30
}
```

`mapId` 필드는 생략할 수도 있습니다.

```json
{
  "title": "K-POP 퀴즈방",
  "maxPlayers": 8,
  "isPrivate": false,
  "roundCount": 5,
  "timeLimitSeconds": 30
}
```

| 필드                 | 타입      | 필수 | 설명                                  |
| ------------------ | ------- | -- | ----------------------------------- |
| `title`            | String  | ✅  | 로비 제목 (최대 255자)                     |
| `maxPlayers`       | Integer | ✅  | 최대 참여 인원 (2~8)                      |
| `isPrivate`        | Boolean | ✅  | 비공개 여부                              |
| `mapId`            | Long    | ❌  | 로비에 연결할 맵 ID. 미선택 시 `null` 또는 생략 가능 |
| `roundCount`       | Integer | ❌  | 라운드 수 (1~20, 기본값 5)                 |
| `timeLimitSeconds` | Integer | ❌  | 제한 시간 초 (10~120, 기본값 30)            |

**Response `201 Created` — 맵 선택 로비**

```json
{
  "lobbyId": 1,
  "inviteCode": "ABC123",
  "title": "K-POP 퀴즈방",
  "maxPlayers": 8,
  "isPrivate": false,
  "status": "WAITING",
  "mapId": 1,
  "mapTitle": "K-POP 2세대",
  "mapCategory": "K-POP"
}
```

**Response `201 Created` — 맵 미선택 로비**

```json
{
  "lobbyId": 1,
  "inviteCode": "ABC123",
  "title": "K-POP 퀴즈방",
  "maxPlayers": 8,
  "isPrivate": false,
  "status": "WAITING",
  "mapId": null,
  "mapTitle": null,
  "mapCategory": null
}
```

| 필드            | 타입      | 설명                                                 |
| ------------- | ------- | -------------------------------------------------- |
| `lobbyId`     | Long    | DB GAME_LOBBY.id                                   |
| `inviteCode`  | String  | 6자리 초대 코드                                          |
| `title`       | String  | 로비 제목                                              |
| `maxPlayers`  | Integer | 최대 참여 인원                                           |
| `isPrivate`   | Boolean | 비공개 여부                                             |
| `status`      | String  | 로비 상태 (`WAITING`)                                  |
| `mapId`       | Long    | 선택된 맵 ID (미선택 시 `null`)                            |
| `mapTitle`    | String  | 선택된 맵 제목 (미선택 시 `null`)                            |
| `mapCategory` | String  | 선택된 맵 카테고리 (`K-POP`, `J-POP`, `POP`, 미선택 시 `null`) |

**맵 검증 정책**

| 상황          | 결과              |
| ----------- | --------------- |
| `mapId` 없음  | 맵 미선택 로비로 생성 허용 |
| 공개 맵        | 로비 연결 허용        |
| 본인 소유 비공개 맵 | 로비 연결 허용        |
| 타인 소유 비공개 맵 | 로비 연결 거부        |
| 삭제된 맵       | 로비 연결 거부        |
| 존재하지 않는 맵   | 로비 연결 거부        |

**Error**

| 상태 코드                     | 설명                              |
| ------------------------- | ------------------------------- |
| `400 Bad Request`         | 요청 검증 실패 (`mapId`가 양수가 아닌 경우 등) |
| `401 Unauthorized`        | JWT 토큰 없음 또는 만료                 |
| `403 Forbidden`           | 타인 소유 비공개 맵을 로비에 연결하려는 경우       |
| `404 Not Found`           | 존재하지 않는 사용자 또는 존재하지 않는 맵        |
| `409 Conflict`            | 삭제된 맵을 로비에 연결하려는 경우             |
| `503 Service Unavailable` | 초대 코드 생성 실패 (재시도 초과)            |

---

#### 초대 코드 기반 로비 입장

```http
POST /api/lobbies/join
```

초대 코드로 로비 입장 가능 여부를 검증하고 로비 기본 정보를 반환합니다.
JWT Access Token이 필요합니다. 게스트와 정식 회원 모두 입장 가능합니다.

> 이 API는 입장 허가 사전 검증만 수행합니다.  
> 실제 참여자 등록은 응답 수신 후 WebSocket `/topic/lobby/{inviteCode}` 구독 시점에 처리됩니다.

클라이언트 처리 순서:

```text
1. POST /api/lobbies/join 호출
2. WebSocket CONNECT
3. SUBSCRIBE /topic/lobby/{inviteCode}
4. 실제 Redis participants 등록
```

**Request Header**

| 헤더              | 필수 | 설명                     |
| --------------- | -- | ---------------------- |
| `Authorization` | ✅  | `Bearer {accessToken}` |

**Request Body**

```json
{
  "inviteCode": "ABC123"
}
```

| 필드           | 타입     | 필수 | 설명                      |
| ------------ | ------ | -- | ----------------------- |
| `inviteCode` | String | ✅  | 6자리 초대 코드 (영문 대문자 + 숫자) |

**Response `200 OK` — 맵 선택 로비**

```json
{
  "inviteCode": "ABC123",
  "title": "K-POP 퀴즈방",
  "hostId": "f8f6aa1b-3dd8-4b20-8ec8-9f7c7e0dd0fc",
  "maxPlayers": 8,
  "currentPlayers": 3,
  "status": "WAITING",
  "mapId": 1,
  "mapTitle": "K-POP 2세대",
  "mapCategory": "K-POP"
}
```

**Response `200 OK` — 맵 미선택 로비**

```json
{
  "inviteCode": "ABC123",
  "title": "K-POP 퀴즈방",
  "hostId": "f8f6aa1b-3dd8-4b20-8ec8-9f7c7e0dd0fc",
  "maxPlayers": 8,
  "currentPlayers": 3,
  "status": "WAITING",
  "mapId": null,
  "mapTitle": null,
  "mapCategory": null
}
```

| 필드               | 타입      | 설명                                                 |
| ---------------- | ------- | -------------------------------------------------- |
| `inviteCode`     | String  | 로비 초대 코드                                           |
| `title`          | String  | 로비 제목                                              |
| `hostId`         | String  | 방장 사용자 식별자                                         |
| `maxPlayers`     | Integer | 최대 참여 인원                                           |
| `currentPlayers` | Integer | 현재 참여 인원 (응답 시점 스냅샷)                               |
| `status`         | String  | 로비 상태 (`WAITING`)                                  |
| `mapId`          | Long    | 선택된 맵 ID (미선택 시 `null`)                            |
| `mapTitle`       | String  | 선택된 맵 제목 (미선택 시 `null`)                            |
| `mapCategory`    | String  | 선택된 맵 카테고리 (`K-POP`, `J-POP`, `POP`, 미선택 시 `null`) |

**Error**

| 상태 코드              | 설명                        |
| ------------------ | ------------------------- |
| `400 Bad Request`  | 초대 코드 형식 오류               |
| `401 Unauthorized` | JWT 토큰 없음 또는 만료           |
| `404 Not Found`    | 존재하지 않는 초대 코드             |
| `409 Conflict`     | 게임이 이미 시작된 로비 또는 최대 인원 초과 |

**REST join 성공 후 WebSocket SUBSCRIBE 실패 처리 기준**

`POST /api/lobbies/join`은 UX용 사전 검증입니다.  
실제 참여자 등록은 `SUBSCRIBE /topic/lobby/{inviteCode}` 시점에 `enter_lobby.lua`로 원자 처리됩니다.

따라서 REST join이 성공했더라도 WebSocket SUBSCRIBE 시점에 로비 상태가 바뀌면 STOMP ERROR가 발생할 수 있습니다.

클라이언트는 로비 입장을 다음 순서로 처리해야 합니다.

```text
1. POST /api/lobbies/join 호출
2. 응답 성공 시 WebSocket CONNECT
3. SUBSCRIBE /topic/lobby/{inviteCode}
4. SUBSCRIBE 성공 후 로비 상세 조회 또는 refresh 이벤트 대기
5. SUBSCRIBE 실패 시 STOMP ERROR payload의 action 기준으로 처리
```

| 상황 | 이유 | FE 처리 |
| --- | --- | --- |
| REST join 성공 후 다른 사용자가 먼저 입장 | SUBSCRIBE 시점에 `FULL` 발생 | `RETURN_TO_LOBBY_LIST` |
| REST join 성공 후 방장이 게임 시작 | SUBSCRIBE 시점에 `LOBBY_NOT_WAITING` 발생 | `RETURN_TO_LOBBY_LIST` |
| REST join 성공 후 로비 삭제 | SUBSCRIBE 시점에 `LOBBY_NOT_FOUND` 발생 | `RETURN_TO_LOBBY_LIST` |
| 강퇴된 유저가 재입장 시도 | kicked Set 기준으로 `KICKED_USER` 발생 | `RETURN_TO_LOBBY_LIST` |
| 같은 userIdentifier로 여러 세션이 경합 | 최신 sessionSequence 기준 stale 세션 차단 | `RECONNECT` |
| Redis/Lua 일시 장애 | 최종 입장 상태 확인 불가 | `REFRESH_AND_RETRY` |

> FE는 `POST /api/lobbies/join` 성공만으로 사용자를 로비 참여자로 확정하면 안 됩니다.  
> 실제 로비 참여 확정 기준은 `SUBSCRIBE /topic/lobby/{code}` 성공입니다.

---

#### 공개 로비 목록 조회

```http
GET /api/lobbies
```

공개(isPrivate = false) 로비 중 목록에 노출 가능한 로비를 페이징 형태로 반환합니다.

공개 로비 목록에는 WAITING, PLAYING 상태 로비가 노출됩니다.
FINISHED 상태 로비는 목록에서 제외됩니다.

성능 최적화를 위해 keyword, mapCategory 필터가 없는 경우에는 Redis Sorted Set 정렬 인덱스에서 필요한 범위의 로비 코드만 조회합니다.
필터가 있는 경우에는 전체 공개 로비 원본을 조회한 뒤 서비스 계층에서 필터링/정렬/페이징을 수행합니다.

**목록 노출 정책**

- 공개 로비 목록에는 WAITING, PLAYING 상태 로비가 노출됩니다.
- FINISHED 상태 로비는 목록에서 제외됩니다.
- WAITING 로비는 입장 가능한 로비입니다.
- PLAYING 로비는 진행 중 상태로 목록에는 노출되지만, 현재 입장은 허용하지 않습니다.
- 클라이언트는 status=PLAYING 로비를 “진행 중” 상태로 표시하고 입장 버튼을 비활성화해야 합니다.

> PLAYING 로비는 WebSocket 입장 단계에서 차단됩니다.
> 따라서 목록에는 노출되지만, 클라이언트는 status=PLAYING 로비를 “진행 중” 상태로 표시하고 입장 버튼을 비활성화해야 합니다.

**Query Parameters**

| 파라미터          | 타입      | 필수 | 기본값      | 설명                                                        |
| ------------- | ------- | -- | -------- | --------------------------------------------------------- |
| `keyword`     | String  | ❌  | 없음       | 로비 제목 검색어. 앞뒤 공백은 제거되며, 대소문자를 구분하지 않습니다.                  |
| `mapCategory` | String  | ❌  | 없음       | 맵 카테고리 필터. `K-POP`, `J-POP`, `POP`을 지원합니다.                |
| `sort`        | String  | ❌  | `latest` | 정렬 기준. `latest`, `most_players`, `most_available`을 지원합니다. |
| `page`        | Integer | ❌  | `0`      | 0-based 페이지 번호입니다.                                        |
| `size`        | Integer | ❌  | `20`     | 페이지 크기입니다. 최소 `1`, 최대 `100`까지 요청할 수 있습니다.                 |

**정렬 기준**

| 값                | 설명                    | Redis 인덱스                     |
| ---------------- | --------------------- | ----------------------------- |
| `latest`         | 최신 생성 로비 순            | `lobby:public:latest`         |
| `most_players` | 현재 인원이 많은 순. 동률은 Redis ZSET member 순서 기준 | `lobby:public:most_players` |
| `most_available` | 빈자리가 많은 순. 동률은 Redis ZSET member 순서 기준 | `lobby:public:most_available` |

> `most_players`, `most_available`는 Redis ZSET score 기준으로 정렬됩니다.  
> 같은 score를 가진 로비 간 최신순 보장은 하지 않습니다. 동률 최신순 보장이 필요하면 복합 score 또는 별도 인덱스 설계가 필요합니다.

**Redis 정렬 인덱스 사용 조건**

| 조건                                          | 동작                              |
| ------------------------------------------- | ------------------------------- |
| `keyword` 없음 + `mapCategory` 없음 + 정렬 인덱스 존재 | Redis ZSET에서 필요한 범위의 로비 코드만 조회  |
| `keyword` 있음                                | 기존 전체 공개 로비 조회 후 Java 필터/정렬/페이징 |
| `mapCategory` 있음                            | 기존 전체 공개 로비 조회 후 Java 필터/정렬/페이징 |
| 정렬 인덱스 없음                                   | 기존 전체 공개 로비 조회 후 Java 필터/정렬/페이징 |

**Request 예시**

```http
GET /api/lobbies
GET /api/lobbies?page=0&size=20
GET /api/lobbies?sort=latest&page=0&size=20
GET /api/lobbies?sort=most_players&page=0&size=20
GET /api/lobbies?sort=most_available&page=0&size=20
GET /api/lobbies?keyword=퀴즈&page=0&size=20
GET /api/lobbies?mapCategory=K-POP&page=0&size=20
GET /api/lobbies?keyword=애니&mapCategory=J-POP&sort=most_available&page=0&size=20
```

**Response `200 OK`**

```json
{
  "items": [
    {
      "code": "ABC123",
      "hostId": "f8f6aa1b-3dd8-4b20-8ec8-9f7c7e0dd0fc",
      "hostNickname": "모노유저",
      "title": "K-POP 퀴즈방",
      "mapId": 1,
      "mapTitle": "K-POP 2세대",
      "mapCategory": "K-POP",
      "maxPlayers": 8,
      "currentPlayers": 3,
      "isPrivate": false,
      "status": "WAITING",
      "createdAtEpochMillis": 1778990123456
    },
    {
      "code": "DEF456",
      "hostId": "b17f7ee0-614f-4f5f-b770-83f6d4b85f4a",
      "hostNickname": "게스트1234",
      "title": "진행 중인 POP 퀴즈방",
      "mapId": 3,
      "mapTitle": "POP 히트곡",
      "mapCategory": "POP",
      "maxPlayers": 6,
      "currentPlayers": 4,
      "isPrivate": false,
      "status": "PLAYING",
      "createdAtEpochMillis": 1778990000000
    }
  ],
  "page": 0,
  "size": 20,
  "hasNext": false
}
```

**Response Fields**

| 필드        | 타입      | 설명                |
| --------- | ------- | ----------------- |
| `items`   | Array   | 현재 페이지의 공개 로비 목록  |
| `page`    | Integer | 0-based 현재 페이지 번호 |
| `size`    | Integer | 요청한 페이지 크기        |
| `hasNext` | Boolean | 다음 페이지 존재 여부      |

**`items[]` Fields**

| 필드                     | 타입      | 설명                                                                                                      |
| ---------------------- | ------- | ------------------------------------------------------------------------------------------------------- |
| `code`                 | String  | 로비 초대 코드                                                                                                |
| `hostId`               | String  | 방장 사용자 식별자                                                                                              |
| `hostNickname`         | String  | 방장 닉네임. **항상 값이 존재하며 `null`이 아닙니다**(실제 닉네임 또는 fallback). 정식 회원·게스트 모두 내려옵니다. 세션 만료 등으로 닉네임을 찾지 못하면 `Unknown-xxxxxx` 형태의 fallback 값을 내려줍니다. 이때 `xxxxxx`는 식별자의 SHA-256 해시 앞 6자리(비가역)이며 세션/토큰 원문을 포함하지 않습니다. |
| `title`                | String  | 로비 제목                                                                                                   |
| `mapId`                | Long    | 선택된 맵 ID. 미선택 시 `null`                                                                                  |
| `mapTitle`             | String  | 선택된 맵 제목. 미선택 시 `null`                                                                                  |
| `mapCategory`          | String  | 선택된 맵 카테고리. `K-POP`, `J-POP`, `POP`, 미선택 시 `null`                                                       |
| `maxPlayers`           | Integer | 최대 참여 인원                                                                                                |
| `currentPlayers`       | Integer | 현재 참여 인원. Redis `lobby:{code}.current_players`를 우선 사용하고 없으면 participants Set 크기로 fallback합니다.           |
| `isPrivate`            | Boolean | 비공개 여부. 공개 로비 목록에서는 `false`                                                                             |
| `status`               | String  | 로비 상태. 공개 로비 목록에서는 `WAITING`, `PLAYING`만 반환. `WAITING`은 입장 가능, `PLAYING`은 진행 중으로 목록에는 노출되지만 입장은 허용하지 않음 |
| `createdAtEpochMillis` | Long    | 로비 생성 시각. Redis `TIME` 기준 epoch milliseconds                                                            |

**Error**

| 상태 코드             | 설명                                  |
| ----------------- | ----------------------------------- |
| `400 Bad Request` | 지원하지 않는 `sort` 값 또는 `mapCategory` 값 |
| `400 Bad Request` | `page`가 0보다 작은 경우                   |
| `400 Bad Request` | `size`가 1보다 작거나 최대값 100을 초과한 경우     |

---

#### 로비 상세 조회

```http
GET /api/lobbies/{code}
```

로비 대기실 상세 정보를 조회합니다.
JWT Access Token이 필요합니다.

응답에는 로비 기본 정보, 선택된 맵 정보, 라운드 설정, 참여자 목록, ready 상태, 게임 시작 가능 여부(`canStart`)가 포함됩니다.

**정책**

* 로비 참여자 또는 방장만 조회할 수 있습니다.
* 일반 유저는 WebSocket `/topic/lobby/{code}` 구독으로 participants Set에 등록된 이후 조회할 수 있습니다.
* 방장은 participants Set에 아직 없어도 조회할 수 있습니다.
* `canStart`는 조회 시점의 snapshot 값입니다.
* 실제 게임 시작 가능 여부는 `POST /api/lobbies/{code}/start`에서 Redis Lua로 최종 검증합니다.

**Request Header**

| 헤더              | 필수 | 설명                     |
| --------------- | -- | ---------------------- |
| `Authorization` | ✅  | `Bearer {accessToken}` |

**Response `200 OK`**

```json
{
  "inviteCode": "ABC123",
  "title": "K-POP 퀴즈방",
  "hostId": "f8f6aa1b-3dd8-4b20-8ec8-9f7c7e0dd0fc",
  "maxPlayers": 8,
  "currentPlayers": 2,
  "status": "WAITING",
  "mapId": 1,
  "mapTitle": "K-POP 2세대",
  "mapCategory": "K-POP",
  "roundCount": 5,
  "timeLimitSeconds": 30,
  "players": [
    {
      "userIdentifier": "f8f6aa1b-3dd8-4b20-8ec8-9f7c7e0dd0fc",
      "nickname": "방장닉네임",
      "host": true,
      "ready": false
    },
    {
      "userIdentifier": "b17f7ee0-614f-4f5f-b770-83f6d4b85f4a",
      "nickname": "참여자닉네임",
      "host": false,
      "ready": true
    }
  ],
  "canStart": true
}
```

| 필드                         | 타입      | 설명                                   |
| -------------------------- | ------- | ------------------------------------ |
| `inviteCode`               | String  | 로비 초대 코드                             |
| `title`                    | String  | 로비 제목                                |
| `hostId`                   | String  | 방장 userIdentifier                    |
| `maxPlayers`               | Integer | 최대 참여 인원                             |
| `currentPlayers`           | Integer | 현재 참여 인원                             |
| `status`                   | String  | 로비 상태                                |
| `mapId`                    | Long    | 선택된 맵 ID                             |
| `mapTitle`                 | String  | 선택된 맵 제목                             |
| `mapCategory`              | String  | 선택된 맵 카테고리 (`K-POP`, `J-POP`, `POP`) |
| `roundCount`               | Integer | 게임 라운드 수                             |
| `timeLimitSeconds`         | Integer | 라운드 제한 시간                            |
| `players`                  | Array   | 현재 로비 참여자 목록                         |
| `players[].userIdentifier` | String  | 참여자 식별자                              |
| `players[].nickname`       | String  | 참여자 닉네임                              |
| `players[].host`           | Boolean | 방장 여부                                |
| `players[].ready`          | Boolean | ready 여부. 방장은 ready 대상이 아니므로 `false` |
| `canStart`                 | Boolean | 조회 시점 기준 게임 시작 가능 여부                 |

**canStart 계산 조건**

| 조건                 | 기준                                             |
| ------------------ | ---------------------------------------------- |
| 로비 상태가 `WAITING`   | Redis `lobby:{code}.status`                    |
| 선택된 맵 존재           | Redis `lobby:{code}.map_id`                    |
| 맵 문제 수가 라운드 수 이상   | DB `map.num_of_song >= GAME_LOBBY.round_count` |
| 방장 제외 참여자 1명 이상    | Redis `lobby:{code}:participants`              |
| 방장 제외 모든 참여자 ready | Redis `lobby:{code}:ready`                     |

> `canStart=true` 이후에도 사용자가 퇴장하거나 ready를 해제하면 `/start` 요청은 `409 Conflict`로 실패할 수 있습니다.
> FE는 `/start` 실패 응답을 버튼 재활성화 및 안내 메시지로 처리해야 합니다.

**Error**

| 상태 코드              | 설명                       |
| ------------------ | ------------------------ |
| `401 Unauthorized` | JWT 토큰 없음 또는 만료          |
| `403 Forbidden`    | 로비 참여자가 아닌 사용자가 상세 조회 시도 |
| `404 Not Found`    | 존재하지 않는 로비               |

---

#### 로비 최근 채팅 조회

```http
GET /api/lobbies/{code}/chats/recent
````

로비 입장 또는 새로고침 시 Redis에 저장된 최근 로비 채팅 메시지를 조회합니다.

이 API는 채팅 영구 저장 기능이 아닙니다.
로비별 Redis List에 보관된 최근 N개의 메시지만 반환하며, 보관 개수와 TTL은 서버 설정값을 따릅니다.

**정책**

* JWT Access Token이 필요합니다.
* 현재 로비 참여자만 조회할 수 있습니다.
* 강퇴된 사용자는 조회할 수 없습니다.
* Redis 장애 또는 최근 채팅 캐시 조회 실패 시 빈 배열을 반환합니다.
* 응답은 오래된 메시지 → 최신 메시지 순서입니다.
* 응답 DTO는 WebSocket 로비 채팅 메시지와 동일한 구조를 사용합니다.

**Request Header**

| 헤더              | 필수 | 설명                     |
| --------------- | -- | ---------------------- |
| `Authorization` | ✅  | `Bearer {accessToken}` |

**Path Variable**

| 이름     | 타입     | 설명       |
| ------ | ------ | -------- |
| `code` | String | 로비 초대 코드 |

**Response `200 OK`**

```json
[
  {
    "messageId": "0f1b2c3d-1111-2222-3333-444444444444",
    "type": "CHAT",
    "roomId": "ABC123",
    "sender": "11111111-1111-1111-1111-111111111111",
    "senderId": 1,
    "senderNickname": "모노유저",
    "content": "안녕하세요",
    "timestamp": "2026-05-30T12:00:00.123Z",
    "sentAt": "2026-05-30T12:00:00.123Z"
  },
  {
    "messageId": "9a8b7c6d-1111-2222-3333-444444444444",
    "type": "CHAT",
    "roomId": "ABC123",
    "sender": "22222222-2222-2222-2222-222222222222",
    "senderId": 2,
    "senderNickname": "게스트123",
    "content": "준비됐어요",
    "timestamp": "2026-05-30T12:00:03.456Z",
    "sentAt": "2026-05-30T12:00:03.456Z"
  }
]
```

최근 채팅이 없거나 Redis 조회에 실패한 경우에도 `200 OK`와 빈 배열을 반환합니다.

```json
[]
```

**Response Fields**

| 필드               | 타입          | 설명                                                        |
| ---------------- | ----------- | --------------------------------------------------------- |
| `messageId`      | String      | 서버가 생성한 채팅 메시지 식별자. 채팅 메시지 신고 API에서 사용                    |
| `type`           | String      | 메시지 타입. 일반 사용자 채팅은 `CHAT`                                 |
| `roomId`         | String      | 로비 초대 코드                                                  |
| `sender`         | String      | 메시지 발신자 userIdentifier                                    |
| `senderId`       | Long|null   | 메시지 발신자 `users.id`. 조회 실패 또는 과거 payload 호환성 때문에 `null` 가능 |
| `senderNickname` | String|null | 메시지 발신자 닉네임. 조회 실패 또는 과거 payload 호환성 때문에 `null` 가능        |
| `content`        | String      | 채팅 메시지 본문                                                 |
| `timestamp`      | String      | 기존 FE 호환용 서버 생성 발신 시각. UTC ISO-8601 문자열                   |
| `sentAt`         | String      | 신규 의미 기반 발신 시각. UTC ISO-8601 문자열                          |

**Error**

| 상태 코드              | 설명                                  |
| ------------------ | ----------------------------------- |
| `401 Unauthorized` | JWT 토큰 없음, 만료, 또는 인증 주체가 유효하지 않은 경우 |
| `403 Forbidden`    | 로비 참여자가 아니거나 강퇴된 사용자가 조회하는 경우       |
| `404 Not Found`    | 존재하지 않는 로비                          |

**FE 처리 기준**

| 상황                 | 처리 방식                       |
| ------------------ | --------------------------- |
| `200 OK` + 메시지 배열  | 로비 채팅 UI 초기 메시지로 렌더링        |
| `200 OK` + 빈 배열    | 기존 메시지 없이 실시간 채팅만 표시        |
| `401 Unauthorized` | 로그인/게스트 세션 재진입 처리           |
| `403 Forbidden`    | 로비 접근 중단 후 목록 또는 이전 화면으로 이동 |
| `404 Not Found`    | 로비 목록으로 이동                  |

**권장 호출 순서**

```text
1. POST /api/lobbies/join 호출
2. WebSocket CONNECT
3. SUBSCRIBE /topic/lobby/{code}
4. GET /api/lobbies/{code}/chats/recent 호출
5. 이후 /topic/lobby/{code} 실시간 메시지 수신
```

> 최근 채팅 조회는 로비 참여 확정 이후 호출해야 합니다.
> REST join 성공만으로는 실제 로비 참여자가 아니며, 최종 참여 확정 기준은 WebSocket SUBSCRIBE 성공입니다.

---

#### 로비 채팅 메시지 신고


```http
POST /api/lobbies/{code}/chats/{messageId}/reports
```

특정 로비 안의 채팅 메시지를 신고합니다.
JWT Access Token이 필요하며, 게스트와 정식 회원 모두 신고할 수 있습니다.

**정책**

* 현재 로비 참여자만 신고할 수 있습니다.
* 강퇴된 사용자는 신고할 수 없습니다.
* 자기 자신이 작성한 채팅 메시지는 신고할 수 없습니다.
* 동일 사용자가 같은 로비의 같은 채팅 메시지에 대해 처리되지 않은 `PENDING` 신고를 중복 생성할 수 없습니다.
* Redis 최근 채팅은 TTL이 있으므로, 신고 접수 시점의 메시지 원문과 발신자 정보를 DB 스냅샷으로 저장합니다.
* 신고 대상 메시지는 Redis 최근 채팅 목록에 남아 있어야 합니다. TTL 만료 또는 최근 채팅 범위 밖으로 밀려난 메시지는 신고할 수 없습니다.

**Request Header**

| 헤더              | 필수 | 설명                     |
| --------------- | -- | ---------------------- |
| `Authorization` | ✅  | `Bearer {accessToken}` |

**Path Variable**

| 이름          | 타입     | 설명              |
| ----------- | ------ | --------------- |
| `code`      | String | 로비 초대 코드        |
| `messageId` | String | 신고 대상 채팅 메시지 ID |

**Request Body**

```json
{
  "reason": "부적절한 채팅 메시지입니다."
}
```

| 필드       | 타입     | 필수 | 설명                    |
| -------- | ------ | -- | --------------------- |
| `reason` | String | ✅  | 신고 사유. 공백 불가, 최대 500자 |

**Response `201 Created`**

```json
{
  "reportId": 100,
  "reporterId": 1,
  "lobbyId": 10,
  "targetType": "LOBBY_CHAT_MESSAGE",
  "targetId": 10,
  "reason": "부적절한 채팅 메시지입니다.",
  "status": "PENDING",
  "createdAt": "2026-05-30T12:00:00"
}
```

**Response Fields**

| 필드           | 타입     | 설명                                                       |
| ------------ | ------ | -------------------------------------------------------- |
| `reportId`   | Long   | 생성된 신고 ID                                                |
| `reporterId` | Long   | 신고자 `users.id`                                           |
| `lobbyId`    | Long   | 신고가 발생한 로비 ID                                            |
| `targetType` | String | 신고 대상 타입. 채팅 메시지 신고는 `LOBBY_CHAT_MESSAGE`                |
| `targetId`   | Long   | 공통 신고 구조 유지를 위해 로비 ID 저장. 실제 `messageId`는 신고 스냅샷 테이블에 저장 |
| `reason`     | String | trim 정규화된 신고 사유                                          |
| `status`     | String | 신고 처리 상태. 생성 직후 `PENDING`                                |
| `createdAt`  | String | 신고 접수 시각                                                 |

**Error**

| 상태 코드              | 설명                                                           |
| ------------------ | ------------------------------------------------------------ |
| `400 Bad Request`  | 신고 사유가 비어 있음, messageId 누락, 자기 자신의 메시지 신고                    |
| `401 Unauthorized` | JWT 토큰 없음, 만료, 또는 인증 주체가 유효하지 않은 경우                          |
| `403 Forbidden`    | 로비 참여자가 아니거나 강퇴된 사용자가 신고하는 경우                                |
| `404 Not Found`    | 존재하지 않는 로비, 존재하지 않는 신고자, Redis 최근 채팅에서 messageId를 찾을 수 없는 경우 |
| `409 Conflict`     | 삭제된 로비 신고, 이미 접수된 중복 신고, 신고할 수 없는 깨진 채팅 메시지 payload          |

**FE 처리 기준**

| 상황                 | 처리 방식                      |
| ------------------ | -------------------------- |
| `201 Created`      | 신고 완료 toast 표시             |
| `400 Bad Request`  | 자기 메시지 신고 또는 사유 입력 오류 안내   |
| `401 Unauthorized` | 로그인/게스트 세션 재진입 처리          |
| `403 Forbidden`    | 로비 접근 중단 또는 권한 없음 안내       |
| `404 Not Found`    | 메시지가 만료되었거나 찾을 수 없다는 안내    |
| `409 Conflict`     | 이미 신고했거나 신고할 수 없는 메시지라는 안내 |

**프론트엔드 연동 흐름**

```text
1. GET /api/lobbies/{code}/chats/recent 응답 또는 WebSocket 실시간 메시지에서 messageId를 확보
2. 사용자가 특정 메시지 신고 버튼 클릭
3. POST /api/lobbies/{code}/chats/{messageId}/reports 호출
4. 성공 시 신고 완료 안내
```

> `messageId`는 클라이언트가 생성하지 않습니다.
> 서버가 채팅 메시지를 수신한 뒤 생성한 값을 그대로 사용해야 합니다.

---

#### 로비 ready 상태 변경

```http
PATCH /api/lobbies/{code}/ready
```

로비 참여자의 ready 상태를 변경합니다.
JWT Access Token이 필요합니다.

**정책**

* 로비가 `WAITING` 상태일 때만 변경할 수 있습니다.
* 로비 참여자만 ready 상태를 변경할 수 있습니다.
* 방장은 ready 대상에서 제외됩니다.
* 방장은 ready 버튼이 아니라 게임 시작 버튼을 사용합니다.
* ready 변경 성공 시 `/topic/lobby/{code}/refresh`로 `REFRESH_LOBBY_INFO`가 브로드캐스트됩니다.

**Request Header**

| 헤더              | 필수 | 설명                     |
| --------------- | -- | ---------------------- |
| `Authorization` | ✅  | `Bearer {accessToken}` |

**Request Body**

```json
{
  "ready": true
}
```

| 필드      | 타입      | 필수 | 설명                              |
| ------- | ------- | -- | ------------------------------- |
| `ready` | Boolean | ✅  | `true` = 준비 완료, `false` = 준비 해제 |

**Response `204 No Content`**

응답 Body 없음.

**Error**

| 상태 코드              | 설명                          |
| ------------------ | --------------------------- |
| `400 Bad Request`  | 방장이 ready 변경을 시도한 경우        |
| `401 Unauthorized` | JWT 토큰 없음 또는 만료             |
| `403 Forbidden`    | 로비 참여자가 아닌 사용자가 ready 변경 시도 |
| `404 Not Found`    | 존재하지 않는 로비                  |
| `409 Conflict`     | `WAITING` 상태가 아닌 로비         |

---

#### 로비 맵 변경

```http
PATCH /api/lobbies/{code}/map
```

방장이 로비 대기실에서 게임에 사용할 맵을 변경합니다.
JWT Access Token이 필요합니다.

**정책**

* 로비 상태가 `WAITING`일 때만 변경할 수 있습니다.
* 방장만 맵을 변경할 수 있습니다.
* 공개 맵은 누구나 연결할 수 있습니다.
* 비공개 맵은 소유자만 연결할 수 있습니다.
* 맵 변경 성공 시 Redis `lobby:{code}` hash의 `map_id`, `map_title`, `map_category`와 DB `GAME_LOBBY.map_id`를 동기화합니다.
* Redis 선갱신 후 DB 갱신에 실패하면 Redis를 이전 값으로 보상 복구합니다.
* 변경 성공 시 `/topic/lobby/{code}/refresh`로 `REFRESH_LOBBY_INFO`가 브로드캐스트됩니다.

**Request Header**

| 헤더              | 필수 | 설명                     |
| --------------- | -- | ---------------------- |
| `Authorization` | ✅  | `Bearer {accessToken}` |

**Request Body**

```json
{
  "mapId": 2
}
```

| 필드      | 타입   | 필수 | 설명                  |
| ------- | ---- | -- | ------------------- |
| `mapId` | Long | ✅  | 연결할 맵 ID (양의 정수) |

**Response `204 No Content`**

응답 Body 없음.

**Error**

| 상태 코드                       | 설명                         |
| --------------------------- | -------------------------- |
| `400 Bad Request`           | `mapId`가 누락되었거나 양수가 아닌 경우  |
| `401 Unauthorized`          | JWT 토큰 없음 또는 만료            |
| `403 Forbidden`             | 방장이 아닌 사용자가 맵 변경 시도 또는 비공개 맵에 접근 권한 없음 |
| `404 Not Found`             | 존재하지 않는 로비 또는 맵            |
| `409 Conflict`              | `WAITING` 상태가 아닌 로비 또는 삭제된 맵 |
| `500 Internal Server Error` | Redis-DB 동기화 실패             |

---

#### 로비 게임 시작

```http
POST /api/lobbies/{code}/start
```

로비 게임 시작 조건을 최종 검증하고, 조건 충족 시 로비 상태를 `PLAYING`으로 전환합니다.
JWT Access Token이 필요하며, 방장만 호출할 수 있습니다.

**정책**

* 요청자는 방장이어야 합니다.
* 로비 상태는 `WAITING`이어야 합니다.
* 맵이 선택되어 있어야 합니다.
* 맵 문제 수가 `roundCount` 이상이어야 합니다.
* 방장을 제외한 참여자가 1명 이상 있어야 합니다.
* 방장을 제외한 모든 참여자가 ready 상태여야 합니다.
* participants Set에 남아 있지만 활성 로비 세션 키가 없는 사용자는 stale participant로 판단해 시작을 거부합니다.
* 조건 검증은 `start_lobby.lua`에서 Redis 기준으로 원자 처리합니다.
* 성공 시 Redis와 DB `GAME_LOBBY` 상태를 `PLAYING`으로 동기화합니다.
* 성공 후 DB 트랜잭션 커밋 이후 `/topic/lobby/{code}/game`으로 `GAME_STARTED` 이벤트를 브로드캐스트합니다.

**Request Header**

| 헤더              | 필수 | 설명                     |
| --------------- | -- | ---------------------- |
| `Authorization` | ✅  | `Bearer {accessToken}` |

**Response `204 No Content`**

응답 Body 없음.

**Error**

| 상태 코드                       | 설명                                                                  |
| --------------------------- | ------------------------------------------------------------------- |
| `401 Unauthorized`          | JWT 토큰 없음 또는 만료                                                     |
| `403 Forbidden`             | 방장이 아닌 사용자가 게임 시작 시도                                                |
| `404 Not Found`             | 존재하지 않는 로비 또는 맵                                                     |
| `409 Conflict`              | 로비 상태 불일치, 맵 미선택, 참여자 없음, ready 미완료, stale participant, 맵 문제 수 부족 등 |
| `500 Internal Server Error` | 게임 시작 상태 동기화 실패                                                     |
| `503 Service Unavailable`   | Redis Lua 처리 실패 또는 Redis 장애                                         |

**주의**

`GET /api/lobbies/{code}`의 `canStart`는 조회 시점 snapshot입니다.
실제 시작 가능 여부는 이 API에서 최종 검증됩니다.
따라서 `canStart=true` 이후에도 다음 상황에서는 `409 Conflict`가 발생할 수 있습니다.

* 참여자가 퇴장한 경우
* 참여자가 ready를 해제한 경우
* 로비 상태가 이미 변경된 경우
* participants Set에 stale 유저가 남아 있는 경우
* Redis participants/ready/session 정합성이 깨진 경우
* 이미 진행 중인 게임 세션이 존재하는 경우

---

### 맵 (Map)

#### 공개 맵 목록 조회

```http
GET /api/maps
```

공개(`is_public=true`) 상태이면서 삭제되지 않은 맵만 반환합니다.
페이지네이션 파라미터를 지원합니다.

| 쿼리 파라미터 |  기본값 | 설명              |
| ------- | ---: | --------------- |
| `page`  |  `0` | 0-based 페이지 번호  |
| `size`  | `20` | 페이지 크기 (최대 100) |

**Response `200 OK`**

```json
{
  "content": [
    {
      "id": 1,
      "title": "K-POP 2세대",
      "category": "kpop",
      "numOfSong": 20,
      "totalPlayTime": 600,
      "isPublic": true,
      "ownerId": 10
    }
  ],
  "page": 0,
  "size": 20,
  "totalElements": 1,
  "totalPages": 1,
  "hasNext": false
}
```

#### 공개 맵 단건 조회

```http
GET /api/maps/{mapId}
```

공개(`is_public=true`) 상태이면서 삭제되지 않은 맵만 조회할 수 있습니다.

**Response `200 OK`**

```json
{
  "id": 1,
  "ownerId": 10,
  "title": "K-POP 2세대",
  "description": "추억의 명곡 모음",
  "category": "kpop",
  "numOfSong": 20,
  "totalPlayTime": 600,
  "isPublic": true,
  "createdAt": "2026-05-06T10:00:00",
  "updatedAt": "2026-05-06T10:00:00"
}
```

#### 맵 생성

```http
POST /api/maps
```

정식 회원(`REGISTERED`)만 생성할 수 있습니다.

`category`는 아래 enum 값만 허용합니다.

* `kpop`
* `jpop`
* `pop`

#### 맵 수정

```http
PUT /api/maps/{mapId}
```

맵 소유자만 수정 가능하며, `isPublic` 필드로 공개/비공개 전환을 지원합니다.
수정 시 Redis 맵 캐시를 무효화합니다.

#### 맵 삭제

```http
DELETE /api/maps/{mapId}
```

맵 소유자만 삭제할 수 있으며, 물리 삭제 대신 Soft Delete(`is_deleted=true`) 처리합니다.

---

## WebSocket API (STOMP)

WebSocket 연결 후 STOMP 프로토콜로 통신합니다.
SockJS를 통해 WebSocket 연결 실패 시 HTTP 폴링으로 자동 대체됩니다.

### 연결

```text
CONNECT
userIdentifier: {UUID 또는 회원 ID}
```

| 헤더               | 필수 | 설명                               |
| ---------------- | -- | -------------------------------- |
| `userIdentifier` | ✅  | 게스트 UUID(8-4-4-4-12 포맷) 또는 회원 ID |

연결 실패 시 STOMP ERROR 프레임으로 사유를 반환합니다.

---

### 채팅

#### 전체 채팅 송신

```text
SEND /app/chat/global
```

**Body**

```json
{
  "type": "CHAT",
  "content": "안녕하세요!"
}
```

#### 전체 채팅 수신 구독

```text
SUBSCRIBE /topic/chat/global
```

**수신 메시지**

```json
{
  "type": "CHAT",
  "roomId": "global",
  "sender": "uuid-xxxx-xxxx",
  "content": "안녕하세요!",
  "timestamp": "2026-05-01T12:00:00"
}
```

#### 로비 채팅 송신

```text
SEND /app/chat/lobby/{code}
```

**Body**

```json
{
  "type": "CHAT",
  "content": "준비됐어요!"
}
```

#### 로비 채팅 수신 구독

```text
SUBSCRIBE /topic/lobby/{code}
```

**수신 메시지**

```json
{
  "type": "CHAT",
  "roomId": "ABC123",
  "sender": "uuid-xxxx-xxxx",
  "content": "준비됐어요!",
  "timestamp": "2026-05-01T12:00:00"
}
```

**메시지 타입 (`type`)**

| 값        | 설명                 |
| -------- | ------------------ |
| `CHAT`   | 일반 채팅 메시지          |
| `ANSWER` | 정답 제출 메시지 (인게임 전용) |
| `ENTER`  | 입장 알림 시스템 메시지      |
| `LEAVE`  | 퇴장 알림 시스템 메시지      |
| `KICK`   | 강퇴 알림 시스템 메시지      |

> `sender` 필드는 클라이언트 전송값을 무시하고 서버 세션에서 추출한 값으로 교체됩니다.

---

### 로비 이벤트

#### 로비 생성 알림 송신

```text
SEND /app/lobby/create
```

로비 생성 후 클라이언트가 호출합니다.
로비 리스트를 보고 있는 모든 클라이언트에게 새로고침 신호를 전송합니다.

#### 로비 리스트 새로고침 구독

```text
SUBSCRIBE /topic/lobby/refresh
```

**수신 메시지**

```text
"REFRESH_LOBBY_LIST"
```

#### 로비 정보 변경 알림 송신

```text
SEND /app/lobby/{code}/update
```

유저 입장, 퇴장, 준비, 맵 변경 등 로비 내부 상태 변경 시 클라이언트가 호출합니다.

#### 로비 내부 새로고침 구독

```text
SUBSCRIBE /topic/lobby/{code}/refresh
```

**수신 메시지**

```text
"REFRESH_LOBBY_INFO"
```

#### 로비 게임 시작 이벤트 구독

```text
SUBSCRIBE /topic/lobby/{code}/game
```

방장이 `POST /api/lobbies/{code}/start`를 성공적으로 호출하면, 서버가 DB 트랜잭션 커밋 이후 이 채널로 게임 시작 이벤트를 브로드캐스트합니다.

**수신 메시지**

```text
"GAME_STARTED"
```

**FE 처리**

* 이 메시지를 수신하면 로비 대기실에서 인게임 화면으로 전환합니다.
* 전환 전 필요하다면 `GET /api/lobbies/{code}`를 재조회해 `status=PLAYING`을 확인할 수 있습니다.

#### 인게임 라운드 시작 이벤트 구독

```text
SUBSCRIBE /topic/game/{code}/round
```

방장이 게임을 시작하거나 다음 라운드로 넘어갈 때, 서버가 이 채널로 라운드 시작 정보를 브로드캐스트합니다.

**수신 메시지 (RoundStartDto)**

```json
{
  "type": "ROUND_READY",
  "videoId": "dQw4w9WgXcQ",
  "youtubeUrl": "https://youtube.com/watch?v=dQw4w9WgXcQ",
  "startTime": 10,
  "endTime": 30,
  "timeLimitSeconds": 30,
  "roundNo": 1,
  "serverStartedAt": 1716500000000
}
```

> **참고**: 클라이언트 스포일러 방지를 위해 정답, 힌트, 제목, 아티스트 등의 메타데이터는 라운드 시작 시점에 전송되지 않으며, 정답 공개 시점에 별도의 채널과 DTO(`RoundMetadataDto`)를 통해 전송될 예정입니다.

#### 유튜브 IFrame 로딩 완료(Ready To Play) 송신

```text
SEND /app/game/{code}/ready-to-play
```

클라이언트가 `ROUND_READY` 메시지를 수신한 후, 유튜브 IFrame 로딩이 완료되면 호출합니다.
참가한 모든 플레이어가 해당 신호를 보내거나, 10초 타임아웃이 지나면 실제 라운드 재생(`ROUND_PLAYBACK_STARTED`)이 트리거됩니다.

**Body**

```json
{
  "roundNo": 1
}
```

#### 라운드 동영상 재생 시작 이벤트 수신

```text
SUBSCRIBE /topic/game/{code}/round
```
(이전 `ROUND_READY` 메시지와 동일한 채널로 수신)

모든 클라이언트의 준비 완료가 취합되거나, 10초 타임아웃이 발생하면 브로드캐스트됩니다. 클라이언트는 이 메시지를 받는 즉시 로드해둔 유튜브 영상을 재생합니다.

**수신 메시지 (RoundPlaybackStartedDto)**

```json
{
  "type": "ROUND_PLAYBACK_STARTED",
  "roundNo": 1,
  "serverStartedAt": 1716500000000,
  "durationSeconds": 30
}
```

#### 로비 유저 강퇴 송신

```text
SEND /app/lobby/{code}/kick
```

방장이 특정 유저를 로비에서 강퇴합니다.

**Body**

```json
{
  "targetUserIdentifier": "f8f6aa1b-3dd8-4b20-8ec8-9f7c7e0dd0fc"
}
```

**처리 결과**

* 요청자가 방장이 아니면 거부됩니다.
* 자기 자신은 강퇴할 수 없습니다.
* 강퇴 대상이 로비 참여자가 아니면 거부됩니다.
* 강퇴 성공 시 로비 채팅 채널로 `KICK` 메시지가 브로드캐스트됩니다.
* 강퇴 성공 후 로비 내부 새로고침 신호가 브로드캐스트됩니다.

---

## 🎮 인게임 (In-Game API)

인게임 세션 생성 이후 라운드 준비, IFrame 재생 동기화 및 단일 대화창 정답 판단에 사용되는 REST 및 STOMP API 규격입니다.

### REST API

#### 1) 서버 현재 시간 조회 (Clock Skew 보정용)
- **URL**: `GET /api/system/time`
- **설명**: YouTube IFrame API 재생 시간의 초 단위 동기화(Clock skew 보정)를 위해 서버 기준 밀리초를 조회합니다.
- **응답 (Response)**:
  ```json
  {
    "serverTime": 1716500000000
  }
  ```

#### 2) 현재 라운드 정보 조회 (새로고침 및 중도 합류용)
- **URL**: `GET /api/game/{code}/round/current`
- **설명**: 사용자가 게임 중간에 강제 새로고침을 하거나 세션이 일시 단절되었다 복구되었을 때, 뷰 복원을 지원하기 위해 현재 진행 중인 라운드의 세부 정보 및 재생 개시 시각을 반환합니다.
- **응답 (Response)**:
  ```json
  {
    "status": "PLAYING", // READY, PLAYING, FINISHED
    "currentRoundNo": 1,
    "timeLimitSeconds": 30,
    "playbackStartedAt": 1716500000000 // 아직 재생 전인 경우 null
  }
  ```

---

### STOMP API

#### 3) 유튜브 IFrame 로딩 완료 (Ready To Play) 송신
- **Destination**: `SEND /app/game/{code}/ready-to-play`
- **설명**: 클라이언트가 비디오 로드 완료 및 준비 상태를 서버에 전송합니다. 모든 참가자가 해당 신호를 보내거나, 10초 타임아웃이 지나면 실제 라운드 비디오 재생이 트리거됩니다.
- **Body**:
  ```json
  {
    "roundNo": 1
  }
  ```

#### 4) 인게임 라운드 시작 및 비디오 재생 이벤트 구독
- **Destination**: `SUBSCRIBE /topic/game/{code}/round`
- **설명**: 새로운 라운드가 준비되거나, 모든 참가자가 준비를 완료하여 비디오 재생을 개시할 때 전파되는 이벤트를 수신합니다.
- **라운드 준비 알림 (ROUND_READY)**:
  ```json
  {
    "type": "ROUND_READY",
    "videoId": "dQw4w9WgXcQ",
    "youtubeUrl": "https://youtube.com/watch?v=dQw4w9WgXcQ",
    "startTime": 10,
    "endTime": 40,
    "timeLimitSeconds": 30,
    "roundNo": 1,
    "serverStartedAt": 1716500000000
  }
  ```
  *(정답, 힌트 등은 스포일러 방지를 위해 ROUND_READY 시점에는 일절 포함되지 않습니다)*
- **재생 개시 알림 (ROUND_PLAYBACK_STARTED)**:
  ```json
  {
    "type": "ROUND_PLAYBACK_STARTED",
    "roundNo": 1,
    "serverStartedAt": 1716500000000,
    "durationSeconds": 30
  }
  ```
- **라운드 종료 및 결과 알림 (ROUND_END)**:
  - **구독 경로**: `SUBSCRIBE /topic/game/{code}/round-end`
  - **수신 메시지 (RoundMetadataDto)**:
    ```json
    {
      "type": "ROUND_END",
      "title": "곡 제목",
      "artist": "가수명",
      "answer": "대표 정답",
      "thumbnailUrl": "https://img.youtube.com/vi/.../maxresdefault.jpg",
      "rankings": [
        {
          "userIdentifier": "f8f6aa1b-3dd8-4b20-8ec8-9f7c7e0dd0fc",
          "nickname": "유저닉네임1",
          "score": 140,
          "rank": 1,
          "scoreAdded": 140
        },
        {
          "userIdentifier": "a9f8bb2c-4dd9-4b30-9fc9-9f7c7e0ee1fd",
          "nickname": "유저닉네임2",
          "score": 100,
          "rank": 2,
          "scoreAdded": 100
        }
      ]
    }
    ```

#### 5) 인게임 전용 채팅 송신 및 브로드캐스트
- **송신**: `SEND /app/game/{code}/chat`
- **Body**:
  ```json
  {
    "roundNo": 1,
    "content": "제출할 채팅 혹은 정답"
  }
  ```
- **구독 (수신)**: `SUBSCRIBE /topic/game/{code}/chat`
  - **일반 채팅 및 오답 수신 (CHAT)**:
    ```json
    {
      "type": "CHAT",
      "roomId": "GAME12",
      "sender": "닉네임",
      "content": "가나다라마",
      "timestamp": "2026-05-30T21:46:52"
    }
    ```
    *(정답을 이미 맞춘 사람이 채팅에 정답을 포함할 경우, 스포일러 방지를 위해 본문 content가 "***" 로 마스킹 처리되어 브로드캐스트됩니다)*
  - **최초 정답 공지 (SYSTEM)**: 미정답자였던 사용자가 정답을 입력하면 본문은 차단되고 전체 사용자에게 시스템 정답 공지가 브로드캐스트됩니다.
    ```json
    {
      "type": "SYSTEM",
      "roomId": "GAME12",
      "sender": "SYSTEM",
      "content": "닉네임님이 정답을 맞췄습니다!",
      "timestamp": "2026-05-30T21:46:52"
    }
    ```

#### 6) 정답 성공 개별 통지 구독
- **Destination**: `SUBSCRIBE /user/queue/game/answers`
- **설명**: 자신이 제출한 채팅이 정답으로 승인되었을 때, 개별 축하 메시지 및 오타 허용(Fuzzy Match) 여부를 직접 통지 받습니다.
- **Response**:
  ```json
  {
    "type": "ROUND_CORRECT",
    "roundNo": 1,
    "isFuzzy": true, // 오타 허용으로 맞춘 경우 true, 완전 일치일 시 false
    "message": "오타 허용 정답입니다!"
  }
  ```

---

## 에러 응답

### STOMP ERROR 프레임

인증 실패, 유효하지 않은 요청, 로비 입장 실패 등 WebSocket 처리 중 클라이언트가 복구 가능한 방식으로 판단해야 하는 오류는 STOMP `ERROR` 프레임으로 응답합니다.

기존에는 ERROR body에 문자열 메시지만 내려갔지만, 로비 입장 실패 케이스를 FE가 안정적으로 처리할 수 있도록 JSON payload를 표준 응답 형식으로 사용합니다.

#### STOMP ERROR Payload

```json
{
  "type": "STOMP_ERROR",
  "code": "LOBBY_NOT_FOUND",
  "message": "존재하지 않는 로비입니다.",
  "action": "RETURN_TO_LOBBY_LIST",
  "recoverable": false,
  "timestamp": "2026-05-25T00:00:00Z"
}
```

| 필드 | 타입 | 설명 |
| --- | --- | --- |
| `type` | String | 고정값 `STOMP_ERROR` |
| `code` | String | 클라이언트 분기용 에러 코드 |
| `message` | String | 사용자에게 표시 가능한 메시지 |
| `action` | String | FE가 수행해야 하는 후속 동작 |
| `recoverable` | Boolean | 같은 화면에서 재시도 가능한 오류인지 여부 |
| `timestamp` | String | 서버에서 ERROR payload를 생성한 시각 |

#### FE 처리 원칙

FE는 `message` 문자열을 파싱하지 말고, 반드시 `code`, `action`, `recoverable` 기준으로 분기해야 합니다.

| action | FE 권장 처리 |
| --- | --- |
| `RETURN_TO_LOBBY_LIST` | 현재 로비 입장을 중단하고 로비 목록 또는 이전 화면으로 복귀 |
| `RETRY_CONNECT` | WebSocket 연결 자체를 다시 시도 |
| `REFRESH_AND_RETRY` | 현재 화면 상태를 새로고침한 뒤 다시 시도 |
| `RECONNECT` | 현재 WebSocket 세션을 폐기하고 새 세션으로 재연결 |
| `NONE` | 별도 화면 전환 없이 현재 상태 유지 |

#### CONNECT 실패 코드

| code | message | action | recoverable |
| --- | --- | --- | --- |
| `CONNECT_USER_IDENTIFIER_MISSING` | 사용자 식별자가 없습니다. 다시 로그인 후 접속해주세요. | `RETRY_CONNECT` | `true` |
| `CONNECT_USER_IDENTIFIER_INVALID` | 유효하지 않은 사용자 식별자입니다. 다시 로그인 후 접속해주세요. | `RETRY_CONNECT` | `true` |
| `CONNECT_SESSION_SEQUENCE_FAILED` | WebSocket 세션 생성에 실패했습니다. 다시 접속해주세요. | `RETRY_CONNECT` | `true` |
| `CONNECT_WS_SESSION_ID_MISSING` | WebSocket 세션 ID가 없습니다. 다시 접속해주세요. | `RETRY_CONNECT` | `true` |
| `CONNECT_ONLINE_STATUS_FAILED` | 사용자 온라인 상태 저장에 실패했습니다. 잠시 후 다시 접속해주세요. | `RETRY_CONNECT` | `true` |

#### 인증/세션 실패 코드

| code | message | action | recoverable |
| --- | --- | --- | --- |
| `SESSION_UNAUTHENTICATED` | 인증 정보가 존재하지 않습니다. 다시 접속해주세요. | `RETRY_CONNECT` | `true` |
| `LOBBY_ENTER_WS_SESSION_MISSING` | 로비 입장에 필요한 WebSocket 세션 ID가 없습니다. 다시 접속해주세요. | `RECONNECT` | `true` |
| `LOBBY_ENTER_SESSION_ATTRIBUTES_MISSING` | 로비 입장에 필요한 세션 정보가 없습니다. 새로고침 후 다시 시도해주세요. | `REFRESH_AND_RETRY` | `true` |
| `LOBBY_ENTER_SEQUENCE_MISSING` | WebSocket 세션 순서 정보가 없습니다. 새로고침 후 다시 시도해주세요. | `REFRESH_AND_RETRY` | `true` |

#### 로비 입장 실패 코드

`POST /api/lobbies/join`은 입장 가능 여부를 확인하는 사전 검증 API입니다.  
실제 참여자 등록은 WebSocket `SUBSCRIBE /topic/lobby/{code}` 시점에 `enter_lobby.lua`로 원자 처리됩니다.

따라서 REST join이 성공했더라도, WebSocket SUBSCRIBE 시점의 최종 상태가 달라지면 STOMP ERROR가 발생할 수 있습니다.

| code | 발생 조건 | message | action | recoverable |
| --- | --- | --- | --- | --- |
| `LOBBY_NOT_FOUND` | 로비가 삭제되었거나 존재하지 않음 | 존재하지 않는 로비입니다. | `RETURN_TO_LOBBY_LIST` | `false` |
| `LOBBY_FULL` | REST join 이후 다른 사용자가 먼저 입장하여 정원이 찬 경우 | 로비 최대 인원에 도달했습니다. | `RETURN_TO_LOBBY_LIST` | `false` |
| `LOBBY_NOT_WAITING` | 로비가 이미 `PLAYING` 또는 `FINISHED` 상태로 변경된 경우 | 이미 시작되었거나 입장할 수 없는 로비입니다. | `RETURN_TO_LOBBY_LIST` | `false` |
| `LOBBY_INVALID_CAPACITY` | Redis 로비 정원 정보가 없거나 유효하지 않은 경우 | 로비 정원 정보가 유효하지 않습니다. | `RETURN_TO_LOBBY_LIST` | `false` |
| `LOBBY_STALE_SESSION` | 더 최신 WebSocket 세션이 이미 존재하는 경우 | 더 최신 WebSocket 세션이 이미 존재합니다. 다시 접속해주세요. | `RECONNECT` | `true` |
| `LOBBY_KICKED_USER` | 강퇴된 사용자가 같은 로비에 재입장하려는 경우 | 강퇴된 로비에는 재입장할 수 없습니다. | `RETURN_TO_LOBBY_LIST` | `false` |
| `LOBBY_INVALID_SEQUENCE` | WebSocket sessionSequence가 유효하지 않은 경우 | 로비 입장 세션 상태가 유효하지 않습니다. 새로고침 후 다시 시도해주세요. | `REFRESH_AND_RETRY` | `true` |
| `LOBBY_ENTER_UNKNOWN_RESULT` | `enter_lobby.lua`가 알 수 없는 반환값을 반환한 경우 | 로비 입장 중 알 수 없는 서버 응답이 발생했습니다. 새로고침 후 다시 시도해주세요. | `REFRESH_AND_RETRY` | `true` |
| `LOBBY_ENTER_TEMPORARILY_UNAVAILABLE` | Redis/Lua 실행이 일시적으로 실패한 경우 | 일시적으로 로비 입장 상태를 확인할 수 없습니다. 새로고침 후 다시 시도해주세요. | `REFRESH_AND_RETRY` | `true` |

#### 서버 내부 오류 코드

| code | message | action | recoverable |
| --- | --- | --- | --- |
| `INTERNAL_STOMP_ERROR` | WebSocket 처리 중 서버 오류가 발생했습니다. | `REFRESH_AND_RETRY` | `true` |

#### REST join 성공 후 WebSocket SUBSCRIBE 실패 처리 기준

클라이언트는 로비 입장을 다음 순서로 처리해야 합니다.

```text
1. POST /api/lobbies/join 호출
2. 응답 성공 시 WebSocket CONNECT
3. SUBSCRIBE /topic/lobby/{inviteCode}
4. SUBSCRIBE 성공 후 로비 상세 조회 또는 refresh 이벤트 대기
5. SUBSCRIBE 실패 시 STOMP ERROR payload의 action 기준으로 처리
```

REST join 성공 후에도 다음 상황에서는 WebSocket SUBSCRIBE가 실패할 수 있습니다.

| 상황 | 이유 | FE 처리 |
| --- | --- | --- |
| REST join 성공 후 다른 사용자가 먼저 입장 | SUBSCRIBE 시점에 `FULL` 발생 | `RETURN_TO_LOBBY_LIST` |
| REST join 성공 후 방장이 게임 시작 | SUBSCRIBE 시점에 `LOBBY_NOT_WAITING` 발생 | `RETURN_TO_LOBBY_LIST` |
| REST join 성공 후 로비 삭제 | SUBSCRIBE 시점에 `LOBBY_NOT_FOUND` 발생 | `RETURN_TO_LOBBY_LIST` |
| 강퇴된 유저가 재입장 시도 | kicked Set 기준으로 `KICKED_USER` 발생 | `RETURN_TO_LOBBY_LIST` |
| 같은 userIdentifier로 여러 세션이 경합 | 최신 sessionSequence 기준 stale 세션 차단 | `RECONNECT` |
| Redis/Lua 일시 장애 | 최종 입장 상태 확인 불가 | `REFRESH_AND_RETRY` |

> FE는 `POST /api/lobbies/join` 성공만으로 사용자를 로비 참여자로 확정하면 안 됩니다.  
> 실제 로비 참여 확정 기준은 `SUBSCRIBE /topic/lobby/{code}` 성공입니다.

---

## 향후 추가 예정 API

현재 구현된 기능 외에 아래 API가 추가될 예정입니다.

| 분류                 | 설명                                            |
| ------------------ | --------------------------------------------- |
| 맵 아이템(문제) CRUD     | `GET/POST/PUT/DELETE /api/maps/{mapId}/items` |
| YouTube URL 유효성 검증 | `POST /api/youtube/validate`                  |
| 로비 맵 변경            | `PATCH /api/lobbies/{inviteCode}/map`         |
