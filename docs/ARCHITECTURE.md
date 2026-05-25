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
│   │   │   ├── GuestLoginResponse.java             # 게스트 로그인 응답 DTO (JWT 포함)
│   │   │   ├── LoginRequest.java                   # 자체 로그인 요청 DTO
│   │   │   └── LoginResponse.java                  # 자체 로그인 응답 DTO (JWT + 세션 식별자)
│   │   ├── entity/
│   │   │   ├── User.java                           # 사용자 엔티티 (게스트/회원 통합)
│   │   │   ├── GuestSession.java                   # 게스트 세션 엔티티
│   │   │   ├── UserCredential.java                 # 회원 인증정보 엔티티
│   │   │   └── UserSession.java                    # 회원 세션 엔티티
│   │   ├── repository/
│   │   │   ├── UserRepository.java
│   │   │   ├── GuestSessionRepository.java
│   │   │   ├── UserCredentialRepository.java
│   │   │   └── UserSessionRepository.java
│   │   └── service/
│   │       ├── GuestAuthService.java               # 게스트 로그인/세션 발급 로직
│   │       ├── RegisterAuthService.java            # 회원가입 로직
│   │       └── LoginAuthService.java               # 자체 로그인/잠금 정책/세션 발급 로직
│   │
│   ├── chat/                                       # 채팅 도메인
│   │   ├── controller/
│   │   │   └── ChatController.java                 # STOMP 채팅 메시지 수신 및 라우팅
│   │   └── service/
│   │       └── ChatService.java                    # 채팅 메시지 처리 및 발행
│   │
│   ├── lobby/                                      # 로비 도메인
│   │   ├── KickLobbyResult.java                    # 강퇴 처리 결과 (sealed interface)
│   │   ├── LeaveLobbyResult.java                   # 퇴장 처리 결과 (sealed interface)
│   │   ├── StartLobbyResult.java                   # 게임 시작 처리 결과 (sealed interface)
│   │   ├── StartLobbyLuaResultCode.java            # start_lobby.lua 반환 문자열 계약 enum
│   │   ├── controller/
│   │   │   ├── LobbyCommandController.java         # 로비 생성, 입장 사전 검증, ready, start REST API
│   │   │   ├── LobbyQueryController.java           # 공개 로비 목록 및 로비 상세 조회 REST API
│   │   │   └── LobbyEventController.java           # STOMP 로비 이벤트 수신
│   │   ├── dto/
│   │   │   ├── CreateLobbyRequest.java             # 로비 생성 요청 DTO
│   │   │   ├── CreateLobbyResponse.java            # 로비 생성 응답 DTO
│   │   │   ├── JoinLobbyRequest.java               # 초대 코드 기반 로비 입장 요청 DTO
│   │   │   ├── JoinLobbyResponse.java              # 초대 코드 기반 로비 입장 응답 DTO
│   │   │   ├── KickLobbyPlayerRequest.java         # 로비 유저 강퇴 요청 DTO
│   │   │   ├── LobbyDetailResponse.java            # 로비 상세 조회 응답 DTO
│   │   │   ├── LobbyPlayerResponse.java            # 로비 상세 참여자 응답 DTO
│   │   │   ├── LobbyMapMetadata.java               # Redis 저장용 로비 맵 메타데이터 DTO
│   │   │   ├── LobbyRedisDto.java                  # 공개 로비 목록 조회 응답 DTO
│   │   │   ├── LobbySearchCondition.java           # 공개 로비 목록 검색/필터/정렬 조건 DTO
│   │   │   ├── LobbySortType.java                  # 공개 로비 목록 정렬 기준 enum
│   │   │   └── UpdateLobbyReadyRequest.java        # ready 상태 변경 요청 DTO
│   │   ├── entity/
│   │   │   ├── GameLobby.java                      # GAME_LOBBY 테이블 엔티티
│   │   │   ├── LobbyDefaults.java                  # 로비 생성 기본값 및 상수
│   │   │   └── LobbyStatus.java                    # 로비 상태 열거형 (WAITING, PLAYING, FINISHED)
│   │   ├── repository/
│   │   │   ├── GameLobbyJpaRepository.java         # GAME_LOBBY JPA 리포지토리
│   │   │   ├── LobbyRepository.java                # 로비 Redis 데이터 접근 인터페이스
│   │   │   ├── LobbyRepositoryImpl.java            # 로비 Repository Facade
│   │   │   └── redis/
│   │   │       ├── LobbyLuaScriptExecutor.java
│   │   │       ├── LobbyLuaResultMapper.java
│   │   │       ├── LobbyRedisCommandRepository.java
│   │   │       ├── LobbyRedisQueryRepository.java
│   │   │       └── LobbyStartReconciliationRepository.java
│   │   └── service/
│   │       ├── LobbyCanStartPolicy.java
│   │       ├── LobbyCreateService.java
│   │       ├── LobbyJoinService.java
│   │       ├── LobbyQueryService.java
│   │       ├── LobbyReadyService.java
│   │       ├── LobbyStartService.java
│   │       ├── LobbyRealtimeNotifier.java
│   │       └── LobbyStartReconciliationService.java
│   │
│   ├── map/                                        # 퀴즈 맵 도메인
│   │   ├── controller/
│   │   │   └── QuizMapController.java
│   │   ├── dto/
│   │   ├── entity/
│   │   │   ├── QuizMap.java
│   │   │   └── MapCategory.java
│   │   ├── repository/
│   │   │   └── QuizMapJpaRepository.java
│   │   └── service/
│   │       └── QuizMapService.java
│   │
│   └── game/                                       # 인게임 도메인
│       ├── controller/
│       ├── dto/
│       ├── entity/
│       ├── repository/
│       └── service/
│
└── global/                                         # 전역 인프라 레이어
    ├── config/
    │   ├── RedisConfig.java
    │   ├── RedisScriptConfig.java
    │   ├── SchedulingConfig.java
    │   ├── WebSocketConfig.java
    │   └── SecurityConfig/
    ├── constant/
    │   ├── RedisKeys.java
    │   ├── StompDestinations.java
    │   └── WebSocketHeaders.java
    ├── redis/
    │   ├── RedisPublisher.java
    │   └── RedisSubscriber.java
    ├── websocket/
    │   ├── CustomStompErrorHandler.java
    │   ├── StompChannelInterceptor.java
    │   ├── WebSocketEventListener.java
    │   ├── WebSocketMetric.java
    │   ├── WebSocketSessionUtils.java
    │   ├── error/
    │   │   ├── StompErrorAction.java
    │   │   ├── StompErrorCode.java
    │   │   ├── StompErrorException.java
    │   │   └── StompErrorPayload.java
    │   └── event/
    │       └── PlayerLeaveEvent.java
    └── security/
        ├── SecurityEndpoints.java
        └── jwt/
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

