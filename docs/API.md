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
