# 인증 API 에러 코드

## 목적

인증 API는 프론트엔드가 사용자 표시 메시지 문자열에 의존하지 않도록 `code`, `message`, `field` 기반 에러 응답을 반환합니다.

프론트엔드는 `message`가 아니라 `code`와 `field`를 기준으로 에러 UI를 제어해야 합니다.

---

## 에러 응답 포맷

```json
{
  "code": "AUTH_INVALID_CREDENTIALS",
  "message": "로그인 ID 또는 비밀번호가 올바르지 않습니다.",
  "field": null
}
```

| 필드 | 설명 |
|---|---|
| `code` | 프론트엔드 분기 기준이 되는 안정적인 에러 코드 |
| `message` | 사용자에게 표시할 수 있는 기본 메시지 |
| `field` | 특정 입력 필드와 연결되는 경우 필드명, 전역 에러면 `null` |

---

## 프론트엔드 처리 원칙

프론트엔드는 아래처럼 `message` 문자열이 아니라 `code`를 기준으로 분기합니다.

```ts
if (error.code === 'AUTH_LOGIN_ID_DUPLICATED') {
  setLoginIdError(error.message);
}
```

다음 방식은 사용하지 않습니다.

```ts
if (error.message === '이미 사용 중인 로그인 ID입니다.') {
  setLoginIdError(error.message);
}
```

사용자 표시 문구는 백엔드에서 변경될 수 있지만, `code`는 API 계약이므로 안정적으로 유지합니다.

---

## 로그인 ID 에러 코드

| code | message | field | HTTP Status |
|---|---|---|---:|
| `AUTH_LOGIN_ID_REQUIRED` | 로그인 ID는 비어 있을 수 없습니다. | `loginId` | 400 |
| `AUTH_LOGIN_ID_INVALID_LENGTH` | 로그인 ID는 4자 이상 50자 이하여야 합니다. | `loginId` | 400 |
| `AUTH_LOGIN_ID_INVALID_FORMAT` | 로그인 ID는 영문과 숫자만 사용할 수 있습니다. | `loginId` | 400 |
| `AUTH_LOGIN_ID_CONTAINS_WHITESPACE` | 로그인 ID에는 공백을 포함할 수 없습니다. | `loginId` | 400 |
| `AUTH_LOGIN_ID_DUPLICATED` | 이미 사용 중인 로그인 ID입니다. | `loginId` | 409 |

---

## 비밀번호 에러 코드

| code | message | field | HTTP Status |
|---|---|---|---:|
| `AUTH_PASSWORD_REQUIRED` | 비밀번호는 비어 있을 수 없습니다. | `password` | 400 |
| `AUTH_PASSWORD_INVALID_LENGTH` | 비밀번호는 8자 이상 100자 이하여야 합니다. | `password` | 400 |
| `AUTH_PASSWORD_CONTAINS_WHITESPACE` | 비밀번호에는 공백을 포함할 수 없습니다. | `password` | 400 |

---

## 닉네임 에러 코드

| code | message | field | HTTP Status |
|---|---|---|---:|
| `AUTH_NICKNAME_REQUIRED` | 닉네임은 비어 있을 수 없습니다. | `nickname` | 400 |
| `AUTH_NICKNAME_INVALID_LENGTH` | 닉네임은 2자 이상 12자 이하여야 합니다. | `nickname` | 400 |
| `AUTH_NICKNAME_DUPLICATED` | 이미 사용 중인 닉네임입니다. | `nickname` | 409 |
| `AUTH_NICKNAME_FORBIDDEN_WORD` | 사용할 수 없는 닉네임입니다. | `nickname` | 400 |

> `AUTH_NICKNAME_FORBIDDEN_WORD`는 금칙어 검증 정책 추가 시 사용합니다.

---

## 로그인/세션 에러 코드

| code | message | field | HTTP Status |
|---|---|---|---:|
| `AUTH_INVALID_CREDENTIALS` | 로그인 ID 또는 비밀번호가 올바르지 않습니다. | `null` | 401 |
| `AUTH_ACCOUNT_LOCKED` | 로그인 시도가 너무 많습니다. 15분 후 다시 시도해주세요. | `null` | 423 |
| `AUTH_UNAUTHENTICATED` | 인증 정보가 없습니다. | `null` | 401 |
| `AUTH_INVALID_AUTHORIZATION` | Authorization 헤더가 유효하지 않습니다. | `null` | 401 |
| `AUTH_INVALID_REFRESH_TOKEN` | Refresh Token이 유효하지 않습니다. | `null` | 401 |
| `AUTH_SESSION_EXPIRED` | 세션이 만료되었습니다. | `null` | 401 |
| `AUTH_TEMPORARY_UNAVAILABLE` | 일시적인 오류가 발생했습니다. 잠시 후 다시 시도해주세요. | `null` | 503 |
| `AUTH_INVALID_REQUEST_BODY` | 요청 본문 형식이 올바르지 않습니다. | `null` | 400 |

