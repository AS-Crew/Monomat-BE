# Monomat-BE Architecture

## 📦 패키지 구조

```text
src/main/java/io/github/ascrew/monomatbe/
├── MonomatBeApplication.java
│
├── domain/                                         # 비즈니스 도메인 레이어
│   ├── auth/                                       # 인증 도메인
│   │   ├── controller/
│   │   │   └── AuthController.java                 # REST 인증 엔드포인트 (/api/auth/**)
│   │   ├── dto/
│   │   │   ├── GuestLoginRequest.java              # 게스트 로그인 요청 DTO
│   │   │   └── GuestLoginResponse.java             # 게스트 로그인 응답 DTO (JWT 포함)
│   │   ├── entity/
│   │   │   ├── User.java                           # 사용자 엔티티 (게스트/회원 통합)
│   │   │   ├── GuestSession.java                   # 게스트 세션 엔티티
│   │   │   ├── UserCredential.java                 # 회원 인증정보 엔티티 (후속 이슈 대비)
│   │   │   └── UserSession.java                    # 회원 세션 엔티티 (후속 이슈 대비)
│   │   ├── repository/
│   │   │   ├── UserRepository.java
│   │   │   ├── GuestSessionRepository.java
│   │   │   ├── UserCredentialRepository.java
│   │   │   └── UserSessionRepository.java
│   │   └── service/
│   │       └── GuestAuthService.java               # 게스트 로그인/세션 발급 로직
│   │
│   ├── chat/                                       # 채팅 도메인
│   │   ├── controller/
│   │   │   └── ChatController.java                 # STOMP 채팅 메시지 수신 및 라우팅
│   │   └── service/
│   │       └── ChatService.java                    # 채팅 메시지 처리 및 발행
│   │
│   └── lobby/                                      # 로비 도메인
│       ├── LeaveLobbyResult.java                   # 퇴장 처리 결과 (sealed interface)
│       ├── controller/
│       │   ├── LobbyController.java                # HTTP REST API (로비 생성, 목록 조회)
│       │   └── LobbyEventController.java           # STOMP 로비 이벤트 수신
│       ├── dto/
│       │   ├── CreateLobbyRequest.java             # 로비 생성 요청 DTO
│       │   ├── CreateLobbyResponse.java            # 로비 생성 응답 DTO (inviteCode 포함)
│       │   └── LobbyRedisDto.java                  # 로비 목록 조회 응답 DTO
│       ├── entity/
│       │   ├── GameLobby.java                      # GAME_LOBBY 테이블 엔티티
│       │   ├── LobbyDefaults.java                  # 로비 생성 기본값 및 상수
│       │   └── LobbyStatus.java                    # 로비 상태 열거형 (WAITING, PLAYING, FINISHED)
│       ├── repository/
│       │   ├── GameLobbyJpaRepository.java         # GAME_LOBBY JPA 리포지토리
│       │   ├── LobbyRepository.java                # 로비 Redis 데이터 접근 인터페이스
│       │   └── LobbyRepositoryImpl.java            # Redis 기반 구현체 (SETNX, Lua 스크립트 활용)
│       └── service/
│           ├── LobbyEventService.java              # 로비 이벤트 처리 및 STOMP 브로드캐스트
│           └── LobbyService.java                   # 로비 생성/조회 비즈니스 로직
│
└── global/                                         # 전역 인프라 레이어
    ├── config/                                     # 애플리케이션 설정
    │   ├── RedisConfig.java                        # Redis 연결, 직렬화, Pub/Sub 설정
    │   ├── RedisScriptConfig.java                  # create/enter/leave Lua 스크립트 Bean 등록
    │   ├── WebSocketConfig.java                    # STOMP 엔드포인트 및 브로커 설정
    │   └── SecurityConfig/
    │       ├── SecurityConfigDev.java              # 개발 환경 (인증 없이 전체 허용)
    │       └── SecurityConfigProd.java             # 운영 환경 (전체 인증 필요)
    │
    ├── constant/                                   # 전역 상수 클래스
    │   ├── RedisKeys.java                          # Redis 키 패턴 및 Hash 필드명 상수
    │   ├── StompDestinations.java                  # STOMP 송신/구독 경로 상수
    │   └── WebSocketHeaders.java                   # WebSocket 헤더 키 및 세션 속성 키 상수
    │
    ├── redis/                                      # Redis Pub/Sub 인프라
    │   ├── RedisPublisher.java                     # Redis 채널 메시지 발행
    │   └── RedisSubscriber.java                    # Redis 채널 메시지 수신 → WebSocket 브로드캐스트
    │
    ├── websocket/                                  # WebSocket 인프라
    │   ├── CustomStompErrorHandler.java            # STOMP ERROR 프레임 핸들러
    │   ├── StompChannelInterceptor.java            # CONNECT/SUBSCRIBE/SEND 인증 검증
    │   ├── WebSocketEventListener.java             # WebSocket 연결 생명주기 이벤트 처리
    │   ├── WebSocketMetric.java                    # 활성 세션 수 Prometheus 메트릭
    │   ├── WebSocketSessionUtils.java              # 세션 식별자 추출 공통 유틸
    │   ├── dto/
    │   │   └── ChatMessageDto.java                 # WebSocket 채팅 메시지 DTO
    │   └── event/
    │       └── PlayerLeaveEvent.java               # 플레이어 퇴장 Spring 이벤트 객체
    └── security/
        ├── SecurityEndpoints.java                  # Security 경로 상수 중앙화
        └── jwt/
            ├── CustomPrincipal.java                # JWT 인증 주체 (userId + userIdentifier)
            ├── JwtAuthenticationFilter.java        # Bearer 토큰 검증 및 SecurityContext 저장
            ├── JwtClaims.java                      # JWT 클레임 키 상수 (발급/검증 공유)
            ├── JwtTokenProvider.java               # JWT Access/Refresh 토큰 발급
            └── TokenWithExpiry.java                # 토큰 + 만료시각 DTO
```

