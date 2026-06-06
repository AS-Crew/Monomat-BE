# Monomat-BE API 명세서

## 공통 사항

| 항목 | 값 |
| --- | --- |
| Base URL | `http://{서버 도메인}:8080` |
| WebSocket Endpoint | `ws://{서버 도메인}:8080/ws` |
| Content-Type | `application/json` |
| REST 인증 | `Authorization: Bearer {accessToken}` |
| WebSocket 인증 | STOMP `CONNECT` 헤더에 `Authorization: Bearer {accessToken}` 또는 프로젝트 인증 계약에 맞는 인증 헤더 포함 |

---

## REST API

## 시스템 (System)

### 서버 시간 동기화 조회

```http
GET /api/system/time
```

클라이언트가 서버와의 시간 오차(delta)를 계산하기 위해 호출합니다. 인증 토큰 없이 호출할 수 있습니다.

#### Success Response

```http
HTTP/1.1 200 OK
```

```json
{
  "serverTimeMillis": 1716500000000
}
```

---

## 인증 (Auth)

인증 API는 게스트 로그인, 회원가입, 자체 로그인, 토큰 재발급, 로그아웃을 제공합니다.

상세 요청/응답, 에러 코드, 필드별 에러 메시지, 프론트엔드 표시 문구 매핑 기준은 별도 문서를 기준으로 관리합니다.

- [인증 API 상세 문서](./AUTH_API.md)

### 인증 API 목록

| 기능 | Method | Endpoint | 인증 필요 | 성공 상태 |
| --- | --- | --- | --- | --- |
| 게스트 로그인 | `POST` | `/api/auth/guest` | 아니오 | `200 OK` |
| 회원가입 | `POST` | `/api/auth/register` | 아니오 | `201 Created` |
| 자체 로그인 | `POST` | `/api/auth/login` | 아니오 | `200 OK` |
| 토큰 재발급 | `POST` | `/api/auth/refresh` | 아니오 | `200 OK` |
| 로그아웃 | `POST` | `/api/auth/logout` | 예 | `200 OK` |

### 인증 API 공통 에러 응답

```json
{
  "code": "AUTH_INVALID_CREDENTIALS",
  "message": "로그인 ID 또는 비밀번호가 올바르지 않습니다.",
  "field": null
}
```

| 필드 | 타입 | 설명 |
| --- | --- | --- |
| `code` | string | 프론트엔드 분기 기준이 되는 인증 에러 코드 |
| `message` | string | 사용자 표시용 메시지 |
| `field` | string \| null | 특정 입력 필드와 연결되는 경우 해당 필드명, 전역 에러면 `null` |

프론트엔드는 인증 API 에러 처리 시 `message` 문자열에 직접 의존하지 않고, 아래 순서로 분기합니다.

1. HTTP Status Code
2. `code`
3. `field`
4. `message`

### 비밀번호 확인 정책

`passwordConfirm`은 백엔드 API 요청 필드가 아닙니다.

프론트엔드는 회원가입 요청 전 `password`와 `passwordConfirm`의 일치 여부를 자체 검증합니다. 두 값이 일치하지 않으면 백엔드 요청을 보내지 않고 화면에서 즉시 안내합니다.

권장 사용자 표시 문구:

```text
비밀번호가 일치하지 않습니다.
```

---

## 사용자 (User)

### 내 사용자 정보 조회

```http
GET /api/users/me
Authorization: Bearer {accessToken}
```

로그인한 사용자의 기본 정보를 조회합니다.

#### Success Response

```http
HTTP/1.1 200 OK
```

```json
{
  "userId": 1,
  "username": "모노유저",
  "userType": "REGISTERED",
  "status": "ACTIVE",
  "createdAt": "2026-05-29T12:00:00"
}
```

#### Response Fields

| 필드 | 타입 | 설명 |
| --- | --- | --- |
| `userId` | number | 사용자 고유 ID. `users.id` |
| `username` | string | 서비스 표시 닉네임 |
| `userType` | string | 사용자 유형. `GUEST`, `REGISTERED` |
| `status` | string | 사용자 상태. `ACTIVE`, `BANNED`, `DELETED` |
| `createdAt` | string | 사용자 생성 시각. ISO-8601 LocalDateTime 형식 |

#### Error Response

| HTTP Status | 상황 |
| ---: | --- |
| 401 | 인증 정보 없음, 유효하지 않은 Access Token, DB에서 사용자를 찾을 수 없음 |
| 401 | 탈퇴 또는 삭제된 사용자 |
| 403 | 정지된 사용자 |
| 409 | 사용자 상태 값이 비정상인 경우 |