### 이벤트 기반 의존 역전 해소

`WebSocketEventListener(global)`가 로비 도메인을 직접 호출하지 않도록 `ApplicationEventPublisher`를 사용합니다.

```text
[기존 — 의존 방향 역전]
global/WebSocketEventListener → domain/LobbyEventService  ❌

[변경 — 이벤트 기반 분리]
global/WebSocketEventListener → ApplicationEventPublisher.publishEvent(PlayerLeaveEvent)
                                            ↓
                               domain/LobbyEventService @EventListener  ✅
```

`PlayerLeaveEvent`는 `global/websocket/event`에 위치하고, 도메인 레이어가 이 이벤트를 구독합니다.

### 도메인 간 의존 규칙

동일한 `domain` 하위 도메인 간 참조는 비즈니스 흐름상 허용하지만, 직접 엔티티 결합은 최소화합니다.

예를 들어 로비 생성 시 맵을 연결할 때 `LobbyCreateService`는 맵 접근 권한을 검증합니다.  
하지만 Redis 저장 계층은 `QuizMap` 엔티티에 직접 의존하지 않고 `LobbyMapMetadata`만 전달받습니다.

```text
LobbyCreateService
    ├── QuizMap 조회
    ├── 맵 존재/삭제/권한 검증
    └── LobbyMapMetadata 생성
            ↓
LobbyRepositoryImpl
    └── Redis 저장에 필요한 mapId, mapTitle, mapCategory만 사용
```

---

## 📋 공개 로비 목록 조회 정책

공개 로비 목록 조회는 `GET /api/lobbies`에서 처리합니다.  
이 API는 FE 로비 목록 화면에서 사용자가 입장 가능한 공개 로비를 빠르게 찾기 위한 조회 API입니다.

이슈 #88 이후 공개 로비 목록 조회는 페이징 응답을 반환하며, 필터가 없는 경우 Redis Sorted Set 정렬 인덱스를 사용해 필요한 범위의 로비 코드만 조회합니다.

```text
Client
    │ GET /api/lobbies?keyword=&mapCategory=&sort=&page=&size=
    ▼
LobbyQueryController
    │ 요청 파라미터 수집
    ▼
LobbySearchCondition
    │ 요청 조건 정규화
    ▼
LobbyQueryService
    │ 로비 목록 노출 정책 적용
    │ - WAITING / PLAYING 상태 노출
    │ - FINISHED 상태 제외
    │ - keyword 제목 검색
    │ - mapCategory 필터링
    │ - latest / most_players / most_available 정렬
    │ - page / size 기준 페이징
    │ - Redis 손상 mapCategory 로비 제외
    │
    ├─ [필터 없음 + 정렬 인덱스 존재]
    │      Redis ZSET 인덱스에서 필요한 범위의 lobbyCode만 조회
    │
    └─ [필터 있음 또는 정렬 인덱스 없음]
           lobby:public 전체 조회 후 Java 필터/정렬/페이징
    ▼
LobbyRepository
    ▼
LobbyRedisQueryRepository
    ▼
Redis
```