---

## 🏛️ 아키텍처 원칙

### 의존 방향 규칙

```text
domain  →  global  (허용 ✅)
global  →  domain  (금지 ❌)
```

`global` 패키지는 도메인에 종속되지 않는 순수 인프라 레이어입니다.  
`global`이 `domain`을 직접 참조하면 의존 방향이 역전되어 순환 의존 및 결합도 증가 문제가 발생합니다.

### 의존 방향 역전 해결 사례 — Spring ApplicationEventPublisher

`WebSocketEventListener(global)`가 `LobbyEventService(domain)`를 직접 호출하던 문제를 아래와 같이 해결했습니다.

```text
[기존 — 의존 방향 역전]
global/WebSocketEventListener → domain/LobbyEventService  ❌

[변경 — 이벤트 기반 분리]
global/WebSocketEventListener → ApplicationEventPublisher.publishEvent(PlayerLeaveEvent)
                                            ↓
                               domain/LobbyEventService @EventListener  ✅
```

`PlayerLeaveEvent`는 `global/websocket/event`에 위치하여 `domain → global` 단방향 의존을 유지합니다.

---

## 🔄 실시간 메시지 흐름

### 채팅 메시지 흐름

```text
클라이언트
    │ STOMP /app/chat/global (또는 /app/chat/lobby/{code})
    ▼
ChatController
    │
    ▼
ChatService
    │ sender 위변조 방지 — 클라이언트 sender 무시, 세션 식별자로 교체
    │
    ▼
RedisPublisher.publish()
    │ Redis Pub/Sub 발행
    ▼
Redis
    │
    ▼
RedisSubscriber.onMessage()
    │ JSON 문자열 그대로 WebSocket 브로드캐스트
    ▼
SimpMessagingTemplate.convertAndSend()
    │ STOMP /topic/chat/global (또는 /topic/lobby/{code})
    ▼
클라이언트 (구독자 전체)
```

