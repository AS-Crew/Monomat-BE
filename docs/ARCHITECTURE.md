# Monomat-BE Architecture

## 📦 패키지 구조

```
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
│       │   ├── LobbyController.java                # HTTP REST API (로비 목록 조회)
│       │   └── LobbyEventController.java           # STOMP 로비 이벤트 수신
│       ├── dto/
│       │   └── LobbyRedisDto.java                  # 로비 정보 응답 DTO
│       ├── repository/
│       │   ├── LobbyRepository.java                # 로비 데이터 접근 인터페이스
│       │   └── LobbyRepositoryImpl.java            # Redis 기반 구현체 (Lua 스크립트 활용)
│       └── service/
│           ├── LobbyEventService.java              # 로비 이벤트 처리 및 STOMP 브로드캐스트
│           └── LobbyService.java                   # 로비 조회 비즈니스 로직
│
└── global/                                         # 전역 인프라 레이어
    ├── config/                                     # 애플리케이션 설정
    │   ├── RedisConfig.java                        # Redis 연결, 직렬화, Pub/Sub 설정
    │   ├── RedisScriptConfig.java                  # Lua 스크립트 Bean 등록
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
        ├── CustomStompErrorHandler.java            # STOMP ERROR 프레임 핸들러
        ├── StompChannelInterceptor.java            # CONNECT/SUBSCRIBE/SEND 인증 검증
        ├── WebSocketEventListener.java             # WebSocket 연결 생명주기 이벤트 처리
        ├── WebSocketMetric.java                    # 활성 세션 수 Prometheus 메트릭
        ├── WebSocketSessionUtils.java              # 세션 식별자 추출 공통 유틸
        ├── dto/
        │   └── ChatMessageDto.java                 # WebSocket 채팅 메시지 DTO
        └── event/
            └── PlayerLeaveEvent.java               # 플레이어 퇴장 Spring 이벤트 객체
    └── security/
        └── jwt/
            ├── JwtTokenProvider.java               # JWT Access/Refresh 토큰 발급
            └── TokenWithExpiry.java                # 토큰 + 만료시각 DTO
```

---

## 🏛️ 아키텍처 원칙

### 의존 방향 규칙

```
domain  →  global  (허용 ✅)
global  →  domain  (금지 ❌)
```

`global` 패키지는 도메인에 종속되지 않는 순수 인프라 레이어입니다.
`global`이 `domain`을 직접 참조하면 의존 방향이 역전되어 순환 의존 및 결합도 증가 문제가 발생합니다.

### 의존 방향 역전 해결 사례 — Spring ApplicationEventPublisher

`WebSocketEventListener(global)`가 `LobbyEventService(domain)`를 직접 호출하던 문제를 아래와 같이 해결했습니다.

```
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

```
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
    │
    ▼
SimpMessagingTemplate.convertAndSend()
    │ STOMP /topic/chat/global (또는 /topic/lobby/{code})
    ▼
클라이언트 (구독자 전체)
```

### WebSocket 연결 해제 흐름

```
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
| `user_status:{userIdentifier}` | String | 사용자 온라인 상태 (`ONLINE`, TTL 2시간) |
| `ws:connection:{wsSessionId}` | Hash | WebSocket 세션 → userId, lobbyCode 매핑 |

> ⚠️ `host_user_id` 필드명은 `leave_lobby.lua`에 하드코딩되어 있습니다. 변경 시 Lua 스크립트도 함께 수정해야 합니다.

### Redis Hash 필드 상수 (`RedisKeys.FIELD_*`)

로비 Hash(`lobby:{code}`) 내부 필드명은 `RedisKeys` 클래스의 `FIELD_*` 상수로 중앙 관리합니다.
문자열 리터럴 직접 사용 시 오타가 런타임에서야 발견되는 문제를 컴파일 타임에 방지합니다.

---

## ⚡ 핵심 설계 결정

### Java 21 Virtual Thread + Lettuce

가상 스레드 환경에서 Redis 클라이언트로 Jedis 대신 **Lettuce**를 사용합니다.
Jedis는 동기 블로킹 방식으로 가상 스레드를 캐리어 스레드에 핀닝(Pinning)할 수 있으나,
Lettuce는 Netty 기반 비동기 드라이버로 핀닝 없이 동작합니다.

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

### 참여자 키 단일 진실의 원천

기존에 `lobby:{code}:participants`(Lua 스크립트 관리)와 `user_room:{lobbyCode}`(Java 레벨 관리)로
동일한 참여자 데이터를 이중 관리하던 문제를 해결했습니다.
`lobby:{code}:participants`를 단일 진실의 원천으로 통일하여
입장(Java) → 퇴장(Lua) 모두 동일한 키를 사용합니다.

---

## 🌐 STOMP 채널 구조

| 방향 | 경로 | 설명 |
|---|---|---|
| 클라이언트 → 서버 | `/app/chat/global` | 전체 채팅 메시지 송신 |
| 클라이언트 → 서버 | `/app/chat/lobby/{code}` | 로비 채팅 메시지 송신 |
| 클라이언트 → 서버 | `/app/lobby/create` | 로비 생성 이벤트 송신 |
| 클라이언트 → 서버 | `/app/lobby/{code}/update` | 로비 정보 변경 이벤트 송신 |
| 서버 → 클라이언트 | `/topic/chat/global` | 전체 채팅 메시지 수신 |
| 서버 → 클라이언트 | `/topic/lobby/{code}` | 로비 채팅 메시지 수신 |
| 서버 → 클라이언트 | `/topic/lobby/{code}/refresh` | 로비 내부 정보 새로고침 신호 |
| 서버 → 클라이언트 | `/topic/lobby/refresh` | 전역 로비 리스트 새로고침 신호 |

---

## 🔐 인증 구조

### 정식 회원 (Member)

로그인 성공 시 JWT 또는 Redis 세션을 발급받아 인증합니다.
맵 생성, 로비 호스팅 등 전체 기능에 접근할 수 있습니다.

### 게스트 (Guest)

닉네임 입력만으로 진입하며, 백엔드에서 UUID를 발급하여 `localStorage`에 저장합니다.
재방문 시 UUID로 자동 복원됩니다. 퀴즈 플레이 참여만 가능합니다.

### WebSocket 인증 흐름

```
STOMP CONNECT 헤더: { userIdentifier: "UUID 또는 회원 ID" }
    │
    ▼
StompChannelInterceptor.handleConnect()
    │ UUID 형식 검증 (8-4-4-4-12 포맷)
    │ 세션에 userIdentifier 저장
    ▼
이후 모든 STOMP 명령에서 세션의 userIdentifier 검증
```