### 책임 분리

| 계층 | 책임 |
| --- | --- |
| `LobbyQueryController` | HTTP 요청 파라미터 수집 |
| `LobbySearchCondition` | 요청값 정규화 및 검증 |
| `LobbyPageRequest` | `page`, `size` 기본값/범위 검증 |
| `LobbyQueryService` | 로비 목록 노출 정책, 검색, 필터링, 정렬, 페이징, 인덱스 사용 여부 결정 |
| `LobbyRedisQueryRepository` | Redis 원본 공개 로비 데이터 조회, ZSET 인덱스 조회, stale index 정리 |

### 노출 정책

* 공개 로비 목록에는 `WAITING`, `PLAYING` 상태 로비가 노출됩니다.
* `FINISHED` 상태 로비는 목록에서 제외됩니다.
* `WAITING` 로비는 입장 가능한 로비입니다.
* `PLAYING` 로비는 진행 중 상태로 목록에는 노출되지만, 현재 입장은 허용하지 않습니다.
* 클라이언트는 `status=PLAYING` 로비를 “진행 중” 상태로 표시하고 입장 버튼을 비활성화해야 합니다.

### 페이징 정책

| 항목 | 정책 |
| --- | --- |
| `page` | 0-based 페이지 번호 |
| `page` 기본값 | `0` |
| `size` 기본값 | `20` |
| `size` 최소값 | `1` |
| `size` 최대값 | `100` |
| 응답 구조 | `items`, `page`, `size`, `hasNext` |

### 정렬 정책

| 정렬 기준 | 설명 | Redis 인덱스 |
| --- | --- | --- |
| `latest` | `createdAtEpochMillis` 내림차순 | `lobby:public:latest` |
| `most_players` | `currentPlayers` 내림차순 | `lobby:public:most_players` |
| `most_available` | `maxPlayers - currentPlayers` 내림차순 | `lobby:public:most_available` |

`lobby:public`은 Redis Set이므로 삽입 순서가 보장되지 않습니다.  
따라서 로비 생성 시 Redis Hash에 `created_at_epoch_millis`를 저장하고, 이 값을 기준으로 최신순 정렬을 수행합니다.

### Redis 정렬 인덱스

| Redis Key | Type | Member | Score | 갱신 시점 |
| --- | --- | --- | --- | --- |
| `lobby:public:latest` | ZSET | lobby code | `created_at_epoch_millis` | 공개 로비 생성 시 `ZADD`, 로비 폭파/삭제 시 `ZREM` |
| `lobby:public:most_players` | ZSET | lobby code | `current_players` | 공개 로비 생성/입장/퇴장/강퇴 시 갱신 |
| `lobby:public:most_available` | ZSET | lobby code | `max_players - current_players` | 공개 로비 생성/입장/퇴장/강퇴 시 갱신 |

### 정렬 인덱스 사용 조건

| 조건 | 조회 방식 |
| --- | --- |
| `keyword` 없음 + `mapCategory` 없음 + 정렬 인덱스 존재 | Redis ZSET에서 page 범위의 lobbyCode만 조회 |
| `keyword` 있음 | `lobby:public` 전체 조회 후 Java 필터/정렬/페이징 |
| `mapCategory` 있음 | `lobby:public` 전체 조회 후 Java 필터/정렬/페이징 |
| 정렬 인덱스 없음 | `lobby:public` 전체 조회 후 Java 필터/정렬/페이징 |

필터가 있는 경우 ZSET page 범위를 먼저 자르면 전체 필터링 결과 기준의 page가 깨질 수 있습니다.  
따라서 `keyword`, `mapCategory` 필터가 있는 경우는 안전하게 기존 전체 조회 경로로 폴백합니다.

---

## 🔄 실시간 메시지 흐름

### 채팅 메시지 흐름

```text
클라이언트
    │ STOMP /app/chat/global 또는 /app/chat/lobby/{code}
    ▼
ChatController
    ▼
ChatService
    │ sender 위변조 방지 — 클라이언트 sender 무시, 세션 식별자로 교체
    ▼
RedisPublisher.publish()
    ▼
Redis Pub/Sub
    ▼
RedisSubscriber.onMessage()
    │ JSON 문자열 그대로 WebSocket 브로드캐스트
    ▼
SimpMessagingTemplate.convertAndSend()
    ▼
클라이언트
```