### WebSocket 로비 입장 흐름

```text
클라이언트
    │ STOMP SUBSCRIBE /topic/lobby/{code}
    ▼
WebSocketEventListener.handleSubscribeEvent()
    │ 1. 로비 채팅 채널 구독 여부 확인
    │    - /topic/lobby/{code}         → 입장 처리 대상
    │    - /topic/lobby/{code}/refresh → 입장 처리 대상 아님
    │ 2. 세션에서 userIdentifier 추출
    │ 3. wsSessionId 추출
    │
    ▼
enter_lobby.lua
    │ Redis Lua 스크립트로 입장 상태를 원자적으로 저장
    │
    ├── lobby:{code}:participants Set에 userIdentifier 저장
    ├── lobby:{code}:order List에 userIdentifier 저장
    ├── ws:connection:{wsSessionId} Hash에 userId/lobbyCode 저장
    └── 중복 구독 시 order List 중복 저장 방지
    │
    ▼
RedisPublisher.publish()
    │ ENTER 시스템 메시지 발행
    ▼
Redis Pub/Sub
    │
    ▼
RedisSubscriber.onMessage()
    │ JSON 문자열 그대로 WebSocket 브로드캐스트
    ▼
클라이언트
    │ /topic/lobby/{code} 구독자로 ENTER 메시지 수신
```

> 로비 입장 처리는 `/topic/lobby/{code}` 구독 시점에만 수행합니다.  
> `/topic/lobby/{code}/refresh` 구독은 로비 정보 갱신 신호 수신용이며, 입장 처리 대상이 아닙니다.

### WebSocket 연결 해제 흐름

```text
클라이언트 연결 해제
    │
    ▼
WebSocketEventListener.handleDisconnectEvent()
    │ 1. Redis ws:connection:{wsSessionId} 에서 lobbyCode 역추적
    │ 2. ApplicationEventPublisher.publishEvent(PlayerLeaveEvent)
    │ 3. LEAVE 메시지 브로드캐스트
    │ 4. Redis 키 정리 (user_status, ws:connection)
    │
    ▼ (이벤트 전달)
LobbyEventService.handlePlayerLeave(@EventListener)
    │ Lua 스크립트로 원자적 퇴장 처리
    ▼
leave_lobby.lua
    ├── DESTROYED → 전역 로비 리스트 새로고침
    ├── DELEGATED → 로비 내부 새로고침 (새 방장 ID 포함)
    └── LEFT      → 로비 내부 새로고침
```

---

## 🗄️ Redis 데이터 구조

| 키 패턴 | 타입 | 설명 |
|---|---|---|
| `lobby:{code}` | Hash | 로비 메타 정보 (title, status, host_user_id 등) |
| `lobby:{code}:participants` | Set | 로비 참여자 식별자 목록 |
| `lobby:{code}:order` | List | 입장 순서 (방장 위임 시 LINDEX 0 사용) |
| `lobby:public` | Set | 공개 로비 코드 목록 (고속 필터링용) |
| `auth:guest:session:{token}` | Hash | 게스트 세션 정보 (userId, username, userType, TTL 30일) |
| `auth:refresh:{sessionId}` | String | Refresh Token (TTL 30일) |
| `user_status:{userIdentifier}` | String | 사용자 온라인 상태 (`ONLINE`, TTL 2시간) |
| `ws:connection:{wsSessionId}` | Hash | WebSocket 세션 → userId, lobbyCode 매핑 |

> ⚠️ `host_user_id` 필드명은 `leave_lobby.lua`에 하드코딩되어 있습니다. 변경 시 Lua 스크립트도 함께 수정해야 합니다.

### Redis Hash 필드 상수 (`RedisKeys.FIELD_*`)