### 내 비밀번호 변경

```http
PATCH /api/users/me/password
Authorization: Bearer {accessToken}
Content-Type: application/json
```

로그인한 정식 회원의 비밀번호를 변경합니다.

비밀번호 변경 성공 후 서버는 해당 사용자의 모든 활성 세션을 만료합니다. 클라이언트는 성공 응답을 받으면 저장 중인 accessToken, refreshToken, 사용자 정보를 제거하고 로그인 화면으로 이동해야 합니다.

#### Request Body

```json
{
  "currentPassword": "oldPassword123",
  "newPassword": "newPassword123",
  "newPasswordConfirm": "newPassword123"
}
```

| 필드 | 타입 | 필수 | 설명 |
| --- | --- | ---: | --- |
| `currentPassword` | string | O | 현재 비밀번호 |
| `newPassword` | string | O | 새 비밀번호 |
| `newPasswordConfirm` | string | O | 새 비밀번호 확인 |

#### 비밀번호 정책

회원가입과 동일한 비밀번호 정책을 적용합니다.

| 정책 | 내용 |
| --- | --- |
| 최소 길이 | 8자 |
| 최대 길이 | 100자 |
| 공백 | 허용하지 않음 |

#### Success Response

```http
HTTP/1.1 204 No Content
```

응답 본문은 없습니다.

#### Error Response

공통 에러 응답 형식은 기존 인증 API와 동일합니다.

```json
{
  "code": "AUTH_CURRENT_PASSWORD_MISMATCH",
  "message": "현재 비밀번호가 올바르지 않습니다.",
  "field": "currentPassword"
}
```

| HTTP Status | code | field | 상황 |
| ---: | --- | --- | --- |
| 400 | `AUTH_INVALID_REQUEST_BODY` | `null` | 요청 본문 형식이 올바르지 않음 |
| 400 | `AUTH_PASSWORD_REQUIRED` | `password` | 새 비밀번호가 비어 있음 |
| 400 | `AUTH_PASSWORD_INVALID_LENGTH` | `password` | 새 비밀번호가 8자 미만 또는 100자 초과 |
| 400 | `AUTH_PASSWORD_CONTAINS_WHITESPACE` | `password` | 새 비밀번호에 공백 포함 |
| 400 | `AUTH_NEW_PASSWORD_CONFIRM_MISMATCH` | `newPasswordConfirm` | 새 비밀번호와 새 비밀번호 확인이 일치하지 않음 |
| 401 | `AUTH_UNAUTHENTICATED` | `null` | 인증 정보가 없거나 유효하지 않음 |
| 401 | `AUTH_CURRENT_PASSWORD_MISMATCH` | `currentPassword` | 현재 비밀번호가 일치하지 않음 |
| 403 | `AUTH_REGISTERED_USER_ONLY` | `null` | 게스트 사용자가 요청함 |

#### FE 처리 정책

비밀번호 변경 성공 시 기존 세션은 모두 만료됩니다.

FE는 `204 No Content` 수신 후 다음 처리를 수행해야 합니다.

1. 저장된 `accessToken` 제거
2. 저장된 `refreshToken` 제거
3. 사용자 상태 초기화
4. 로그인 화면으로 이동
5. “비밀번호가 변경되었습니다. 다시 로그인해주세요.” 메시지 표시

성공 직후 기존 accessToken으로 `/api/users/me`를 재요청하지 마세요. 서버에서 활성 세션 키를 제거하기 때문에 이후 인증 요청은 실패할 수 있습니다.

---

## 맵 (Map)

맵 API는 공개 맵 목록 조회, 내 맵 목록 조회, 공개 맵 상세 조회를 제공합니다.

`playCount`는 해당 맵이 실제 게임 시작에 사용된 누적 횟수입니다.  
로비 생성이나 맵 선택만으로는 증가하지 않으며, 선택된 맵으로 게임 세션 생성이 확정된 경우에만 증가합니다.

### 공개 맵 목록 조회