### WebSocket 로비 입장 흐름

```text
클라이언트
    │ STOMP SUBSCRIBE /topic/lobby/{code}
    ▼
StompChannelInterceptor.preSend()
    │ enter_lobby.lua 실행
    │ Redis 입장 상태가 확정된 경우에만 SUBSCRIBE 통과
    ▼
enter_lobby.lua
    ├── lobby:{code} 존재 여부 확인
    ├── WAITING 상태 확인
    ├── kicked Set 확인
    ├── sessionSequence 비교
    ├── 최대 인원 검증
    ├── lobby:{code}:participants Set에 userIdentifier 저장
    ├── lobby:{code}:order List에 userIdentifier 저장
    ├── lobby:{code}:user_session:{userIdentifier}에 현재 wsSessionId 저장
    ├── lobby:{code}:user_session_seq:{userIdentifier}에 현재 세션 sequence 저장
    ├── ws:connection:{wsSessionId} Hash에 userId/lobbyCode 저장
    └── 중복 구독 시 order List 중복 저장 방지
    ▼
SessionSubscribeEvent
    ▼
WebSocketEventListener.handleSubscribeEvent()
    │ 세션에 저장된 LOBBY_ENTER_RESULT 기반 후처리
    ├── ENTERED → ENTER 메시지 발행 + 로비 refresh
    ├── ALREADY_JOINED → ENTER 메시지 생략
    └── SESSION_REPLACED → 최신 세션 유지
    ▼
클라이언트
```

> 로비 입장 처리는 `/topic/lobby/{code}` 구독 시점에만 수행합니다.  
> `/topic/lobby/{code}/refresh` 구독은 로비 정보 갱신 신호 수신용이며, 입장 처리 대상이 아닙니다.

---

## WebSocket 로비 입장 실패 처리 정책

로비 입장은 REST와 WebSocket이 역할을 나누어 처리합니다.

```text
POST /api/lobbies/join
    - 초대 코드 형식 검증
    - 로비 존재 여부 사전 검증
    - WAITING 상태 사전 검증
    - 현재 인원 사전 검증
    - 응답 성공 시 로비 기본 정보 반환

SUBSCRIBE /topic/lobby/{code}
    - 실제 participants 등록
    - order List 등록
    - ws:connection 역추적 정보 저장
    - user_session / user_session_seq 저장
    - ENTER 메시지 및 refresh 후처리
```

`POST /api/lobbies/join`은 UX용 사전 검증입니다.  
실제 참여자 등록은 `SUBSCRIBE /topic/lobby/{code}` 시점에 `enter_lobby.lua`로 처리합니다.

따라서 REST join 성공 후에도 아래 상황에서는 WebSocket SUBSCRIBE가 실패할 수 있습니다.

| 상황 | Lua 반환값 | STOMP ERROR code | 처리 기준 |
| --- | --- | --- | --- |
| 로비가 삭제됨 | `LOBBY_NOT_FOUND` | `LOBBY_NOT_FOUND` | 로비 목록으로 복귀 |
| REST join 이후 정원이 참 | `FULL` | `LOBBY_FULL` | 로비 목록으로 복귀 |
| 방장이 게임을 시작함 | `LOBBY_NOT_WAITING` | `LOBBY_NOT_WAITING` | 로비 목록으로 복귀 |
| 강퇴된 유저 재입장 | `KICKED_USER` | `LOBBY_KICKED_USER` | 로비 목록으로 복귀 |
| 더 최신 세션이 존재함 | `STALE_SESSION:{wsSessionId}` | `LOBBY_STALE_SESSION` | 현재 세션 폐기 후 재연결 |
| 세션 sequence 누락/비정상 | `INVALID_SEQUENCE` | `LOBBY_INVALID_SEQUENCE` | 새로고침 후 재시도 |
| Redis 로비 정원 데이터 손상 | `INVALID_LOBBY_CAPACITY` | `LOBBY_INVALID_CAPACITY` | 로비 목록으로 복귀 |
| Lua null/unknown/Redis 일시 장애 | `null` 또는 unknown | `LOBBY_ENTER_TEMPORARILY_UNAVAILABLE` | 새로고침 후 재시도 |

### 실패 처리 흐름

```text
Client
    │ SUBSCRIBE /topic/lobby/{code}
    ▼
StompChannelInterceptor.preSend()
    │ enter_lobby.lua 실행
    │
    ├─ 성공
    │   ├─ ENTERED
    │   ├─ ALREADY_JOINED
    │   └─ SESSION_REPLACED:{previousWsSessionId}
    │
    └─ 실패
        ├─ Lua 반환값을 StompErrorCode로 변환
        ├─ ws:connection 보상 삭제
        └─ StompErrorException 발생
                ▼
CustomStompErrorHandler
    │ STOMP ERROR JSON payload 생성
    ▼
Client
    │ code/action/recoverable 기준으로 화면 처리
```

