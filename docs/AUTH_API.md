# 인증 API 문서

## 1. 문서 목적

이 문서는 Monomat 프론트엔드 인증 화면 구현에 필요한 백엔드 인증 API 계약을 정리한다.

대상 화면은 다음과 같다.

- 회원가입
- 자체 로그인
- 게스트 로그인
- 토큰 재발급
- 로그아웃

프론트엔드는 인증 API 에러 처리 시 `message` 문자열에 의존하지 않는다.

에러 UI 분기 기준은 다음 순서를 따른다.

1. HTTP Status Code
2. `code`
3. `field`
4. `message`

`message`는 사용자 표시용 문구 또는 fallback 문구로만 사용한다.

---

## 2. 공통 규칙

### 2.1 Base URL

```http
/api/auth
````

### 2.2 인증 API 에러 응답 포맷

인증 API에서 발생하는 표준 에러 응답 body는 다음 형식을 따른다.

```json
{
  "code": "AUTH_INVALID_CREDENTIALS",
  "message": "로그인 ID 또는 비밀번호가 올바르지 않습니다.",
  "field": null
}
```

| 필드        | 타입            | 설명                                      |
| --------- | ------------- | --------------------------------------- |
| `code`    | string        | 프론트엔드 분기 기준이 되는 안정적인 에러 코드              |
| `message` | string        | 사용자 표시용 메시지                             |
| `field`   | string | null | 특정 입력 필드와 연결되는 경우 해당 필드명, 전역 에러면 `null` |

> 현재 구현 기준 `httpStatus`는 응답 body에 포함되지 않는다.
> HTTP 상태는 response status line을 기준으로 판단한다.

### 2.3 프론트엔드 에러 처리 기준

프론트엔드는 다음 기준으로 에러 UI를 처리한다.

```ts
type AuthErrorResponse = {
  code: string;
  message: string;
  field: "loginId" | "password" | "nickname" | "refreshToken" | null;
};
```

처리 기준은 다음과 같다.

| 조건                | 처리 방식                             |
| ----------------- | --------------------------------- |
| `field`가 존재함      | 해당 form field 하단에 메시지 표시          |
| `field`가 `null`임  | form 상단 또는 toast/global alert로 표시 |
| `code`를 알고 있음     | FE에서 정의한 표시 정책 우선 적용 가능           |
| `code`를 모름        | 서버 `message`를 fallback으로 표시       |
| HTTP Status `401` | 인증 실패 또는 세션 만료 처리                 |
| HTTP Status `423` | 계정 잠금 안내                          |
| HTTP Status `503` | 일시 장애 안내                          |

---

## 3. 게스트 로그인 API

### 3.1 Endpoint

```http
POST /api/auth/guest
```

### 3.2 Request Body

```json
{
  "nickname": "게스트닉네임"
}
```

| 필드         | 타입     | 필수 | 제약                     |
| ---------- | ------ | -- | ---------------------- |
| `nickname` | string | O  | 2자 이상 12자 이하, blank 불가 |

### 3.3 Success Response

HTTP Status: `200 OK`

```json
{
  "userId": 1,
  "nickname": "게스트닉네임",
  "userType": "GUEST",
  "userIdentifier": "uuid-or-session-identifier",
  "accessToken": "access-token",
  "accessTokenExpiresAt": "2026-05-29T12:00:00Z",
  "refreshToken": "refresh-token",
  "refreshTokenExpiresAt": "2026-06-28T12:00:00Z"
}
```

### 3.4 Error Response

| HTTP Status | code                           | field      | message                          |
| ----------- | ------------------------------ | ---------- | -------------------------------- |
| 400         | `AUTH_NICKNAME_REQUIRED`       | `nickname` | 닉네임은 비어 있을 수 없습니다.               |
| 400         | `AUTH_NICKNAME_INVALID_LENGTH` | `nickname` | 닉네임은 2자 이상 12자 이하로 입력해주세요.       |
| 400         | `AUTH_NICKNAME_FORBIDDEN_WORD` | `nickname` | 금칙어가 포함된 닉네임은 사용할 수 없습니다.        |
| 409         | `AUTH_NICKNAME_DUPLICATED`     | `nickname` | 이미 사용 중인 닉네임입니다.                 |
| 400         | `AUTH_INVALID_REQUEST_BODY`    | `null`     | 요청 본문 형식이 올바르지 않습니다.             |
| 503         | `AUTH_TEMPORARY_UNAVAILABLE`   | `null`     | 일시적인 오류가 발생했습니다. 잠시 후 다시 시도해주세요. |

---

## 4. 회원가입 API

### 4.1 Endpoint

```http
POST /api/auth/register
```

### 4.2 Request Body

```json
{
  "loginId": "testuser01",
  "password": "password1234",
  "nickname": "모노유저"
}
```

| 필드         | 타입     | 필수 | 제약                      |
| ---------- | ------ | -- | ----------------------- |
| `loginId`  | string | O  | 4자 이상 50자 이하, 영문/숫자만 허용 |
| `password` | string | O  | 8자 이상 100자 이하, 공백 불가    |
| `nickname` | string | O  | 2자 이상 12자 이하            |

### 4.3 Success Response

HTTP Status: `201 Created`

```json
{
  "userId": 1,
  "loginId": "testuser01",
  "nickname": "모노유저",
  "userType": "REGISTERED"
}
```

회원가입 성공 시 토큰은 발급하지 않는다.
로그인이 필요한 경우 프론트엔드는 회원가입 성공 후 로그인 API를 별도로 호출한다.

### 4.4 Error Response

| HTTP Status | code                                | field      | message                          |
| ----------- | ----------------------------------- | ---------- | -------------------------------- |
| 400         | `AUTH_LOGIN_ID_REQUIRED`            | `loginId`  | 로그인 ID를 입력해주세요.                  |
| 400         | `AUTH_LOGIN_ID_INVALID_LENGTH`      | `loginId`  | 로그인 ID는 4자 이상 50자 이하로 입력해주세요.    |
| 400         | `AUTH_LOGIN_ID_INVALID_FORMAT`      | `loginId`  | 로그인 ID는 영문과 숫자만 사용할 수 있습니다.      |
| 400         | `AUTH_LOGIN_ID_CONTAINS_WHITESPACE` | `loginId`  | 로그인 ID에는 공백을 포함할 수 없습니다.         |
| 409         | `AUTH_LOGIN_ID_DUPLICATED`          | `loginId`  | 이미 사용 중인 로그인 ID입니다.              |
| 400         | `AUTH_PASSWORD_REQUIRED`            | `password` | 비밀번호는 비어 있을 수 없습니다.              |
| 400         | `AUTH_PASSWORD_INVALID_LENGTH`      | `password` | 비밀번호는 8자 이상 100자 이하여야 합니다.       |
| 400         | `AUTH_PASSWORD_CONTAINS_WHITESPACE` | `password` | 비밀번호에는 공백을 포함할 수 없습니다.           |
| 400         | `AUTH_NICKNAME_REQUIRED`            | `nickname` | 닉네임은 비어 있을 수 없습니다.               |
| 400         | `AUTH_NICKNAME_INVALID_LENGTH`      | `nickname` | 닉네임은 2자 이상 12자 이하로 입력해주세요.       |
| 400         | `AUTH_NICKNAME_FORBIDDEN_WORD`      | `nickname` | 금칙어가 포함된 닉네임은 사용할 수 없습니다.        |
| 409         | `AUTH_NICKNAME_DUPLICATED`          | `nickname` | 이미 사용 중인 닉네임입니다.                 |
| 400         | `AUTH_INVALID_REQUEST_BODY`         | `null`     | 요청 본문 형식이 올바르지 않습니다.             |
| 503         | `AUTH_TEMPORARY_UNAVAILABLE`        | `null`     | 일시적인 오류가 발생했습니다. 잠시 후 다시 시도해주세요. |

### 4.5 비밀번호 확인 정책

`passwordConfirm`은 백엔드 API 요청 필드가 아니다.

프론트엔드는 회원가입 요청 전 다음 검증을 자체 수행한다.

```ts
password === passwordConfirm
```

비밀번호 확인이 일치하지 않으면 백엔드 요청을 보내지 않고, 프론트엔드에서 즉시 안내한다.

권장 사용자 표시 문구:

```text
비밀번호가 일치하지 않습니다.
```

---

## 5. 자체 로그인 API

### 5.1 Endpoint

```http
POST /api/auth/login
```

### 5.2 Request Body

```json
{
  "loginId": "testuser01",
  "password": "password1234"
}
```

| 필드         | 타입     | 필수 | 제약       |
| ---------- | ------ | -- | -------- |
| `loginId`  | string | O  | blank 불가 |
| `password` | string | O  | blank 불가 |

로그인 API에서는 기존 회원 호환성을 위해 `loginId` 포맷 검증을 수행하지 않는다.
존재하지 않는 계정, 비밀번호 불일치 등은 모두 `AUTH_INVALID_CREDENTIALS`로 처리한다.

### 5.3 Success Response

HTTP Status: `200 OK`

```json
{
  "userId": 1,
  "loginId": "testuser01",
  "nickname": "모노유저",
  "userType": "REGISTERED",
  "userIdentifier": "uuid-or-session-identifier",
  "accessToken": "access-token",
  "accessTokenExpiresAt": "2026-05-29T12:00:00Z",
  "refreshToken": "refresh-token",
  "refreshTokenExpiresAt": "2026-06-28T12:00:00Z"
}
```

### 5.4 Error Response

| HTTP Status | code                         | field      | message                           |
| ----------- | ---------------------------- | ---------- | --------------------------------- |
| 400         | `AUTH_LOGIN_ID_REQUIRED`     | `loginId`  | 로그인 ID를 입력해주세요.                   |
| 400         | `AUTH_PASSWORD_REQUIRED`     | `password` | 비밀번호는 비어 있을 수 없습니다.               |
| 401         | `AUTH_INVALID_CREDENTIALS`   | `null`     | 로그인 ID 또는 비밀번호가 올바르지 않습니다.        |
| 423         | `AUTH_ACCOUNT_LOCKED`        | `null`     | 로그인 시도가 너무 많습니다. 15분 후 다시 시도해주세요. |
| 400         | `AUTH_INVALID_REQUEST_BODY`  | `null`     | 요청 본문 형식이 올바르지 않습니다.              |
| 503         | `AUTH_TEMPORARY_UNAVAILABLE` | `null`     | 일시적인 오류가 발생했습니다. 잠시 후 다시 시도해주세요.  |

---

## 6. 토큰 재발급 API

### 6.1 Endpoint

```http
POST /api/auth/refresh
```

### 6.2 Request Body

```json
{
  "refreshToken": "refresh-token"
}
```

| 필드             | 타입     | 필수 | 제약       |
| -------------- | ------ | -- | -------- |
| `refreshToken` | string | O  | blank 불가 |

### 6.3 Success Response

HTTP Status: `200 OK`

```json
{
  "userId": 1,
  "userType": "REGISTERED",
  "userIdentifier": "uuid-or-session-identifier",
  "accessToken": "new-access-token",
  "accessTokenExpiresAt": "2026-05-29T12:15:00Z",
  "refreshToken": "new-refresh-token",
  "refreshTokenExpiresAt": "2026-06-28T12:15:00Z"
}
```

### 6.4 Error Response

| HTTP Status | code                          | field          | message                          |
| ----------- | ----------------------------- | -------------- | -------------------------------- |
| 400         | `AUTH_REFRESH_TOKEN_REQUIRED` | `refreshToken` | Refresh Token은 비어 있을 수 없습니다.     |
| 401         | `AUTH_INVALID_REFRESH_TOKEN`  | `null`         | Refresh Token이 유효하지 않습니다.        |
| 401         | `AUTH_SESSION_EXPIRED`        | `null`         | 세션이 만료되었습니다.                     |
| 400         | `AUTH_INVALID_REQUEST_BODY`   | `null`         | 요청 본문 형식이 올바르지 않습니다.             |
| 503         | `AUTH_TEMPORARY_UNAVAILABLE`  | `null`         | 일시적인 오류가 발생했습니다. 잠시 후 다시 시도해주세요. |

---

## 7. 로그아웃 API

### 7.1 Endpoint

```http
POST /api/auth/logout
```

### 7.2 Request Header

```http
Authorization: Bearer {accessToken}
```

### 7.3 Request Body

없음.

### 7.4 Success Response

HTTP Status: `200 OK`

```json
{
  "message": "로그아웃이 완료되었습니다."
}
```

### 7.5 Error Response

| HTTP Status | code                         | field  | message                          |
| ----------- | ---------------------------- | ------ | -------------------------------- |
| 401         | `AUTH_UNAUTHENTICATED`       | `null` | 인증 정보가 없습니다.                     |
| 401         | `AUTH_INVALID_AUTHORIZATION` | `null` | Authorization 헤더가 유효하지 않습니다.     |
| 401         | `AUTH_SESSION_EXPIRED`       | `null` | 세션이 만료되었습니다.                     |
| 503         | `AUTH_TEMPORARY_UNAVAILABLE` | `null` | 일시적인 오류가 발생했습니다. 잠시 후 다시 시도해주세요. |

---

## 8. 인증 에러 코드 전체 목록

| HTTP Status | code                                | field          | message                           |
| ----------- | ----------------------------------- | -------------- | --------------------------------- |
| 400         | `AUTH_INVALID_REQUEST_BODY`         | `null`         | 요청 본문 형식이 올바르지 않습니다.              |
| 400         | `AUTH_LOGIN_ID_REQUIRED`            | `loginId`      | 로그인 ID를 입력해주세요.                   |
| 400         | `AUTH_LOGIN_ID_INVALID_LENGTH`      | `loginId`      | 로그인 ID는 4자 이상 50자 이하로 입력해주세요.     |
| 400         | `AUTH_LOGIN_ID_INVALID_FORMAT`      | `loginId`      | 로그인 ID는 영문과 숫자만 사용할 수 있습니다.       |
| 400         | `AUTH_LOGIN_ID_CONTAINS_WHITESPACE` | `loginId`      | 로그인 ID에는 공백을 포함할 수 없습니다.          |
| 409         | `AUTH_LOGIN_ID_DUPLICATED`          | `loginId`      | 이미 사용 중인 로그인 ID입니다.               |
| 400         | `AUTH_PASSWORD_REQUIRED`            | `password`     | 비밀번호는 비어 있을 수 없습니다.               |
| 400         | `AUTH_PASSWORD_INVALID_LENGTH`      | `password`     | 비밀번호는 8자 이상 100자 이하여야 합니다.        |
| 400         | `AUTH_PASSWORD_CONTAINS_WHITESPACE` | `password`     | 비밀번호에는 공백을 포함할 수 없습니다.            |
| 400         | `AUTH_NICKNAME_REQUIRED`            | `nickname`     | 닉네임은 비어 있을 수 없습니다.                |
| 400         | `AUTH_NICKNAME_INVALID_LENGTH`      | `nickname`     | 닉네임은 2자 이상 12자 이하로 입력해주세요.        |
| 409         | `AUTH_NICKNAME_DUPLICATED`          | `nickname`     | 이미 사용 중인 닉네임입니다.                  |
| 400         | `AUTH_NICKNAME_FORBIDDEN_WORD`      | `nickname`     | 금칙어가 포함된 닉네임은 사용할 수 없습니다.         |
| 401         | `AUTH_INVALID_CREDENTIALS`          | `null`         | 로그인 ID 또는 비밀번호가 올바르지 않습니다.        |
| 423         | `AUTH_ACCOUNT_LOCKED`               | `null`         | 로그인 시도가 너무 많습니다. 15분 후 다시 시도해주세요. |
| 401         | `AUTH_UNAUTHENTICATED`              | `null`         | 인증 정보가 없습니다.                      |
| 401         | `AUTH_INVALID_AUTHORIZATION`        | `null`         | Authorization 헤더가 유효하지 않습니다.      |
| 400         | `AUTH_REFRESH_TOKEN_REQUIRED`       | `refreshToken` | Refresh Token은 비어 있을 수 없습니다.      |
| 401         | `AUTH_INVALID_REFRESH_TOKEN`        | `null`         | Refresh Token이 유효하지 않습니다.         |
| 401         | `AUTH_SESSION_EXPIRED`              | `null`         | 세션이 만료되었습니다.                      |
| 503         | `AUTH_TEMPORARY_UNAVAILABLE`        | `null`         | 일시적인 오류가 발생했습니다. 잠시 후 다시 시도해주세요.  |

---

## 9. 필드별 에러 메시지 매핑

### 9.1 loginId

| code                                | 표시 위치         | 사용자 표시 문구                     |
| ----------------------------------- | ------------- | ----------------------------- |
| `AUTH_LOGIN_ID_REQUIRED`            | loginId field | 로그인 ID를 입력해주세요.               |
| `AUTH_LOGIN_ID_INVALID_LENGTH`      | loginId field | 로그인 ID는 4자 이상 50자 이하로 입력해주세요. |
| `AUTH_LOGIN_ID_INVALID_FORMAT`      | loginId field | 로그인 ID는 영문과 숫자만 사용할 수 있습니다.   |
| `AUTH_LOGIN_ID_CONTAINS_WHITESPACE` | loginId field | 로그인 ID에는 공백을 포함할 수 없습니다.      |
| `AUTH_LOGIN_ID_DUPLICATED`          | loginId field | 이미 사용 중인 로그인 ID입니다.           |

### 9.2 password

| code                                | 표시 위치          | 사용자 표시 문구                  |
| ----------------------------------- | -------------- | -------------------------- |
| `AUTH_PASSWORD_REQUIRED`            | password field | 비밀번호는 비어 있을 수 없습니다.        |
| `AUTH_PASSWORD_INVALID_LENGTH`      | password field | 비밀번호는 8자 이상 100자 이하여야 합니다. |
| `AUTH_PASSWORD_CONTAINS_WHITESPACE` | password field | 비밀번호에는 공백을 포함할 수 없습니다.     |

### 9.3 nickname

| code                           | 표시 위치          | 사용자 표시 문구                  |
| ------------------------------ | -------------- | -------------------------- |
| `AUTH_NICKNAME_REQUIRED`       | nickname field | 닉네임은 비어 있을 수 없습니다.         |
| `AUTH_NICKNAME_INVALID_LENGTH` | nickname field | 닉네임은 2자 이상 12자 이하로 입력해주세요. |
| `AUTH_NICKNAME_DUPLICATED`     | nickname field | 이미 사용 중인 닉네임입니다.           |
| `AUTH_NICKNAME_FORBIDDEN_WORD` | nickname field | 금칙어가 포함된 닉네임은 사용할 수 없습니다.  |

### 9.4 refreshToken

| code                          | 표시 위치                  | 사용자 표시 문구                    |
| ----------------------------- | ---------------------- | ---------------------------- |
| `AUTH_REFRESH_TOKEN_REQUIRED` | global 또는 hidden field | Refresh Token은 비어 있을 수 없습니다. |

---

## 10. 프론트엔드 사용자 표시 기준

### 10.1 Field Error

`field`가 존재하면 해당 입력 필드 하단에 표시한다.

예시:

```json
{
  "code": "AUTH_LOGIN_ID_REQUIRED",
  "message": "로그인 ID를 입력해주세요.",
  "field": "loginId"
}
```

처리:

```text
loginId input 하단에 "로그인 ID를 입력해주세요." 표시
```

### 10.2 Global Error

`field`가 `null`이면 form 상단 또는 toast로 표시한다.

예시:

```json
{
  "code": "AUTH_INVALID_CREDENTIALS",
  "message": "로그인 ID 또는 비밀번호가 올바르지 않습니다.",
  "field": null
}
```

처리:

```text
로그인 form 상단에 "로그인 ID 또는 비밀번호가 올바르지 않습니다." 표시
```

### 10.3 인증 만료

다음 코드는 인증 만료 또는 재인증 필요 상태로 처리한다.

* `AUTH_UNAUTHENTICATED`
* `AUTH_INVALID_AUTHORIZATION`
* `AUTH_INVALID_REFRESH_TOKEN`
* `AUTH_SESSION_EXPIRED`

권장 처리:

1. 저장된 accessToken / refreshToken 제거
2. 사용자 인증 상태 초기화
3. 로그인 또는 게스트 입장 화면으로 이동
4. 필요 시 toast 표시

권장 사용자 표시 문구:

```text
로그인이 만료되었습니다. 다시 로그인해주세요.
```

### 10.4 일시 장애

다음 코드는 서버 또는 Redis 등 외부 의존성의 일시 장애로 처리한다.

* `AUTH_TEMPORARY_UNAVAILABLE`

권장 사용자 표시 문구:

```text
일시적인 오류가 발생했습니다. 잠시 후 다시 시도해주세요.
```

---

## 11. Validation 우선순위

여러 필드 검증 오류가 동시에 발생하면 백엔드는 하나의 에러만 반환한다.

현재 우선순위는 다음과 같다.

1. required 계열
2. whitespace 계열
3. length 계열
4. format 계열
5. invalid request body
6. 기타 인증 에러

따라서 프론트엔드는 한 번의 요청에서 여러 필드 에러 배열이 내려올 것을 기대하지 않는다.

---

## 12. 프론트엔드 구현 시 주의사항

* `message` 문자열로 분기하지 않는다.
* `code`와 `field`를 기준으로 UI를 제어한다.
* `field === null`인 에러는 특정 input이 아니라 form/global 영역에 표시한다.
* `passwordConfirm`은 백엔드로 보내지 않는다.
* 토큰 재발급 실패 시 기존 토큰을 제거하고 재로그인 흐름으로 보낸다.
* 로그아웃 API 성공 후에는 프론트엔드 저장소의 인증 상태도 반드시 초기화한다.

