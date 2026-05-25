# Monomat-BE API 명세서

## 공통 사항

* **Base URL** : `http://{서버 도메인}:8080`
* **WebSocket Endpoint** : `ws://{서버 도메인}:8080/ws` (SockJS 폴백 지원)
* **Content-Type** : `application/json`
* **REST 인증** : `Authorization: Bearer {accessToken}`
* **WebSocket 인증** : STOMP CONNECT 헤더에 `userIdentifier` 포함 필요

---

## REST API

### 인증 (Auth)

#### 게스트 로그인

```http
POST /api/auth/guest
```

닉네임 입력만으로 게스트 계정을 생성하고 `UUID(userIdentifier)` 기반 세션을 발급합니다.
응답으로 Access/Refresh 토큰이 함께 반환되며, 게스트 세션 정보는 Redis에 30일 TTL로 저장됩니다.

**Request**

```json
{
  "nickname": "게스트닉네임"
}
```

**Response `200 OK`**

```json
{
  "userId": 1,
  "nickname": "게스트닉네임",
  "userType": "GUEST",
  "userIdentifier": "f8f6aa1b-3dd8-4b20-8ec8-9f7c7e0dd0fc",
  "accessToken": "eyJhbGciOi...",
  "accessTokenExpiresAt": "2026-05-03T07:45:00Z",
  "refreshToken": "eyJhbGciOi...",
  "refreshTokenExpiresAt": "2026-06-02T07:30:00Z"
}
```

**Error `409 CONFLICT`**

* 정식 회원 닉네임과 충돌: `정식 회원이 이미 사용 중인 닉네임입니다.`
* 기존 사용자 닉네임과 충돌: `이미 사용 중인 닉네임입니다.`

---

#### 회원가입

```http
POST /api/auth/register
```

로그인 ID/비밀번호/닉네임으로 정식 회원 계정을 생성합니다.
회원가입 API는 계정 생성만 수행하며 토큰 발급은 로그인 API에서 처리됩니다.

**Request**

```json
{
  "loginId": "member01",
  "password": "password123",
  "nickname": "registered-user"
}
```

**Response `201 Created`**

```json
{
  "userId": 2,
  "loginId": "member01",
  "nickname": "registered-user",
  "userType": "REGISTERED"
}
```

**Error**

* `400 Bad Request`: 필수값 누락 / 비밀번호 길이 조건 불만족 / 비밀번호 공백 포함 / 닉네임 8자 초과
* `409 Conflict`: 로그인 ID 또는 닉네임 중복

---

#### 자체 로그인

```http
POST /api/auth/login
```

가입한 로그인 ID/비밀번호로 인증 후 Access/Refresh 토큰을 발급합니다.
로그인 성공 시 `user_sessions`에 세션 추적 정보를 저장하며, Refresh Token은 Redis에 저장됩니다.

**Request**

```json
{
  "loginId": "member01",
  "password": "password123"
}
```

**Response `200 OK`**

```json
{
  "userId": 2,
  "loginId": "member01",
  "nickname": "registered-user",
  "userType": "REGISTERED",
  "userIdentifier": "b17f7ee0-614f-4f5f-b770-83f6d4b85f4a",
  "accessToken": "eyJhbGciOi...",
  "accessTokenExpiresAt": "2026-05-03T07:45:00Z",
  "refreshToken": "eyJhbGciOi...",
  "refreshTokenExpiresAt": "2026-06-02T07:30:00Z"
}
```

**Error**

* `400 Bad Request`: 필수값 누락 / 공백 포함
* `401 Unauthorized`: 로그인 ID 또는 비밀번호 불일치
* `423 Locked`: 로그인 실패 5회 누적 계정 잠금 (15분)

---

#### 토큰 재발급 (RTR)

```http
POST /api/auth/refresh
```

Refresh Token을 검증하고 `Refresh Token Rotation` 정책으로 Access/Refresh 토큰을 재발급합니다.  
유효하지 않거나 재사용이 감지된 Refresh Token 요청은 거부되며, 보안 위협으로 판단될 경우 사용자 활성 세션이 서버에서 강제 종료됩니다.