### STOMP ERROR payload 계약

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

FE는 `message` 문자열을 파싱하지 않습니다.  
반드시 `code`, `action`, `recoverable` 기준으로 화면 복귀, 재연결, 새로고침 재시도를 결정합니다.

### 성공 반환값 처리

| Lua 반환값 | 의미 | 후처리 |
| --- | --- | --- |
| `ENTERED` | 신규 입장 성공 | ENTER 메시지 발행, 로비 refresh |
| `ALREADY_JOINED` | 같은 세션의 중복 구독 | ENTER 메시지 생략 |
| `SESSION_REPLACED:{previousWsSessionId}` | 같은 userIdentifier의 새 세션이 기존 세션을 대체 | 최신 세션 유지, 기존 세션 disconnect 시 stale 처리 |

### 최신 세션 유지 정책

동일한 userIdentifier가 같은 로비에 여러 WebSocket 세션으로 진입할 경우, `CONNECT` 시 Redis `INCR`로 발급한 `sessionSequence`가 더 큰 세션을 최신 세션으로 봅니다.

```text
lobby:{code}:user_session:{userIdentifier}
lobby:{code}:user_session_seq:{userIdentifier}
```

이 정책으로 오래된 세션의 늦은 SUBSCRIBE 또는 늦은 DISCONNECT가 최신 세션 상태를 덮어쓰지 못하게 막습니다.

### 보상 삭제 정책

SUBSCRIBE 실패 시 현재 요청의 `ws:connection:{wsSessionId}`는 보상 삭제합니다.

```text
ws:connection:{wsSessionId}
```

다만 `participants`, `order`, `user_session`, `user_session_seq`는 Lua 성공 전 실패한 경우 생성되지 않았거나, stale 세션인 경우 최신 세션의 상태일 수 있으므로 Java에서 직접 삭제하지 않습니다.

이 원칙을 지켜야 정상 사용자의 최신 세션을 실수로 제거하지 않습니다.

---

## WebSocket 연결 해제 흐름

```text
클라이언트 연결 해제
    ▼
WebSocketEventListener.handleDisconnectEvent()
    │ 1. Redis ws:connection:{wsSessionId} 에서 lobbyCode 역추적
    │ 2. stale 세션 여부 확인
    │ 3. 최신 세션이면 PlayerLeaveEvent 발행
    │ 4. LEAVE 메시지 브로드캐스트
    │ 5. Redis 키 정리
    ▼
LobbyEventService.handlePlayerLeave(@EventListener)
    ▼
leave_lobby.lua
    ├── DESTROYED → 전역 로비 리스트 새로고침
    ├── DELEGATED → 로비 내부 새로고침
    └── LEFT      → 로비 내부 새로고침
```

### stale DISCONNECT 정책

동일 userIdentifier의 새 WebSocket 세션이 기존 세션을 대체한 후, 이전 세션의 DISCONNECT가 늦게 도착할 수 있습니다.

이 경우 이전 세션은 최신 유효 세션이 아니므로 실제 퇴장 처리를 수행하지 않습니다.

```text
if lobby:{code}:user_session:{userIdentifier} != disconnectedWsSessionId
    → stale 세션으로 판단
    → PlayerLeaveEvent 발행 생략
    → ws:connection:{oldWsSessionId}만 삭제
```

---

## 로비 강퇴 흐름

```text
방장 클라이언트
    │ STOMP SEND /app/lobby/{code}/kick
    ▼
LobbyEventController.kickLobbyPlayer()
    │ 세션에서 requesterIdentifier 추출
    ▼
LobbyEventService.kickLobbyPlayer()
    │ 1. 로비 코드 형식 검증
    │ 2. 요청자 인증 정보 검증
    │ 3. 강퇴 대상 식별자 검증
    ▼
kick_lobby.lua
    ├── 로비 존재 여부 확인
    ├── 방장 정보 존재 여부 확인
    ├── 요청자가 방장인지 검증
    ├── 자기 자신 강퇴 요청인지 검증
    ├── 강퇴 대상이 참여자인지 검증
    ├── participants/order에서 강퇴 대상 제거
    ├── kicked Set에 강퇴 대상 추가
    └── 대상의 현재 wsSessionId 반환
    ▼
LobbyEventService.handleKickSuccess()
    │ 1. 대상 ws:connection 키 삭제
    │ 2. KICK 메시지 로비 채팅 채널로 브로드캐스트
    │ 3. 로비 내부 refresh 브로드캐스트
```