```http
GET /api/maps?page=0&size=20&keyword=KPOP&category=KPOP&sort=NEWEST
````

공개 상태인 맵 목록을 페이징하여 조회합니다.

#### Query Parameters

| 필드         | 타입     | 필수 |      기본값 | 설명             |
| ---------- | ------ | -: | -------: | -------------- |
| `page`     | number |  X |        0 | 0-based 페이지 번호 |
| `size`     | number |  X |       20 | 페이지 크기. 최대 100 |
| `keyword`  | string |  X |     null | 제목/설명 검색 키워드   |
| `category` | string |  X |     null | 맵 카테고리         |
| `sort`     | string |  X | `NEWEST` | 정렬 기준          |

#### Success Response

```http
HTTP/1.1 200 OK
```

```json
{
  "content": [
    {
      "mapId": 1,
      "title": "K-POP 랜덤 퀴즈",
      "description": "인기 K-POP 문제 모음",
      "category": "KPOP",
      "numOfSong": 10,
      "totalPlayTime": 300,
      "isPublic": true,
      "pendingPublic": false,
      "ownerId": 10,
      "ownerNickname": "owner",
      "playCount": 42
    }
  ],
  "page": 0,
  "size": 20,
  "totalElements": 1,
  "totalPages": 1,
  "hasNext": false
}
```

#### Response Fields - Map Summary

| 필드              | 타입            | 설명                        |
| --------------- | ------------- | ------------------------- |
| `mapId`         | number        | 맵 고유 ID                   |
| `title`         | string        | 맵 제목                      |
| `description`   | string | null | 맵 설명                      |
| `category`      | string        | 맵 카테고리                    |
| `numOfSong`     | number        | 맵에 등록된 곡/문제 수             |
| `totalPlayTime` | number        | 맵 전체 재생 시간(초). 플레이 횟수가 아님 |
| `isPublic`      | boolean       | 공개 여부                     |
| `pendingPublic` | boolean       | 공개 의도 보존 여부               |
| `ownerId`       | number        | 맵 소유자 ID                  |
| `ownerNickname` | string        | 맵 소유자 닉네임                 |
| `playCount`     | number        | 맵이 실제 게임 시작에 사용된 누적 횟수    |

---

### 내 맵 목록 조회

```http
GET /api/maps/me?page=0&size=20&keyword=ost&category=OST&sort=NEWEST
Authorization: Bearer {accessToken}
```

#### Query Parameters

| 이름 | 타입 | 필수 | 기본값 | 설명 |
| --- | --- | --- | --- | --- |
| page | number | N | 0 | 페이지 번호 |
| size | number | N | 20 | 페이지 크기 |
| keyword | string | N | - | 내 맵 제목 검색어. 카테고리 값으로 해석 가능한 경우 카테고리도 검색 대상에 포함 |
| category | string | N | - | 명시적 카테고리 필터 |
| sort | string | N | NEWEST | 정렬 기준 |

#### category 지원 값

| 입력 예시 | 처리 결과 |
| --- | --- |
| K-POP, KPOP, kpop | K-POP |
| J-POP, JPOP, jpop | J-POP |
| POP, pop | POP |
| OST, ost | OST |
| 애니, ANIME, anime | 애니 |

#### sort 지원 값

| 값 | 설명 |
| --- | --- |
| NEWEST | 최신순 |
| OLDEST | 오래된순 |
| MOST_SONGS | 곡 수 많은 순 |
| TITLE_ASC | 제목 오름차순 |

#### 검색 조건

```txt
ownerId = 로그인 사용자 ID
AND isDeleted = false
AND keyword 조건
AND category 조건
```

`keyword`와 `category`가 함께 들어오면 AND 조건으로 동작합니다.

#### Response

```json
{
  "content": [
    {
      "mapId": 1,
      "title": "OST 모음",
      "description": "내가 만든 OST 퀴즈",
      "category": "OST",
      "numOfSong": 10,
      "totalPlayTime": 300,
      "isPublic": false,
      "pendingPublic": true,
      "ownerId": 10,
      "ownerNickname": "hyeon",
      "playCount": 12
    }
  ],
  "page": 0,
  "size": 20,
  "totalElements": 1,
  "totalPages": 1,
  "hasNext": false
}
```

---

### 내 맵 단건 조회

```http
GET /api/maps/me/{mapId}
Authorization: Bearer {accessToken}
````

로그인한 정식 회원이 본인 소유의 공개/비공개/공개 대기 맵을 단건 조회합니다.

공개 맵 상세 조회 API(`GET /api/maps/{mapId}`)와 달리 공개 여부를 조회 조건으로 사용하지 않습니다.
단, 삭제된 맵은 조회되지 않습니다.

#### Path Variables

| 필드      | 타입     | 설명         |
| ------- | ------ | ---------- |
| `mapId` | number | 조회할 내 맵 ID |

#### Success Response

```http
HTTP/1.1 200 OK
```