로비 Hash(`lobby:{code}`) 내부 필드명은 `RedisKeys` 클래스의 `FIELD_*` 상수로 중앙 관리합니다.  
문자열 리터럴 직접 사용 시 오타가 런타임에서야 발견되는 문제를 컴파일 타임에 방지합니다.

### WebSocket 로비 입장 저장 구조

로비 입장 상태는 `enter_lobby.lua`에서 원자적으로 저장합니다.

| 키 패턴 | 타입 | 저장 시점 | 설명 |
|---|---|---|---|
| `lobby:{code}:participants` | Set | `/topic/lobby/{code}` 구독 시 | 현재 로비에 참여 중인 userIdentifier 목록 |
| `lobby:{code}:order` | List | `/topic/lobby/{code}` 구독 시 | 입장 순서. 방장 퇴장 시 다음 방장 위임 기준 |
| `ws:connection:{wsSessionId}` | Hash | `/topic/lobby/{code}` 구독 시 | WebSocket 세션 ID로 userIdentifier와 lobbyCode를 역추적하기 위한 매핑 |

`ws:connection:{wsSessionId}` Hash 필드:

| 필드 | 값 | 설명 |
|---|---|---|
| `userId` | `userIdentifier` | Redis/WebSocket에서 사용하는 사용자 식별자 |
| `lobbyCode` | `{code}` | 현재 WebSocket 세션이 참여 중인 로비 코드 |

정상 저장 예시:

```redis
SMEMBERS lobby:R2VJW5:participants
1) "9746cc76-f8f2-4859-b602-df6e1032fea4"

LRANGE lobby:R2VJW5:order 0 -1
1) "9746cc76-f8f2-4859-b602-df6e1032fea4"

HGETALL ws:connection:mhrg4it0
1) "userId"
2) "9746cc76-f8f2-4859-b602-df6e1032fea4"
3) "lobbyCode"
4) "R2VJW5"
```

중복 구독 시 `participants`는 Set 구조로 중복 저장되지 않으며, `order` List도 `enter_lobby.lua`에서 신규 입장자일 때만 `RPUSH`하여 중복 저장을 방지합니다.

---

## ⚡ 핵심 설계 결정

### Java 21 Virtual Thread + Lettuce

가상 스레드 환경에서 Redis 클라이언트로 Jedis 대신 **Lettuce**를 사용합니다.  
Jedis는 동기 블로킹 방식으로 가상 스레드를 캐리어 스레드에 핀닝(Pinning)할 수 있으나,  
Lettuce는 Netty 기반 비동기 드라이버로 핀닝 없이 동작합니다.

### Lua 스크립트 기반 원자적 입장 처리

`enter_lobby.lua`는 WebSocket 로비 입장 시 필요한 Redis 상태 변경을 단일 원자 연산으로 처리합니다.

처리 대상:

```text
lobby:{code}:participants
lobby:{code}:order
ws:connection:{wsSessionId}
```

Java 코드에서 위 Redis 명령을 개별적으로 실행하면 중간 실패 시 다음과 같은 상태 불일치가 발생할 수 있습니다.

```text
participants에는 추가됐지만 order에는 없음
order에는 추가됐지만 ws:connection 매핑이 없음
ws:connection은 있지만 participants에는 없음
```

이를 방지하기 위해 `enter_lobby.lua`에서 아래 작업을 한 번에 수행합니다.

```text
1. lobby:{code} 존재 여부 확인
2. participants Set에 userIdentifier 추가
3. 신규 입장자인 경우에만 order List에 userIdentifier 추가
4. ws:connection:{wsSessionId} Hash에 userId/lobbyCode 저장
5. ws:connection:{wsSessionId} TTL 설정
```

반환값은 다음과 같습니다.

| 반환값 | 의미 |
|---|---|
| `ENTERED` | 신규 입장 처리 완료 |
| `ALREADY_JOINED` | 이미 참여 중인 유저의 중복 구독. order 중복 저장 없음 |
| `LOBBY_NOT_FOUND` | 존재하지 않는 로비 코드로 구독 요청 |