강퇴된 사용자는 `lobby:{code}:kicked` Set에 저장되며, 같은 로비에 다시 `SUBSCRIBE /topic/lobby/{code}`를 시도하면 `enter_lobby.lua`에서 `KICKED_USER`로 차단됩니다.

---

## 로비 게임 시작 흐름

```text
방장 클라이언트
    │ REST POST /api/lobbies/{code}/start
    ▼
LobbyStartService
    │ 1. 인증 정보 확인
    │ 2. Redis 로비 존재 여부 확인
    │ 3. DB GAME_LOBBY 스냅샷 조회
    │ 4. 선택된 맵 존재/삭제 여부 확인
    │ 5. 맵 문제 수 >= roundCount 검증
    ▼
LobbyRepositoryImpl.executeStartLobbyProcess()
    │ 1. start_lobby.lua 실행 전 stale ready 데이터 정리
    │ 2. Redis Lua로 방장 권한, WAITING, mapId, participants, ready, session 상태 원자 검증
    ▼
start_lobby.lua
    ├── 로비 존재 여부 확인
    ├── 방장 정보 존재 여부 확인
    ├── 요청자가 방장인지 확인
    ├── 상태가 WAITING인지 확인
    ├── map_id 존재 여부 확인
    ├── 방장 제외 참여자 1명 이상인지 확인
    ├── 방장 제외 참여자의 활성 로비 세션 키 존재 여부 확인
    ├── 방장 제외 모든 참여자가 ready 상태인지 확인
    ├── lobby:{code}.status = PLAYING
    └── lobby:public에서 code 제거
    ▼
DB GAME_LOBBY.status = PLAYING 저장
    ▼
afterCommit
    ├── /topic/lobby/{code}/game → GAME_STARTED
    └── /topic/game/{code}/round → RoundStartDto
```

`GAME_STARTED` 이벤트는 DB 트랜잭션 커밋 이후에만 발행합니다.  
트랜잭션 동기화가 비활성인 경우 즉시 발행하지 않고 서버 오류로 처리하여 DB 커밋 전 이벤트 발행을 방지합니다.

---

## 게임 세션 및 라운드 시작 흐름

```text
클라이언트 (방장)
    │ POST /api/lobbies/{code}/start
    ▼
LobbyStartService
    │ Redis 로비 상태 검증 및 PLAYING 변경
    │ DB 트랜잭션 시작
    │ DB 로비 상태 PLAYING 변경
    │ GameSessionCreateService 호출
    ▼
GameSessionCreateService
    │ 1. MapItem 조회 및 셔플
    │ 2. DB GameSession, GameSessionPlayer 스냅샷 생성
    │ 3. init_game_session.lua 로 Redis 인게임 세션 데이터 초기화
    │ 4. 첫 라운드 정보(RoundStartDto) 반환
    ▼
DB Commit 완료
    ▼
GameRealtimeNotifier / LobbyRealtimeNotifier
    │ 1. /topic/lobby/{code}/game 으로 GAME_STARTED 브로드캐스트
    │ 2. /topic/game/{code}/round 로 첫 번째 라운드 RoundStartDto 브로드캐스트
    ▼
클라이언트
    │ 게임 화면 전환 및 YouTube IFrame API 재생
```

### 트랜잭션-웹소켓 동기화

DB 커밋 전에 웹소켓 이벤트가 발송되면 클라이언트가 아직 DB에 반영되지 않은 상태를 조회할 수 있습니다.  
이를 방지하기 위해 `afterCommit` 훅을 사용합니다.

### 보상 트랜잭션

게임 세션 생성이나 Redis 스크립트 실행 중 예외가 발생하면 DB 트랜잭션은 롤백되며, 로비 상태도 `WAITING`으로 복구합니다.  
Redis 롤백까지 실패한 경우 재처리 큐에 적재합니다.

---

## 🗄️ Redis 데이터 구조

