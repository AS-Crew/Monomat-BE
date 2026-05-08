# Monomat-BE API 명세서

## 공통 사항

- **Base URL** : `http://{서버 도메인}:8080`
- **WebSocket Endpoint** : `ws://{서버 도메인}:8080/ws` (SockJS 폴백 지원)
- **Content-Type** : `application/json`
- **인증** : WebSocket 연결 시 STOMP CONNECT 헤더에 `userIdentifier` 포함 필요

---

## REST API

### 인증 (Auth)

#### 게스트 로그인

```
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

- 정식 회원 닉네임과 충돌: `정식 회원이 이미 사용 중인 닉네임입니다.`
- 기존 사용자 닉네임과 충돌: `이미 사용 중인 닉네임입니다.`

#### (dev 전용) REGISTERED 토큰 발급

```
POST /api/auth/dev/registered-token
```

dev 프로필에서만 노출됩니다. 입력한 username으로 REGISTERED 사용자를 만들거나 재사용해 Access/Refresh 토큰을 발급합니다.
#### 회원가입

```
POST /api/auth/register
```

로그인 ID/비밀번호/닉네임으로 정식 회원 계정을 생성합니다.  
회원가입 API는 계정 생성만 수행하며 토큰 발급은 로그인 API에서 처리됩니다.

**Request**

```json
{
  "username": "dev-registered-user"
}
```

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

- `400 Bad Request`: 필수값 누락/비밀번호 길이 조건 불만족
- `409 Conflict`: 로그인 ID 또는 닉네임 중복

### 로비 (Lobby)

### 로비 생성

```
POST /api/lobbies
```

로비를 생성하고 6자리 초대 코드를 발급합니다.
JWT Access Token이 필요합니다. 게스트와 정식 회원 모두 생성 가능합니다.

**Request Header**

| 헤더 | 필수 | 설명 |
|---|---|---|
| `Authorization` | ✅ | `Bearer {accessToken}` |

**Request Body**

```json
{
  "title": "K-POP 퀴즈방",
  "maxPlayers": 8,
  "isPrivate": false,
  "roundCount": 5,
  "timeLimitSeconds": 30
}
```

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| `title` | String | ✅ | 로비 제목 (최대 255자) |
| `maxPlayers` | Integer | ✅ | 최대 참여 인원 (2~8) |
| `isPrivate` | Boolean | ✅ | 비공개 여부 |
| `roundCount` | Integer | ❌ | 라운드 수 (1~20, 기본값 5) |
| `timeLimitSeconds` | Integer | ❌ | 제한 시간 초 (10~120, 기본값 30) |

**Response `201 Created`**

```json
{
  "lobbyId": 1,
  "inviteCode": "ABC123",
  "title": "K-POP 퀴즈방",
  "maxPlayers": 8,
  "isPrivate": false,
  "status": "WAITING"
}
```

| 필드 | 타입 | 설명 |
|---|---|---|
| `lobbyId` | Long | DB GAME_LOBBY.id (신고 참조용) |
| `inviteCode` | String | 6자리 초대 코드 (딥링크: `/lobby/{inviteCode}`) |
| `title` | String | 로비 제목 |
| `maxPlayers` | Integer | 최대 참여 인원 |
| `isPrivate` | Boolean | 비공개 여부 |
| `status` | String | 로비 상태 (`WAITING`) |

**Error**

| 상태 코드 | 설명 |
|---|---|
| `401 Unauthorized` | JWT 토큰 없음 또는 만료 |
| `503 Service Unavailable` | 초대 코드 생성 실패 (재시도 초과) |

---

#### 공개 로비 목록 조회

```
GET /api/lobbies
```

현재 활성화된 공개(`isPrivate = false`) 로비 목록을 반환합니다.
Redis에서 직접 필터링하여 고속 반환합니다.

**Response `200 OK`**

```json
[
  {
    "code": "ABC123",
    "hostId": "uuid-xxxx-xxxx",
    "title": "K-POP 퀴즈방",
    "mapId": 1,
    "maxPlayers": 8,
    "isPrivate": false,
    "status": "WAITING"
  }
]
```

| 필드 | 타입 | 설명 |
|---|---|---|
| `code` | String | 로비 초대 코드 (6자리) |
| `hostId` | String | 방장 사용자 식별자 |
| `title` | String | 로비 제목 |
| `mapId` | Long | 선택된 맵 ID (미선택 시 null) |
| `maxPlayers` | Integer | 최대 참여 인원 |
| `isPrivate` | Boolean | 비공개 여부 (`true` = 비공개) |
| `status` | String | 로비 상태 (`WAITING` \| `PLAYING`) |

---

### 맵 (Map)

#### 공개 맵 목록 조회

```
GET /api/maps
```

공개(`is_public=true`) 상태이면서 삭제되지 않은 맵만 반환합니다.
페이지네이션 파라미터를 지원합니다.

| 쿼리 파라미터 | 기본값 | 설명 |
|---|---:|---|
| `page` | `0` | 0-based 페이지 번호 |
| `size` | `20` | 페이지 크기 (최대 100) |

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

```
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