입장 처리는 반드시 `/topic/lobby/{code}` 구독 시점에만 수행합니다.  
`/topic/lobby/{code}/refresh`는 입장 처리를 트리거하지 않습니다.

### Lua 스크립트 기반 원자적 퇴장 처리

`leave_lobby.lua`는 참여자 제거 → 방장 위임 → 로비 폭파를 단일 트랜잭션으로 처리합니다.  
Redis는 싱글 스레드로 Lua 스크립트를 실행하므로 다수의 동시 퇴장 시에도 Race Condition이 발생하지 않습니다.

반환값은 `DESTROYED`, `DELEGATED:{newHostId}`, `LEFT` 세 가지이며,  
`LobbyRepositoryImpl`에서 `LeaveLobbyResult` sealed interface로 파싱하여  
서비스 레이어가 Redis 반환 포맷을 알 필요 없도록 캡슐화합니다.

### ChatMessageDto 위치

`ChatMessageDto`는 채팅 도메인 전용 객체가 아닌 WebSocket 통신 전반에서 사용되는 메시지 포맷입니다.  
`domain/chat/dto`가 아닌 `global/websocket/dto`에 위치하여  
`global` 레이어의 `RedisPublisher`, `RedisSubscriber`, `WebSocketEventListener`가  
`domain`을 역참조하는 의존 방향 역전을 방지합니다.

### Redis Pub/Sub 직렬화 분리

Redis Pub/Sub 메시지는 WebSocket 클라이언트에게 그대로 전달될 수 있으므로, 일반 Redis 객체 저장용 `RedisTemplate<String, Object>`와 분리하여 처리합니다.

#### 일반 Redis 데이터 저장

`RedisTemplate<String, Object>`는 Redis Hash/Value에 객체 타입 정보를 보존하기 위해 `GenericJacksonJsonRedisSerializer`와 `activateDefaultTyping`을 사용합니다.

사용 예:

```text
lobby:{code}
auth:guest:session:{token}
```

#### Pub/Sub 메시지 발행

Pub/Sub 메시지는 `StringRedisTemplate`과 Pub/Sub 전용 `JsonMapper`를 사용합니다.

이유:

```text
RedisTemplate<String, Object>로 ChatMessageDto를 발행하면
WebSocket 클라이언트가 ["클래스명", {...}] 형태의 메시지를 받게 됨
```

기존 문제 형태:

```json
["io.github.ascrew.monomatbe.global.websocket.dto.ChatMessageDto",{"type":"ENTER","roomId":"R2VJW5"}]
```

수정 후 형태:

```json
{"type":"ENTER","roomId":"R2VJW5","sender":"...","content":"...","timestamp":"..."}
```

`RedisSubscriber`는 Pub/Sub 메시지를 DTO로 역직렬화하지 않고, JSON 문자열 그대로 `SimpMessagingTemplate.convertAndSend()`로 WebSocket 클라이언트에게 전달합니다.

### 참여자 키 단일 진실의 원천

기존에 `lobby:{code}:participants`(Lua 스크립트 관리)와 `user_room:{lobbyCode}`(Java 레벨 관리)로  
동일한 참여자 데이터를 이중 관리하던 문제를 해결했습니다.  
`lobby:{code}:participants`를 단일 진실의 원천으로 통일하여  
입장(Lua) → 퇴장(Lua) 모두 동일한 키를 사용합니다.

### SETNX 기반 초대 코드 중복 방지

`LobbyRepositoryImpl.acquireInviteCode()`는 6자리 초대 코드를 생성할 때  
`lobby:code:lock:{code}` 키를 Redis SET NX 명령으로 원자적으로 선점합니다.  
선점 성공 시 해당 코드를 사용하고, 실패 시 최대 `LobbyDefaults.INVITE_CODE_MAX_RETRY`(5회)까지 재시도합니다.  
락 TTL은 10초로 설정하여 로비 생성 실패 시 자동 해제되어 코드 공간이 반환됩니다.