| 키 패턴 | 타입 | 설명 |
| --- | --- | --- |
| `lobby:{code}` | Hash | 로비 메타 정보 |
| `lobby:{code}:participants` | Set | 로비 참여자 식별자 목록 |
| `lobby:{code}:ready` | Set | ready 상태인 로비 참여자 userIdentifier 목록 |
| `lobby:{code}:order` | List | 입장 순서 |
| `lobby:{code}:kicked` | Set | 강퇴된 사용자 식별자 목록 |
| `lobby:{code}:user_session:{userIdentifier}` | String | 로비 내 특정 사용자의 현재 유효 WebSocket 세션 ID |
| `lobby:{code}:user_session_seq:{userIdentifier}` | String | 로비 내 특정 사용자의 현재 유효 WebSocket 세션 sequence |
| `lobby:public` | Set | 공개 로비 코드 목록 |
| `lobby:public:latest` | ZSET | 공개 로비 최신순 정렬 인덱스 |
| `lobby:public:most_players` | ZSET | 공개 로비 현재 인원순 정렬 인덱스 |
| `lobby:public:most_available` | ZSET | 공개 로비 빈자리순 정렬 인덱스 |
| `lobby:start:reconciliation` | List | 게임 시작 Redis-DB 상태 불일치 재처리 큐 |
| `auth:guest:session:{token}` | Hash | 게스트 세션 정보 |
| `auth:refresh:{sessionId}` | String | Refresh Token |
| `user_status:{userIdentifier}` | String | 사용자 온라인 상태 |
| `user_status:{userIdentifier}:sessions` | Set | 사용자별 활성 WebSocket 세션 목록 |
| `ws:connection:{wsSessionId}` | Hash | WebSocket 세션 → userId, lobbyCode 매핑 |
| `map:public:list:v:{version}:p:{page}:s:{size}` | String | 공개 맵 목록 페이지 캐시 |
| `map:public:{mapId}` | String | 공개 맵 단건 캐시 |
| `map:public:list:version` | String | 공개 맵 목록 캐시 버전 |

> `host_user_id` 필드명은 `leave_lobby.lua`, `kick_lobby.lua`, `start_lobby.lua`와 맞춰 관리해야 합니다. 변경 시 Lua 스크립트도 함께 수정해야 합니다.

### `lobby:{code}` Hash 구조

| 필드 | 값 예시 | 설명 |
| --- | --- | --- |
| `code` | `ABC123` | 로비 초대 코드 |
| `host_user_id` | `9746cc76-f8f2-4859-b602-df6e1032fea4` | 현재 방장 userIdentifier |
| `title` | `K-POP 퀴즈방` | 로비 제목 |
| `max_players` | `8` | 최대 참여 인원 |
| `current_players` | `3` | 현재 참여 인원 캐시 |
| `is_private` | `false` | 비공개 여부 |
| `status` | `WAITING` | 로비 상태 |
| `map_id` | `1` | 선택된 맵 ID |
| `map_title` | `K-POP 2세대` | 선택된 맵 제목 |
| `map_category` | `kpop` | 선택된 맵 카테고리 원본 값 |
| `created_at_epoch_millis` | `1778990123456` | Redis TIME 기준 생성 시각 |

`map_id`, `map_title`, `map_category`는 로비 생성 시 `mapId`가 전달된 경우에만 저장합니다.  
맵이 선택되지 않은 로비는 위 세 필드를 저장하지 않습니다.

---

## 로비 ready 상태 저장 구조

```text
lobby:{code}:ready
```

| 키 패턴 | 타입 | 설명 |
| --- | --- | --- |
| `lobby:{code}:ready` | Set | ready 상태인 일반 참여자의 userIdentifier 목록 |

정책:

```text
1. 방장은 ready 대상에서 제외한다.
2. 일반 참여자만 ready 상태를 변경할 수 있다.
3. ready 변경은 PATCH /api/lobbies/{code}/ready에서 처리한다.
4. ready 변경 성공 시 /topic/lobby/{code}/refresh로 REFRESH_LOBBY_INFO를 브로드캐스트한다.
5. 퇴장/강퇴 시 ready Set에서 해당 userIdentifier를 제거한다.
6. 게임 시작 직전 ready Set에는 있지만 participants Set에는 없는 stale ready 데이터를 정리한다.
```

ready 상태는 `canStart` 계산과 `start_lobby.lua`의 최종 시작 조건 검증에 사용됩니다.

---

## ⚡ 핵심 설계 결정

### Java 21 Virtual Thread + Lettuce

가상 스레드 환경에서 Redis 클라이언트로 Jedis 대신 Lettuce를 사용합니다.

Jedis는 동기 블로킹 방식으로 가상 스레드를 캐리어 스레드에 핀닝할 수 있으나, Lettuce는 Netty 기반 비동기 드라이버로 핀닝 위험을 줄입니다.

### Redis Pub/Sub + WebSocket 브로드캐스트

멀티 인스턴스 환경에서는 특정 서버에 연결된 WebSocket 세션만 로컬 메모리에 존재합니다.

따라서 로비 채팅, ENTER, LEAVE, KICK 등 실시간 메시지는 Redis Pub/Sub으로 발행한 뒤 각 서버의 `RedisSubscriber`가 자기 서버에 연결된 클라이언트에게 브로드캐스트합니다.

```text
Server A
    │ RedisPublisher.publish()
    ▼
Redis Pub/Sub
    ├── Server A RedisSubscriber → local clients
    ├── Server B RedisSubscriber → local clients
    └── Server C RedisSubscriber → local clients
```