**Request**

```json
{
  "refreshToken": "eyJhbGciOi..."
}
```

**Response `200 OK`**

```json
{
  "userId": 2,
  "userType": "REGISTERED",
  "userIdentifier": "b17f7ee0-614f-4f5f-b770-83f6d4b85f4a",
  "accessToken": "eyJhbGciOi...",
  "accessTokenExpiresAt": "2026-05-03T07:45:00Z",
  "refreshToken": "eyJhbGciOi...",
  "refreshTokenExpiresAt": "2026-06-02T07:30:00Z"
}
```

**Error**

- `400 Bad Request`: `refreshToken` 누락/공백
- `401 Unauthorized`: 유효하지 않거나 만료된 Refresh Token
- `503 Service Unavailable`: 세션 저장소(Redis) 일시 장애

---

#### 로그아웃

```http
POST /api/auth/logout
```

Access Token 기반으로 요청자를 식별하여 로그아웃 처리합니다.  
요청에 사용된 Access Token은 블랙리스트 처리되고, 현재 세션의 Refresh Token은 즉시 폐기됩니다.

**Request Header**

| 헤더 | 필수 | 설명 |
|---|---|---|
| `Authorization` | ✅ | `Bearer {accessToken}` |

**Response `200 OK`**

```json
{
  "message": "로그아웃이 완료되었습니다."
}
```

**Error**

- `401 Unauthorized`: 인증 실패 또는 잘못된 Authorization 헤더

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
| `players[].host`           | Boolean | 방장 여부                                |
| `players[].ready`          | Boolean | ready 여부. 방장은 ready 대상이 아니므로 `false` |
| `canStart`                 | Boolean | 조회 시점 기준 게임 시작 가능 여부                 |
| `players[].nickname` | String | 참여자 닉네임 |

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
  "videoId": "dQw4w9WgXcQ",
  "startTime": 10,
  "endTime": 30,
  "roundNo": 1,
  "serverTime": 1716500000000
}
```

> **참고**: 클라이언트 스포일러 방지를 위해 정답, 힌트, 제목, 아티스트 등의 메타데이터는 라운드 시작 시점에 전송되지 않으며, 정답 공개 시점에 별도의 채널과 DTO(`RoundMetadataDto`)를 통해 전송될 예정입니다.

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

## 에러 응답

### STOMP ERROR 프레임

인증 실패 또는 유효하지 않은 요청 시 STOMP ERROR 프레임으로 응답합니다.

| 상황                          | 메시지                                              |
| --------------------------- | ------------------------------------------------ |
| `userIdentifier` 헤더 누락      | `STOMP CONNECT: 사용자 식별자가 없습니다. 연결이 거부되었습니다.`     |
| 유효하지 않은 `userIdentifier` 형식 | `STOMP CONNECT: 유효하지 않은 식별자 형식입니다. 연결이 거부되었습니다.` |
| 인증 없이 SEND/SUBSCRIBE 시도     | `인증 정보가 존재하지 않습니다.`                              |
| 최대 인원 초과 로비 구독 시도           | `로비 입장 실패: 최대 인원에 도달했습니다.`                       |
| 존재하지 않는 로비 구독 시도            | `로비 입장 실패: 존재하지 않는 로비입니다.`                       |

---

## 향후 추가 예정 API

현재 구현된 기능 외에 아래 API가 추가될 예정입니다.

| 분류                 | 설명                                            |
| ------------------ | --------------------------------------------- |
| 맵 아이템(문제) CRUD     | `GET/POST/PUT/DELETE /api/maps/{mapId}/items` |
| YouTube URL 유효성 검증 | `POST /api/youtube/validate`                  |
| 로비 맵 변경            | `PATCH /api/lobbies/{inviteCode}/map`         |
| 인게임 WebSocket      | `/app/game/{code}/**`                         |