```json
{
  "id": 1,
  "ownerId": 10,
  "ownerNickname": "hyeon",
  "title": "J-POP 퀴즈 대결",
  "description": "J-POP 중심 퀴즈 맵",
  "category": "JPOP",
  "numOfSong": 10,
  "totalPlayTime": 300,
  "isPublic": false,
  "pendingPublic": false,
  "playCount": 0,
  "createdAt": "2026-06-05T18:00:00",
  "updatedAt": "2026-06-05T18:30:00"
}
```

#### Response Fields

| 필드              | 타입            | 설명                     |
| --------------- | ------------- | ---------------------- |
| `id`            | number        | 맵 고유 ID                |
| `ownerId`       | number        | 맵 소유자 ID               |
| `ownerNickname` | string        | 맵 소유자 닉네임              |
| `title`         | string        | 맵 제목                   |
| `description`   | string | null | 맵 설명                   |
| `category`      | string        | 맵 카테고리                 |
| `numOfSong`     | number        | 맵에 등록된 곡/문제 수          |
| `totalPlayTime` | number        | 맵 전체 재생 시간             |
| `isPublic`      | boolean       | 공개 여부                  |
| `pendingPublic` | boolean       | 공개 의도 보존 여부            |
| `playCount`     | number        | 맵이 실제 게임 시작에 사용된 누적 횟수 |
| `createdAt`     | string        | 맵 생성 시각                |
| `updatedAt`     | string        | 맵 수정 시각                |

#### Error Response

| HTTP Status | 상황                               |
| ----------: | -------------------------------- |
|         401 | 인증 정보 없음 또는 유효하지 않은 Access Token |
|         403 | 정식 회원이 아닌 사용자                    |
|         403 | 본인 소유가 아닌 맵 조회                   |
|         404 | 존재하지 않는 맵                        |
|         404 | 삭제된 맵                            |

---

### 공개 맵 상세 조회

```http
GET /api/maps/{mapId}
```

공개 상태인 맵의 상세 정보를 조회합니다.

#### Path Variables

| 필드      | 타입     | 설명       |
| ------- | ------ | -------- |
| `mapId` | number | 조회할 맵 ID |

#### Success Response

```http
HTTP/1.1 200 OK
```

```json
{
  "id": 1,
  "ownerId": 10,
  "ownerNickname": "owner",
  "title": "K-POP 랜덤 퀴즈",
  "description": "인기 K-POP 문제 모음",
  "category": "KPOP",
  "numOfSong": 10,
  "totalPlayTime": 300,
  "isPublic": true,
  "pendingPublic": false,
  "playCount": 42,
  "createdAt": "2026-06-05T18:00:00",
  "updatedAt": "2026-06-05T18:30:00"
}
```

#### Response Fields

| 필드              | 타입            | 설명                                 |
| --------------- | ------------- | ---------------------------------- |
| `id`            | number        | 맵 고유 ID                            |
| `ownerId`       | number        | 맵 소유자 ID                           |
| `ownerNickname` | string        | 맵 소유자 닉네임                          |
| `title`         | string        | 맵 제목                               |
| `description`   | string | null | 맵 설명                               |
| `category`      | string        | 맵 카테고리                             |
| `numOfSong`     | number        | 맵에 등록된 곡/문제 수                      |
| `totalPlayTime` | number        | 맵 전체 재생 시간(초). 플레이 횟수가 아님          |
| `isPublic`      | boolean       | 공개 여부                              |
| `pendingPublic` | boolean       | 공개 의도 보존 여부                        |
| `playCount`     | number        | 맵이 실제 게임 시작에 사용된 누적 횟수             |
| `createdAt`     | string        | 맵 생성 시각. ISO-8601 LocalDateTime 형식 |
| `updatedAt`     | string        | 맵 수정 시각. ISO-8601 LocalDateTime 형식 |

#### Error Response

| HTTP Status | 상황            |
| ----------: | ------------- |
|         404 | 공개 맵을 찾을 수 없음 |

---

## 로비 (Lobby)

### 로비 생성

```http
POST /api/lobbies
Authorization: Bearer {accessToken}
Content-Type: application/json
```

로그인한 사용자가 게임 로비를 생성합니다. 게스트와 정식 회원 모두 로비를 생성할 수 있습니다.

`maxPlayers`, `questionCount`, `timeLimitSeconds`는 생략할 수 있으며, 생략 시 서버 기본값이 적용됩니다.

#### Request Body