---

## API별 주요 에러

### `POST /api/auth/register`

| 상황 | code |
|---|---|
| 로그인 ID 누락 | `AUTH_LOGIN_ID_REQUIRED` |
| 로그인 ID 공백 포함 | `AUTH_LOGIN_ID_CONTAINS_WHITESPACE` |
| 로그인 ID 중복 | `AUTH_LOGIN_ID_DUPLICATED` |
| 비밀번호 누락 | `AUTH_PASSWORD_REQUIRED` |
| 비밀번호 공백 포함 | `AUTH_PASSWORD_CONTAINS_WHITESPACE` |
| 닉네임 누락 | `AUTH_NICKNAME_REQUIRED` |
| 닉네임 길이 오류 | `AUTH_NICKNAME_INVALID_LENGTH` |
| 닉네임 중복 | `AUTH_NICKNAME_DUPLICATED` |

---

### `POST /api/auth/login`

| 상황 | code |
|---|---|
| 로그인 ID 누락 | `AUTH_LOGIN_ID_REQUIRED` |
| 로그인 ID 길이 오류 | `AUTH_LOGIN_ID_INVALID_LENGTH` |
| 로그인 ID 형식 오류 | `AUTH_LOGIN_ID_INVALID_FORMAT` |
| 비밀번호 누락 | `AUTH_PASSWORD_REQUIRED` |
| 비밀번호 길이 오류 | `AUTH_PASSWORD_INVALID_LENGTH` |
| 로그인 ID 또는 비밀번호 불일치 | `AUTH_INVALID_CREDENTIALS` |
| 계정 잠금 | `AUTH_ACCOUNT_LOCKED` |

---

### `POST /api/auth/guest`

| 상황 | code |
|---|---|
| 닉네임 누락 | `AUTH_NICKNAME_REQUIRED` |
| 닉네임 길이 오류 | `AUTH_NICKNAME_INVALID_LENGTH` |
| 닉네임 중복 | `AUTH_NICKNAME_DUPLICATED` |
| 닉네임 금칙어 포함 | `AUTH_NICKNAME_FORBIDDEN_WORD` |

---

### `POST /api/auth/refresh`

| 상황 | code |
|---|---|
| Refresh Token 누락 | `AUTH_INVALID_REFRESH_TOKEN` |
| Refresh Token 형식 오류 | `AUTH_INVALID_REFRESH_TOKEN` |
| Refresh Token 불일치 | `AUTH_INVALID_REFRESH_TOKEN` |
| 세션 만료 | `AUTH_SESSION_EXPIRED` |
| 세션 저장소 일시 오류 | `AUTH_TEMPORARY_UNAVAILABLE` |

---

### `POST /api/auth/logout`

| 상황 | code |
|---|---|
| 인증 정보 없음 | `AUTH_UNAUTHENTICATED` |
| Authorization 헤더 누락 | `AUTH_INVALID_AUTHORIZATION` |
| Authorization 헤더 형식 오류 | `AUTH_INVALID_AUTHORIZATION` |
| Access Token 파싱 실패 | `AUTH_INVALID_AUTHORIZATION` |

---

### 공통 요청 오류

| 상황 | code |
|---|---|
| JSON body 형식 오류 | `AUTH_INVALID_REQUEST_BODY` |

---

## 주의사항

### 로그인 실패 메시지 통합

로그인 실패 시 로그인 ID가 존재하지 않는 경우와 비밀번호가 틀린 경우는 모두 아래 코드로 통합합니다.

```text
AUTH_INVALID_CREDENTIALS
```

계정 존재 여부 추측을 막기 위한 정책입니다.

---

### `message`는 표시용입니다

프론트엔드는 `message`를 분기 기준으로 사용하지 않습니다.

허용:

```ts
switch (error.code) {
  case 'AUTH_PASSWORD_REQUIRED':
    setPasswordError(error.message);
    break;
}
```

비허용:

```ts
if (error.message === '비밀번호는 비어 있을 수 없습니다.') {
  setPasswordError(error.message);
}
```

---

### `field`가 null인 경우

`field`가 `null`이면 특정 입력 필드가 아니라 인증 상태 전체에 대한 에러입니다.

예:

```json
{
  "code": "AUTH_INVALID_CREDENTIALS",
  "message": "로그인 ID 또는 비밀번호가 올바르지 않습니다.",
  "field": null
}
```

프론트엔드는 이런 에러를 form 상단 또는 toast 영역에 표시합니다.