### Lua 스크립트 기반 원자 처리

로비 생성, 입장, 퇴장, 강퇴, 시작은 Redis Lua 스크립트로 원자 처리합니다.

Java에서 Redis 명령을 여러 번 나누어 실행하면 중간 실패 시 상태 불일치가 발생할 수 있습니다.

```text
participants에는 추가됐지만 order에는 없음
order에는 추가됐지만 ws:connection 매핑이 없음
ws:connection은 있지만 participants에는 없음
```

이를 방지하기 위해 상태 변경을 Lua 내부에서 하나의 원자 연산으로 처리합니다.

---

## 게임 시작 Redis-DB 상태 불일치 재처리

게임 시작은 Redis Lua가 먼저 원자 검증 및 Redis 상태 전환을 수행하고, 이후 DB `GAME_LOBBY` 상태를 `PLAYING`으로 동기화합니다.

이 구조에서는 아래와 같은 불일치가 발생할 수 있습니다.

```text
Redis: PLAYING
DB: WAITING
```

발생 가능한 상황:

```text
1. start_lobby.lua 성공
2. Redis lobby:{code}.status = PLAYING
3. DB GAME_LOBBY.status 변경 실패
```

보정 정책:

```text
1. DB 상태 변경 실패 시 Redis 상태를 WAITING으로 보상 롤백한다.
2. Redis 롤백 실패 시 lobby:start:reconciliation 큐에 payload를 적재한다.
3. LobbyStartReconciliationService가 주기적으로 큐를 소비한다.
4. 실패 시 exponential backoff 기반으로 재시도한다.
5. 최대 재시도 초과 시 [ALERT_REQUIRED] 로그를 남긴다.
```

재처리 payload 형식:

```text
lobbyCode|reason|attempt|nextRetryAtEpochMillis
```

재처리 사유:

| reason | 보정 정책 |
| --- | --- |
| `START_DB_SYNC_FAILED` | Redis 상태를 `WAITING`으로 롤백 |

---

## 운영 및 관측성 기준

### WebSocket Metric

`WebSocketMetric`은 활성 WebSocket 세션 수를 Prometheus에 노출합니다.

```text
CONNECT 성공 → increment
DISCONNECT 처리 → decrement
```

### Redis 장애 처리

Redis는 로비/세션/실시간 동기화의 핵심 저장소입니다.  
Redis 장애는 대부분 실시간 기능 장애로 이어지므로 다음 기준을 따릅니다.

| 영역 | 장애 처리 |
| --- | --- |
| 인증 refresh 저장소 | `503 Service Unavailable` |
| WebSocket CONNECT 온라인 상태 저장 실패 | STOMP ERROR `CONNECT_ONLINE_STATUS_FAILED` |
| 로비 입장 Lua 실패 | STOMP ERROR `LOBBY_ENTER_TEMPORARILY_UNAVAILABLE` |
| Pub/Sub 발행 실패 | 로컬 WebSocket fallback 또는 로그/관측성 처리 |
| 게임 시작 Redis-DB 불일치 | 보상 롤백 또는 reconciliation 큐 적재 |

### 로그 기준

로비 상태 정합성에 영향을 주는 오류는 단순 warn으로 묻지 않습니다.

| 상황 | 로그 기준 |
| --- | --- |
| Lua 알 수 없는 반환값 | `error` + monitoring required |
| Redis-DB 게임 시작 불일치 | `error` + reconciliation enqueue |
| 최대 재시도 초과 | `error` + alert required |
| stale 세션 disconnect | `info` |
| 중복 구독 | `info` |

---

## FE 연동 핵심 계약

### 로비 입장

FE는 REST join 성공만으로 사용자를 로비 참여자로 확정하면 안 됩니다.

```text
1. POST /api/lobbies/join
2. WebSocket CONNECT
3. SUBSCRIBE /topic/lobby/{code}
4. SUBSCRIBE 성공 후 GET /api/lobbies/{code}
```

### 로비 목록

`PLAYING` 로비는 목록에 노출될 수 있으나 입장 버튼은 비활성화해야 합니다.

### 로비 상세

`canStart`는 snapshot 값입니다.  
실제 시작 가능 여부는 `POST /api/lobbies/{code}/start`에서 최종 검증됩니다.

### STOMP ERROR

FE는 STOMP ERROR의 `message`를 파싱하지 않습니다.

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

분기는 다음 기준으로 처리합니다.

| 필드 | 사용 목적 |
| --- | --- |
| `code` | 구체적인 실패 원인 분기 |
| `action` | 화면 이동/재연결/재시도 정책 |
| `recoverable` | 같은 화면에서 재시도 가능한지 판단 |
| `message` | 사용자에게 표시할 문구 |