```
POST /api/maps
```

정식 회원(`REGISTERED`)만 생성할 수 있습니다.

`category`는 아래 enum 값만 허용합니다.
- `kpop`
- `jpop`
- `pop`

#### 맵 수정

```
PUT /api/maps/{mapId}
```

맵 소유자만 수정 가능하며, `isPublic` 필드로 공개/비공개 전환을 지원합니다.
수정 시 Redis 맵 캐시를 무효화합니다.

#### 맵 삭제

```
DELETE /api/maps/{mapId}
```

맵 소유자만 삭제할 수 있으며, 물리 삭제 대신 Soft Delete(`is_deleted=true`) 처리합니다.

---

## WebSocket API (STOMP)

WebSocket 연결 후 STOMP 프로토콜로 통신합니다.
SockJS를 통해 WebSocket 연결 실패 시 HTTP 폴링으로 자동 대체됩니다.

### 연결

```
CONNECT
userIdentifier: {UUID 또는 회원 ID}
```

| 헤더 | 필수 | 설명 |
|---|---|---|
| `userIdentifier` | ✅ | 게스트 UUID(8-4-4-4-12 포맷) 또는 회원 ID |

연결 실패 시 STOMP ERROR 프레임으로 사유를 반환합니다.

---

### 채팅

#### 전체 채팅 송신

```
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

```
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

```
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

```
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

| 값 | 설명 |
|---|---|
| `CHAT` | 일반 채팅 메시지 |
| `ANSWER` | 정답 제출 메시지 (인게임 전용) |
| `ENTER` | 입장 알림 시스템 메시지 |
| `LEAVE` | 퇴장 알림 시스템 메시지 |

> `sender` 필드는 클라이언트 전송값을 무시하고 서버 세션에서 추출한 값으로 교체됩니다. (발신자 위변조 방지)

---

### 로비 이벤트

#### 로비 생성 알림 송신

```
SEND /app/lobby/create
```

로비 생성 후 클라이언트가 호출합니다.
로비 리스트를 보고 있는 모든 클라이언트에게 새로고침 신호를 전송합니다.

#### 로비 리스트 새로고침 구독

```
SUBSCRIBE /topic/lobby/refresh
```

**수신 메시지**

```
"REFRESH_LOBBY_LIST"
```

#### 로비 정보 변경 알림 송신

```
SEND /app/lobby/{code}/update
```

유저 입장, 퇴장, 준비, 맵 변경 등 로비 내부 상태 변경 시 클라이언트가 호출합니다.

#### 로비 내부 새로고침 구독

```
SUBSCRIBE /topic/lobby/{code}/refresh
```

**수신 메시지**

```
"REFRESH_LOBBY_INFO"
```

---

## 에러 응답

### STOMP ERROR 프레임

인증 실패 또는 유효하지 않은 요청 시 STOMP ERROR 프레임으로 응답합니다.

| 상황 | 메시지 |
|---|---|
| `userIdentifier` 헤더 누락 | `STOMP CONNECT: 사용자 식별자가 없습니다. 연결이 거부되었습니다.` |
| 유효하지 않은 `userIdentifier` 형식 | `STOMP CONNECT: 유효하지 않은 식별자 형식입니다. 연결이 거부되었습니다.` |
| 인증 없이 SEND/SUBSCRIBE 시도 | `인증 정보가 존재하지 않습니다.` |

---

## 향후 추가 예정 API

현재 구현된 기능 외에 아래 API가 추가될 예정입니다.

| 분류 | 설명 |
|---|---|
| 로그인 | `POST /api/auth/login` |
| 로비 초대 코드 입장 | `POST /api/lobbies/join` |
| 맵 아이템(문제) CRUD | `GET/POST/PUT/DELETE /api/maps/{mapId}/items` |
| YouTube URL 유효성 검증 | `POST /api/youtube/validate` |
| 인게임 WebSocket | `/app/game/{code}/**` |