```json
{
  "title": "모노맛 테스트 로비",
  "maxPlayers": 4,
  "isPrivate": false,
  "mapId": 1,
  "questionCount": 10,
  "timeLimitSeconds": 30
}
```

| 필드 | 타입 | 필수 | 기본값 | 제한 | 설명 |
| --- | --- | ---: | ---: | --- | --- |
| `title` | string | O | - | 최대 255자, blank 불가 | 로비 제목 |
| `maxPlayers` | number | X | 4 | 2~8 | 최대 참여 인원 |
| `isPrivate` | boolean | O | - | - | 비공개 로비 여부 |
| `mapId` | number | X | null | 양수 | 연결할 맵 ID. 생략 시 맵 미선택 로비 생성 |
| `questionCount` | number | X | 10 | 1~50 | 진행할 문제 수/라운드 수 |
| `timeLimitSeconds` | number | X | 30 | 10~120 | 라운드당 제한 시간(초) |

#### 로비 생성 기본값/제한 정책

| 항목 | 최소값 | 기본값 | 최대값 |
| --- | ---: | ---: | ---: |
| 최대 인원 | 2명 | 4명 | 8명 |
| 문제 수/라운드 수 | 1개 | 10개 | 50개 |
| 라운드당 제한 시간 | 10초 | 30초 | 120초 |

#### 맵 선택 시 문제 수 정책

questionCount 생략 + mapId 있음 → min(10, map.numOfSong)
questionCount 명시 + mapId 있음 + questionCount > map.numOfSong → 400

#### Success Response

```http
HTTP/1.1 201 Created
```

```json
{
  "lobbyId": 1,
  "inviteCode": "ABC123",
  "title": "모노맛 테스트 로비",
  "maxPlayers": 4,
  "isPrivate": false,
  "status": "WAITING",
  "mapId": 1,
  "mapTitle": "K-POP 퀴즈",
  "mapCategory": "K-POP"
}
```

#### 맵 카테고리(`mapCategory`) 값

맵 생성/수정 요청 및 로비 목록 `mapCategory` 필터에서 사용하는 카테고리 값은 아래 5종이다.

| 응답/표시 값 | 허용 입력 값(대소문자·하이픈·언더스코어·공백 무시) |
| --- | --- |
| `K-POP` | `K-POP`, `KPOP`, `kpop` |
| `J-POP` | `J-POP`, `JPOP`, `jpop` |
| `POP` | `POP`, `pop` |
| `OST` | `OST`, `ost` |
| `애니` | `애니`, `ANIME`, `anime` |

목록에 없는 값을 요청하면 400 Bad Request로 응답한다.

#### Error Response

| HTTP Status | 상황 |
| ---: | --- |
| 400 | 요청 본문 검증 실패 |
| 400 | 선택한 맵의 등록 곡 수보다 `questionCount`가 큼 |
| 401 | 인증 정보 없음 또는 유효하지 않은 Access Token |
| 404 | 사용자 또는 선택한 맵을 찾을 수 없음 |
| 500 | Redis 저장 후 DB 스냅샷 저장 실패 등 로비 생성 실패 |

### 초대 코드 기반 로비 입장

```http
POST /api/lobbies/join
Authorization: Bearer {accessToken}
Content-Type: application/json
```

초대 코드 기반으로 로비 입장 가능 여부를 사전 검증합니다. 실제 참여자 등록은 WebSocket 구독 시점에 처리됩니다.

#### 클라이언트 처리 순서

1. `POST /api/lobbies/join` 호출
2. 입장 가능 여부 확인
3. WebSocket `SUBSCRIBE /topic/lobby/{inviteCode}` 호출
4. 서버가 실제 참여자 등록 처리

---

## 관리자 (Admin)

### 닉네임 금칙어 관리

닉네임 금칙어 목록을 관리자 API로 조회, 추가, 삭제합니다.

관리자 API를 호출하려면 다음 조건을 모두 만족해야 합니다.

- JWT Access Token이 유효해야 합니다.
- JWT에서 추출한 `userId`가 `admin_users.user_id`에 등록되어 있어야 합니다.
- 해당 사용자의 `user_type`이 `REGISTERED`여야 합니다.

---

## 게임 및 인게임 (Game & In-Game)

### 현재 게임 및 라운드 상태 복구 조회

```http
GET /api/game/{code}/round/current
Authorization: Bearer {accessToken}
```

게임 도중 접속이 일시 중단(새로고침, 모바일 환경 백그라운드 전환 등)된 사용자가 현재 진행 중인 게임 세션 및 라운드 상태를 복구할 수 있도록 동영상 메타데이터 및 상태를 조회합니다.