### JWT 인증 구조

`JwtAuthenticationFilter`가 모든 요청의 Authorization 헤더에서 Bearer 토큰을 파싱합니다.  
파싱 성공 시 `CustomPrincipal(userId, userIdentifier, userType)`을 생성하여 SecurityContext에 저장합니다.  
컨트롤러에서는 `@AuthenticationPrincipal CustomPrincipal`로 주입받아 사용합니다.

JWT 클레임 키는 `JwtClaims` 상수 클래스로 중앙 관리하여  
`JwtTokenProvider`(발급)와 `JwtAuthenticationFilter`(검증) 간 키 불일치를 컴파일 타임에 방지합니다.

[식별자 분리]

- `userId` (Long) : DB users.id FK 참조용
- `userIdentifier` (UUID String) : Redis/WebSocket 식별자

---

## 🌐 STOMP 채널 구조

| 방향 | 경로 | 설명 |
|---|---|---|
| 클라이언트 → 서버 | `/app/chat/global` | 전체 채팅 메시지 송신 |
| 클라이언트 → 서버 | `/app/chat/lobby/{code}` | 로비 채팅 메시지 송신 |
| 클라이언트 → 서버 | `/app/lobby/create` | 로비 생성 이벤트 송신 |
| 클라이언트 → 서버 | `/app/lobby/{code}/update` | 로비 정보 변경 이벤트 송신 |
| 서버 → 클라이언트 | `/topic/chat/global` | 전체 채팅 메시지 수신 |
| 서버 → 클라이언트 | `/topic/lobby/{code}` | 로비 채팅 메시지 수신 및 로비 입장 처리 트리거 |
| 서버 → 클라이언트 | `/topic/lobby/{code}/refresh` | 로비 내부 정보 새로고침 신호 |
| 서버 → 클라이언트 | `/topic/lobby/refresh` | 전역 로비 리스트 새로고침 신호 |

> `/topic/lobby/{code}`는 로비 채팅 메시지 수신 채널이면서 WebSocket 입장 처리 트리거입니다.  
> `/topic/lobby/{code}/refresh`는 로비 정보 새로고침 신호 수신 전용이며, 입장 처리 트리거가 아닙니다.

---

## 🔐 인증 구조

### 정식 회원 (Member)

로그인 성공 시 JWT 또는 Redis 세션을 발급받아 인증합니다.  
맵 생성, 로비 호스팅 등 전체 기능에 접근할 수 있습니다.

### 게스트 (Guest)

닉네임 입력만으로 진입하며, 백엔드에서 UUID를 발급하여 `localStorage`에 저장합니다.  
재방문 시 UUID로 자동 복원됩니다. 퀴즈 플레이 참여만 가능합니다.

### WebSocket 인증 흐름

```text
STOMP CONNECT 헤더: { userIdentifier: "UUID 또는 회원 ID" }
    │
    ▼
StompChannelInterceptor.handleConnect()
    │ UUID 형식 검증 (8-4-4-4-12 포맷)
    │ 세션에 userIdentifier 저장
    ▼
이후 모든 STOMP 명령에서 세션의 userIdentifier 검증
```

### REST API 인증 흐름

```text
HTTP 요청 (Authorization: Bearer {accessToken})
    │
    ▼
JwtAuthenticationFilter
    │ 1. Authorization 헤더에서 Bearer 토큰 추출
    │ 2. JWT 서명 검증 (secretKey)
    │ 3. userId, userIdentifier, userType 클레임 추출 (JwtClaims 상수)
    │ 4. CustomPrincipal 생성 → SecurityContext 저장
    ▼
Controller
    │ @AuthenticationPrincipal CustomPrincipal로 주입
    │ - principal.userId()          → DB Insert FK 용도
    │ - principal.userIdentifier()  → Redis 저장 식별자 용도
    ▼
Service
```