#### Success Response

```http
HTTP/1.1 200 OK
Content-Type: application/json
```

##### 1. `READY` 단계 (ROUND_READY: 비디오 로딩 대기 상태)

```json
{
  "roundNo": 1,
  "status": "WAITING",
  "roundPhase": "READY",
  "timeLimitSeconds": 30,
  "serverStartedAt": null,
  "videoId": "vid_123",
  "youtubeUrl": "https://youtube.com/watch?v=vid_123",
  "startTime": 15,
  "endTime": 45,
  "remainingSeconds": null,
  "isCorrect": false
}
```

##### 2. `PLAYING` 단계 (ROUND_PLAYBACK_STARTED: 재생 진행 중 상태)

```json
{
  "roundNo": 1,
  "status": "PLAYING",
  "roundPhase": "PLAYING",
  "timeLimitSeconds": 30,
  "serverStartedAt": 1717148068000,
  "videoId": "vid_123",
  "youtubeUrl": "https://youtube.com/watch?v=vid_123",
  "startTime": 15,
  "endTime": 45,
  "remainingSeconds": 20,
  "isCorrect": true
}
```

##### 3. `ENDED` 단계 (ROUND_END: 결과화면 노출 중 상태)

```json
{
  "roundNo": 1,
  "status": "WAITING",
  "roundPhase": "ENDED",
  "timeLimitSeconds": 30,
  "serverStartedAt": null,
  "videoId": null,
  "youtubeUrl": null,
  "startTime": null,
  "endTime": null,
  "remainingSeconds": null,
  "isCorrect": false
}
```

##### 4. `FINISHED` 단계 (게임 세션 종료 상태)

```json
{
  "roundNo": 5,
  "status": "FINISHED",
  "roundPhase": "FINISHED",
  "timeLimitSeconds": 30,
  "serverStartedAt": null,
  "videoId": null,
  "youtubeUrl": null,
  "startTime": null,
  "endTime": null,
  "remainingSeconds": null,
  "isCorrect": false
}
```

#### Response Fields

| 필드 | 타입 | 설명 |
| --- | --- | --- |
| `roundNo` | number | 현재 진행 중인 라운드 번호 (1-based) |
| `status` | string | 전체 게임 세션 상태 (`WAITING` \| `PLAYING` \| `FINISHED`) |
| `roundPhase` | string | 현재 라운드 진행 단계 (`READY` \| `PLAYING` \| `ENDED` \| `FINISHED`) |
| `timeLimitSeconds` | number | 라운드별 제한 시간(초) |
| `serverStartedAt` | number \| null | YouTube 영상 실제 재생 시작 시점의 서버 epoch milliseconds. 아직 재생을 시작하지 않았거나 종료된 경우 `null` |
| `videoId` | string \| null | YouTube 비디오 고유 ID. `READY`/`PLAYING` 단계가 아니면 `null` |
| `youtubeUrl` | string \| null | YouTube 비디오 전체 URL. `READY`/`PLAYING` 단계가 아니면 `null` |
| `startTime` | number \| null | 비디오 내 재생 시작 지점(초). `READY`/`PLAYING` 단계가 아니면 `null` |
| `endTime` | number \| null | 비디오 내 재생 종료 지점(초) (`startTime + timeLimitSeconds`). `READY`/`PLAYING` 단계가 아니면 `null` |
| `remainingSeconds` | number \| null | 영상 종료 시점까지 남은 제한 시간(초). `PLAYING` 단계가 아니면 `null` |
| `isCorrect` | boolean | 현재 사용자가 해당 라운드에서 정답을 맞췄는지 여부 |

#### Error Response

| HTTP Status | Message | 상황 |
| ---: | --- | --- |
| 401 | `유효하지 않은 인증 정보입니다.` | 인증 정보(Access Token)가 누락되거나 잘못됨 |
| 403 | `로비 참여자만 게임 상태를 조회할 수 있습니다.` | 세션이 활성화된 로비의 정상 참여자가 아님 |
| 403 | `강퇴된 로비의 게임 상태는 조회할 수 없습니다.` | 해당 로비에서 강퇴된 사용자가 조회를 시도함 |
| 404 | `존재하지 않는 로비입니다.` | 존재하지 않는 로비 초대 코드 입력 |
| 404 | `진행 중인 게임 세션이 없습니다.` | 해당 로비에 매핑된 활성화 상태의 게임 세션이 없음 |

