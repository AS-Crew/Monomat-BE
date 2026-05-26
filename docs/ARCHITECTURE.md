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
│   │   │       ├── LobbyLuaScriptExecutor.java     # 로비 Lua 스크립트 실행 컴포넌트
│   │   │       ├── LobbyLuaResultMapper.java       # Lua 반환 문자열 → 도메인 결과 매핑
│   │   │       ├── LobbyRedisCommandRepository.java # Redis 로비 명령 처리
│   │   │       ├── LobbyRedisQueryRepository.java  # Redis 로비 조회 처리
│   │   │       └── LobbyStartReconciliationRepository.java # start 상태 불일치 재처리 큐 접근
│   │   └── service/
│   │       ├── LobbyCanStartPolicy.java            # 조회 시점 canStart 계산 정책
│   │       ├── LobbyCreateService.java             # 로비 생성 유스케이스
│   │       ├── LobbyJoinService.java               # 초대 코드 기반 입장 사전 검증 유스케이스
│   │       ├── LobbyQueryService.java              # 로비 목록/상세 조회 유스케이스
│   │       ├── LobbyReadyService.java              # ready 상태 변경 유스케이스
│   │       ├── LobbyStartService.java              # 게임 시작 유스케이스
│   │       ├── LobbyRealtimeNotifier.java          # 로비 refresh/STOMP 브로드캐스트
│   │       └── LobbyStartReconciliationService.java # 게임 시작 Redis-DB 상태 불일치 재처리 스케줄러
│   │
│   ├── map/                                        # 퀴즈 맵 도메인
│   │   ├── controller/
│   │   │   └── MapController.java                  # 맵 REST API
│   │   ├── dto/
│   │   │   └── ...                                 # 맵 생성/수정/조회 DTO
│   │   ├── entity/
│   │   │   ├── QuizMap.java                        # map 테이블 엔티티
│   │   │   └── MapCategory.java                    # 맵 카테고리 enum (kpop, jpop, pop)
│   │   ├── repository/
│   │   │   └── QuizMapJpaRepository.java           # 맵 JPA 리포지토리
│   │   └── service/
│   │       └── MapService.java                     # 맵 생성/수정/삭제/조회 비즈니스 로직
│   │
│   └── ...
│
└── global/                                         # 전역 인프라 레이어
    ├── config/                                     # 애플리케이션 설정
    │   ├── RedisConfig.java                        # Redis 연결, 직렬화, Pub/Sub 설정
    │   ├── RedisScriptConfig.java                  # create/enter/leave/kick/start Lua 스크립트 Bean 등록
    │   ├── SchedulingConfig.java                   # @Scheduled 기반 재처리 작업 활성화
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
    │   ├── error/
    │   │   ├── StompErrorAction.java               # FE 후속 동작 enum
    │   │   ├── StompErrorCode.java                 # STOMP ERROR 코드 계약 enum
    │   │   ├── StompErrorException.java            # STOMP 표준 에러 예외
    │   │   └── StompErrorPayload.java              # STOMP ERROR JSON payload
    │   └── event/
    │       └── PlayerLeaveEvent.java               # 플레이어 퇴장 Spring 이벤트 객체
    │
    └── security/
        ├── SecurityEndpoints.java                  # Security 경로 상수 중앙화
        └── jwt/
            ├── CustomPrincipal.java                # JWT 인증 주체 (userId + userIdentifier)
            ├── JwtAuthenticationFilter.java        # Bearer 토큰 검증 및 SecurityContext 저장
            ├── JwtClaims.java                      # JWT 클레임 키 상수 (발급/검증 공유)
            ├── JwtTokenProvider.java               # JWT Access/Refresh 토큰 발급
            └── TokenWithExpiry.java                # 토큰 + 만료시각 DTO
````

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

### 도메인 간 의존 규칙

동일한 `domain` 하위 도메인 간 참조는 비즈니스 흐름상 허용하지만, 직접 엔티티 결합은 최소화합니다.

예를 들어 로비 생성 시 맵을 연결할 때 `LobbyService`는 `QuizMapJpaRepository`로 맵 접근 권한을 검증합니다.
하지만 Redis 저장 계층인 `LobbyRepositoryImpl`은 `QuizMap` 엔티티에 직접 의존하지 않고, `LobbyMapMetadata`라는 최소 DTO만 전달받습니다.

```text
LobbyService
    ├── QuizMapJpaRepository 조회
    ├── 맵 존재/삭제/권한 검증
    └── LobbyMapMetadata 생성
            ↓
LobbyRepositoryImpl
    └── Redis 저장에 필요한 mapId, mapTitle, mapCategory만 사용
```

이 구조를 통해 맵 도메인의 영속성 모델 변경이 Redis 저장 계층으로 직접 전파되는 것을 방지합니다.

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
    │ - keyword
    │ - mapCategory
    │ - sort
    │ - page
    │ - size
    ▼
LobbySearchCondition
    │ 요청 조건 정규화
    │ - keyword trim + lower-case
    │ - mapCategory MapCategory.from()으로 검증
    │ - sort LobbySortType으로 검증
    │ - page 기본값/범위 검증
    │ - size 기본값/최대값 검증
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
    │ Redis Hash / Set / ZSET 조회
    ▼
Redis
```

### 책임 분리

| 계층                          | 책임                                                 |
| --------------------------- | -------------------------------------------------- |
| `LobbyQueryController`      | HTTP 요청 파라미터 수집                                    |
| `LobbySearchCondition`      | 요청값 정규화 및 검증                                       |
| `LobbyPageRequest`          | `page`, `size` 기본값/범위 검증                           |
| `LobbyQueryService`         | 로비 목록 노출 정책, 검색, 필터링, 정렬, 페이징, 인덱스 사용 여부 결정        |
| `LobbyRedisQueryRepository` | Redis 원본 공개 로비 데이터 조회, ZSET 인덱스 조회, stale index 정리 |

### 노출 정책

* 공개 로비 목록에는 `WAITING`, `PLAYING` 상태 로비가 노출됩니다.
* `FINISHED` 상태 로비는 목록에서 제외됩니다.
* `WAITING` 로비는 입장 가능한 로비입니다.
* `PLAYING` 로비는 진행 중 상태로 목록에는 노출되지만, 현재 입장은 허용하지 않습니다.
* 클라이언트는 `status=PLAYING` 로비를 “진행 중” 상태로 표시하고 입장 버튼을 비활성화해야 합니다.

### 페이징 정책

| 항목         | 정책                                 |
| ---------- | ---------------------------------- |
| `page`     | 0-based 페이지 번호                     |
| `page` 기본값 | `0`                                |
| `size` 기본값 | `20`                               |
| `size` 최소값 | `1`                                |
| `size` 최대값 | `100`                              |
| 응답 구조      | `items`, `page`, `size`, `hasNext` |

### 정렬 정책

| 정렬 기준            | 설명                                           | Redis 인덱스                     |
| ---------------- | -------------------------------------------- | ----------------------------- |
| `latest`         | `createdAtEpochMillis` 내림차순                  | `lobby:public:latest`         |
| `most_players`   | `currentPlayers` 내림차순, 동률이면 최신순              | `lobby:public:most_players`   |
| `most_available` | `maxPlayers - currentPlayers` 내림차순, 동률이면 최신순 | `lobby:public:most_available` |

`lobby:public`은 Redis Set이므로 삽입 순서가 보장되지 않습니다. 따라서 로비 생성 시 Redis Hash에 `created_at_epoch_millis`를 저장하고, 이 값을 기준으로 최신순 정렬을 수행합니다.

**Redis 정렬 인덱스**

| Redis Key                     | Type | Member     | Score                           | 갱신 시점                                |
| ----------------------------- | ---- | ---------- | ------------------------------- | ------------------------------------ |
| `lobby:public:latest`         | ZSET | lobby code | `created_at_epoch_millis`       | 공개 로비 생성 시 `ZADD`, 로비 폭파/삭제 시 `ZREM` |
| `lobby:public:most_players`   | ZSET | lobby code | `current_players`               | 공개 로비 생성/입장/퇴장/강퇴 시 갱신               |
| `lobby:public:most_available` | ZSET | lobby code | `max_players - current_players` | 공개 로비 생성/입장/퇴장/강퇴 시 갱신               |

**정렬 인덱스 사용 조건**

| 조건                                          | 조회 방식                                 |
| ------------------------------------------- | ------------------------------------- |
| `keyword` 없음 + `mapCategory` 없음 + 정렬 인덱스 존재 | Redis ZSET에서 page 범위의 lobbyCode만 조회   |
| `keyword` 있음                                | `lobby:public` 전체 조회 후 Java 필터/정렬/페이징 |
| `mapCategory` 있음                            | `lobby:public` 전체 조회 후 Java 필터/정렬/페이징 |
| 정렬 인덱스 없음                                   | `lobby:public` 전체 조회 후 Java 필터/정렬/페이징 |

필터가 있는 경우 ZSET page 범위를 먼저 자르면 전체 필터링 결과 기준의 page가 깨질 수 있습니다.
예를 들어 ZSET 상위 20개에는 `K-POP` 로비가 없지만 21번째 이후에 `K-POP` 로비가 있을 수 있습니다.
따라서 `keyword`, `mapCategory` 필터가 있는 경우는 안전하게 기존 전체 조회 경로로 폴백합니다.

### 생성 시각 기준

로비 생성 시각은 Java 애플리케이션 서버 시간이 아니라 Redis `TIME` 명령을 기준으로 생성합니다.

```lua
local redisTime = redis.call('TIME')
local createdAtEpochMillis = redisTime[1] * 1000 + math.floor(redisTime[2] / 1000)
```

멀티 인스턴스 운영 환경에서는 애플리케이션 서버 간 시간 편차가 발생할 수 있습니다.  
따라서 Redis 서버 기준 단일 시간원을 사용해 최신순 정렬 기준을 일관되게 유지합니다.

### 현재 인원 캐싱 정책

`current_players`는 `lobby:{code}:participants` Set과 중복 상태입니다.
따라서 Java 서비스에서 직접 증가/감소시키지 않고, participants 변경을 수행하는 Lua 스크립트 내부에서만 갱신합니다.

| 이벤트       | 처리                                                          |
| --------- | ----------------------------------------------------------- |
| 로비 생성     | `current_players = 0`                                       |
| 입장        | `SCARD lobby:{code}:participants` 결과를 `current_players`에 저장 |
| 퇴장        | 남은 participants 수를 `current_players`에 저장                    |
| 강퇴        | 남은 participants 수를 `current_players`에 저장                    |
| 마지막 인원 퇴장 | 로비 Hash 삭제. 별도 `current_players = 0` 저장 불필요                 |

조회 시에는 `lobby:{code}.current_players`를 우선 사용합니다.
다만 배포 전 생성된 기존 Redis 데이터에는 해당 필드가 없을 수 있으므로, 누락 시 `SCARD lobby:{code}:participants` 결과로 fallback합니다.

### Redis Hash 필드

`lobby:{code}` Hash에는 공개 로비 목록 조회 및 정렬을 위해 다음 필드가 포함됩니다.

| 필드                        | 설명                                           |
| ------------------------- | -------------------------------------------- |
| `created_at_epoch_millis` | Redis `TIME` 기준 로비 생성 시각. epoch milliseconds |
| `current_players`         | 현재 참여 인원 캐시. participants Set 변경 Lua에서만 갱신   |
| `map_category`            | 선택된 맵 카테고리. `K-POP`, `J-POP`, `POP`          |
| `status`                  | 로비 상태. 목록에서는 `WAITING`, `PLAYING`만 노출        |
| `is_private`              | 비공개 여부. 공개 로비 목록은 `false`만 노출                |
| `max_players`             | 최대 참여 인원                                     |

### Redis 정합성 방어

공개 로비 인덱스에는 남아 있지만 `lobby:{code}` Hash가 TTL 만료, 수동 삭제, 과거 버전 로직 등으로 사라질 수 있습니다.

이 경우 공개 로비 목록 조회 경로에서는 해당 code를 응답에서 제외만 합니다.  
조회 요청 중 즉시 인덱스를 삭제하지 않습니다.

인덱스 정리는 `LobbyPublicIndexCleanupScheduler`가 주기적으로 수행합니다.

```text
LobbyPublicIndexCleanupScheduler
    │ fixedDelay 기반 주기 실행
    ▼
lobby:public Set에서 일부 code 샘플 조회
    ▼
각 code에 대해 lobby:{code} Hash 존재 여부 확인
    ▼
Hash가 없으면 아래 공개 인덱스에서 제거
```
정리 대상은 다음과 같습니다.

```
lobby:public
lobby:public:latest
lobby:public:most_players
lobby:public:most_available
```

이 구조는 조회 경로와 정리 경로를 분리하기 위한 설계입니다.

| 구분                                                    | 책임                                      |
| ----------------------------------------------------- | --------------------------------------- |
| `LobbyRedisQueryRepository#getPublicLobbiesByCodes()` | stale code를 응답에서 제외                     |
| `LobbyPublicIndexCleanupScheduler`                    | stale code를 public Set/ZSET 인덱스에서 배치 정리 |
| `removePublicLobbyIndexes()`                          | 보정/정리 경로에서 특정 lobbyCode를 공개 인덱스에서 제거    |

정렬 인덱스 기반 조회 중 stale code가 섞이면 `LobbyQueryService`는 요청한 page size를 최대한 채우기 위해 추가 범위를 스캔합니다.
스캔 상한은 `targetItemCount * 5` 기준으로 제한하여 stale index가 누적된 상황에서도 무한 조회를 방지합니다.

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
    ├── lobby:{code}:user_session:{userIdentifier}에 현재 wsSessionId 저장
    ├── lobby:{code}:user_session_seq:{userIdentifier}에 현재 세션 sequence 저장
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

### 게임 세션 및 라운드 시작 흐름

```text
클라이언트 (방장)
    │ POST /api/lobbies/{code}/start
    ▼
LobbyStartService
    │ 1. start_lobby.lua 로 Redis 로비 상태 검증 및 PLAYING 변경
    │ 2. DB 트랜잭션 시작 (@Transactional)
    │ 3. DB 로비 상태 PLAYING 변경
    │ 4. GameSessionCreateService 호출
    ▼
GameSessionCreateService
    │ 1. MapItem 조회 및 셔플 (라운드 수만큼 선택)
    │ 2. DB GameSession, GameSessionPlayer 스냅샷 생성 (Bulk Insert)
    │ 3. init_game_session.lua 로 Redis 인게임 세션 데이터 초기화 (2시간 TTL 적용)
    │ 4. 첫 라운드 정보(RoundStartDto) 반환
    ▼
LobbyStartService
    │ TransactionSynchronizationManager.registerSynchronization (afterCommit)
    │ DB 트랜잭션 정상 커밋 대기
    ▼
DB Commit 완료
    │
    ▼
GameRealtimeNotifier / LobbyRealtimeNotifier
    │ 1. /topic/lobby/{code}/game 으로 GAME_STARTED 브로드캐스트
    │ 2. /topic/game/{code}/round 로 첫 번째 라운드 RoundStartDto 브로드캐스트
    ▼
클라이언트 (전체)
    │ 게임 화면 전환 및 유튜브 플레이어 재생
```

* **트랜잭션-웹소켓 동기화**: DB 커밋 전에 웹소켓 이벤트가 발송되어 클라이언트가 아직 DB에 반영되지 않은 상태를 조회하는 Race Condition을 방지하기 위해 `afterCommit` 훅을 사용합니다.
* **보상 트랜잭션(Rollback)**: 게임 세션 생성이나 Redis 스크립트 실행 중 예외가 발생하면 DB 트랜잭션이 롤백되며, 로비 상태도 `WAITING`으로 안전하게 복구됩니다.
```

> 로비 입장 처리는 `/topic/lobby/{code}` 구독 시점에만 수행합니다.
> `/topic/lobby/{code}/refresh` 구독은 로비 정보 갱신 신호 수신용이며, 입장 처리 대상이 아닙니다.

### WebSocket 로비 입장 실패 처리 정책

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
| 상황                           | Lua 반환값                       | STOMP ERROR code                      | 처리 기준          |
| ---------------------------- | ----------------------------- | ------------------------------------- | -------------- |
| 로비가 삭제됨                      | `LOBBY_NOT_FOUND`             | `LOBBY_NOT_FOUND`                     | 로비 목록으로 복귀     |
| REST join 이후 정원이 참           | `FULL`                        | `LOBBY_FULL`                          | 로비 목록으로 복귀     |
| 방장이 게임을 시작함                  | `LOBBY_NOT_WAITING`           | `LOBBY_NOT_WAITING`                   | 로비 목록으로 복귀     |
| 강퇴된 유저 재입장                   | `KICKED_USER`                 | `LOBBY_KICKED_USER`                   | 로비 목록으로 복귀     |
| 더 최신 세션이 존재함                 | `STALE_SESSION:{wsSessionId}` | `LOBBY_STALE_SESSION`                 | 현재 세션 폐기 후 재연결 |
| 세션 sequence 누락/비정상           | `INVALID_SEQUENCE`            | `LOBBY_INVALID_SEQUENCE`              | 새로고침 후 재시도     |
| Redis 로비 정원 데이터 손상           | `INVALID_LOBBY_CAPACITY`      | `LOBBY_INVALID_CAPACITY`              | 로비 목록으로 복귀     |
| Lua null/unknown/Redis 일시 장애 | `null` 또는 unknown             | `LOBBY_ENTER_TEMPORARILY_UNAVAILABLE` | 새로고침 후 재시도     |

#### 실패 처리 흐름
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

#### STOMP ERROR payload 계약
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

#### 성공 반환값 처리
| Lua 반환값                                  | 의미                                 | 후처리                                   |
| ---------------------------------------- | ---------------------------------- | ------------------------------------- |
| `ENTERED`                                | 신규 입장 성공                           | ENTER 메시지 발행, 로비 refresh              |
| `ALREADY_JOINED`                         | 같은 세션의 중복 구독                       | ENTER 메시지 생략                          |
| `SESSION_REPLACED:{previousWsSessionId}` | 같은 userIdentifier의 새 세션이 기존 세션을 대체 | 최신 세션 유지, 기존 세션 disconnect 시 stale 처리 |

#### 최신 세션 유지 정책
동일한 userIdentifier가 같은 로비에 여러 WebSocket 세션으로 진입할 경우, `CONNECT` 시 Redis INCR로 발급한 `sessionSequence`가 더 큰 세션을 최신 세션으로 봅니다.
```text
lobby:{code}:user_session:{userIdentifier}
lobby:{code}:user_session_seq:{userIdentifier}
```
이 정책으로 오래된 세션의 늦은 SUBSCRIBE 또는 늦은 DISCONNECT가 최신 세션 상태를 덮어쓰지 못하게 막습니다.

#### 보상 삭제 정책
SUBSCRIBE 실패 시 현재 요청의 `ws:connection:{wsSessionId}`는 보상 삭제합니다.
```
ws:connection:{wsSessionId}
```
다만 `participants`, `order`, `user_session`, `user_session_seq`는 Lua 성공 전 실패한 경우 생성되지 않았거나, stale 세션인 경우 최신 세션의 상태일 수 있으므로 Java에서 직접 삭제하지 않습니다.

이 원칙을 지켜야 정상 사용자의 최신 세션을 실수로 제거하지 않습니다.


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

### 로비 강퇴 흐름

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
    │ Redis Lua 스크립트로 강퇴 상태를 원자적으로 변경
    │
    ├── 방장 권한 검증
    ├── 자기 자신 강퇴 방지
    ├── 참여자 여부 검증
    ├── participants/order에서 대상 제거
    ├── kicked Set에 대상 추가
    └── 대상의 현재 wsSessionId 반환
    │
    ▼
LobbyEventService.handleKickSuccess()
    │ 1. 대상 ws:connection 키 삭제
    │ 2. KICK 메시지 로비 채팅 채널로 브로드캐스트
    │ 3. 로비 내부 refresh 브로드캐스트
```

### 로비 게임 시작 흐름

```text
방장 클라이언트
    │ REST POST /api/lobbies/{code}/start
    ▼
LobbyController.startLobbyGame()
    │ @AuthenticationPrincipal CustomPrincipal 전달
    ▼
LobbyService.startLobbyGame()
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
LobbyService
    │ DB GAME_LOBBY.status = PLAYING 저장
    │ afterCommit에서 GAME_STARTED / REFRESH_LOBBY_INFO 브로드캐스트 등록
    ▼
클라이언트
    │ /topic/lobby/{code}/game에서 GAME_STARTED 수신
    ▼
인게임 화면 전환
```

`GAME_STARTED` 이벤트는 DB 트랜잭션 커밋 이후에만 발행합니다.
트랜잭션 동기화가 비활성인 경우 즉시 발행하지 않고 서버 오류로 처리하여 DB 커밋 전 이벤트 발행을 방지합니다.

---

## 🗄️ Redis 데이터 구조

| 키 패턴                                             | 타입     | 설명                                                                        |
| ------------------------------------------------ | ------ | ------------------------------------------------------------------------- |
| `lobby:{code}`                                   | Hash   | 로비 메타 정보 (title, status, host_user_id, map_id, map_title, map_category 등) |
| `lobby:{code}:participants`                      | Set    | 로비 참여자 식별자 목록                                                             |
| `lobby:{code}:ready`                             | Set    | ready 상태인 로비 참여자 userIdentifier 목록                                        |
| `lobby:{code}:order`                             | List   | 입장 순서 (방장 위임 시 LINDEX 0 사용)                                               |
| `lobby:{code}:kicked`                            | Set    | 강퇴된 사용자 식별자 목록                                                            |
| `lobby:{code}:user_session:{userIdentifier}`     | String | 로비 내 특정 사용자의 현재 유효 WebSocket 세션 ID                                        |
| `lobby:{code}:user_session_seq:{userIdentifier}` | String | 로비 내 특정 사용자의 현재 유효 WebSocket 세션 sequence                                  |
| `lobby:public`                                   | Set    | 공개 로비 코드 목록 (고속 필터링용)                                                     |
| `lobby:start:reconciliation`                     | List   | 게임 시작 Redis-DB 상태 불일치 재처리 큐                                               |
| `metric:lobby:start:reconciliation:enqueued`     | String | 게임 시작 상태 재처리 큐 적재 횟수                                                      |
| `metric:lobby:start:reconciliation:success`      | String | 게임 시작 상태 재처리 성공 횟수                                                        |
| `metric:lobby:start:reconciliation:failed`       | String | 게임 시작 상태 재처리 실패 횟수                                                        |
| `metric:lobby:start:unknown-result`              | String | `start_lobby.lua` 알 수 없는 반환값 발생 횟수                                        |
| `metric:lobby:ready:stale-cleanup`               | String | 게임 시작 전 stale ready 데이터 정리 횟수                                             |
| `metric:lobby:ready:consistency-failure`         | String | 게임 시작 실패 시 ready/participants/session 정합성 진단 발생 횟수                        |
| `auth:guest:session:{token}`                     | Hash   | 게스트 세션 정보 (userId, username, userType, TTL 30일)                           |
| `auth:refresh:{sessionId}`                       | String | Refresh Token (TTL 30일)                                                   |
| `user_status:{userIdentifier}`                   | String | 사용자 온라인 상태 (`ONLINE`, TTL 2시간)                                            |
| `user_status:{userIdentifier}:sessions`          | Set    | 사용자별 활성 WebSocket 세션 목록                                                   |
| `ws:connection:{wsSessionId}`                    | Hash   | WebSocket 세션 → userId, lobbyCode 매핑                                       |
| `map:public:list:v:{version}:p:{page}:s:{size}`  | String | 공개 맵 목록 페이지 캐시                                                            |
| `map:public:{mapId}`                             | String | 공개 맵 단건 캐시                                                                |
| `map:public:list:version`                        | String | 공개 맵 목록 캐시 버전                                                             |

> ⚠️ `host_user_id` 필드명은 `leave_lobby.lua`, `kick_lobby.lua`, `start_lobby.lua`와 맞춰 관리해야 합니다. 변경 시 Lua 스크립트도 함께 수정해야 합니다.

### `lobby:{code}` Hash 구조

로비 메타 정보는 Redis Hash로 저장합니다.

| 필드             | 값 예시                                   | 설명                                                          |
| -------------- | -------------------------------------- | ----------------------------------------------------------- |
| `code`         | `ABC123`                               | 로비 초대 코드                                                    |
| `host_user_id` | `9746cc76-f8f2-4859-b602-df6e1032fea4` | 현재 방장 userIdentifier                                        |
| `title`        | `K-POP 퀴즈방`                            | 로비 제목                                                       |
| `max_players`  | `8`                                    | 최대 참여 인원                                                    |
| `is_private`   | `false`                                | 비공개 여부. `true` = 비공개                                        |
| `status`       | `WAITING`                              | 로비 상태                                                       |
| `map_id`       | `1`                                    | 선택된 맵 ID                                                    |
| `map_title`    | `K-POP 2세대`                            | 선택된 맵 제목                                                    |
| `map_category` | `jpop`                                 | 선택된 맵 카테고리 원본 값. HTTP 응답 시 `K-POP`, `J-POP`, `POP` 형식으로 정규화 |

`map_id`, `map_title`, `map_category`는 로비 생성 시 `mapId`가 전달된 경우에만 저장합니다.
맵이 선택되지 않은 로비는 위 세 필드를 저장하지 않습니다.

Redis에 `"null"` 문자열을 저장하지 않고, 응답 DTO에서만 `null`로 표현합니다.

### Redis Hash 필드 상수 (`RedisKeys.FIELD_*`)

로비 Hash(`lobby:{code}`) 내부 필드명은 `RedisKeys` 클래스의 `FIELD_*` 상수로 중앙 관리합니다.
문자열 리터럴 직접 사용 시 오타가 런타임에서야 발견되는 문제를 컴파일 타임에 방지합니다.

로비 맵 연결 기능에서 사용하는 주요 필드는 다음과 같습니다.

| 필드 상수                | Redis Hash 필드명 | 설명                |
| -------------------- | -------------- | ----------------- |
| `FIELD_MAP_ID`       | `map_id`       | 선택된 맵 ID          |
| `FIELD_MAP_TITLE`    | `map_title`    | 선택된 맵 제목          |
| `FIELD_MAP_CATEGORY` | `map_category` | 선택된 맵 카테고리        |
| `FIELD_STATUS`       | `status`       | 로비 상태             |
| `FIELD_HOST_USER_ID` | `host_user_id` | 방장 userIdentifier |

맵 미선택 로비에서는 맵 관련 필드를 저장하지 않습니다.

### WebSocket 로비 입장 저장 구조

로비 입장 상태는 `enter_lobby.lua`에서 원자적으로 저장합니다.

| 키 패턴                                             | 타입     | 저장 시점                      | 설명                                                      |
| ------------------------------------------------ | ------ | -------------------------- | ------------------------------------------------------- |
| `lobby:{code}:participants`                      | Set    | `/topic/lobby/{code}` 구독 시 | 현재 로비에 참여 중인 userIdentifier 목록                          |
| `lobby:{code}:order`                             | List   | `/topic/lobby/{code}` 구독 시 | 입장 순서. 방장 퇴장 시 다음 방장 위임 기준                              |
| `lobby:{code}:user_session:{userIdentifier}`     | String | `/topic/lobby/{code}` 구독 시 | 해당 유저의 현재 유효 wsSessionId                                |
| `lobby:{code}:user_session_seq:{userIdentifier}` | String | `/topic/lobby/{code}` 구독 시 | 해당 유저의 현재 유효 세션 sequence                                |
| `ws:connection:{wsSessionId}`                    | Hash   | `/topic/lobby/{code}` 구독 시 | WebSocket 세션 ID로 userIdentifier와 lobbyCode를 역추적하기 위한 매핑 |

`ws:connection:{wsSessionId}` Hash 필드:

| 필드          | 값                | 설명                             |
| ----------- | ---------------- | ------------------------------ |
| `userId`    | `userIdentifier` | Redis/WebSocket에서 사용하는 사용자 식별자 |
| `lobbyCode` | `{code}`         | 현재 WebSocket 세션이 참여 중인 로비 코드   |

정상 저장 예시:

```redis
SMEMBERS lobby:R2VJW5:participants
1) "9746cc76-f8f2-4859-b602-df6e1032fea4"

LRANGE lobby:R2VJW5:order 0 -1
1) "9746cc76-f8f2-4859-b602-df6e1032fea4"

GET lobby:R2VJW5:user_session:9746cc76-f8f2-4859-b602-df6e1032fea4
"mhrg4it0"

GET lobby:R2VJW5:user_session_seq:9746cc76-f8f2-4859-b602-df6e1032fea4
"1"

HGETALL ws:connection:mhrg4it0
1) "userId"
2) "9746cc76-f8f2-4859-b602-df6e1032fea4"
3) "lobbyCode"
4) "R2VJW5"
```

중복 구독 시 `participants`는 Set 구조로 중복 저장되지 않으며, `order` List도 `enter_lobby.lua`에서 신규 입장자일 때만 `RPUSH`하여 중복 저장을 방지합니다.

### 로비 ready 상태 저장 구조

로비 참여자의 ready 상태는 Redis Set으로 관리합니다.

```text
lobby:{code}:ready
```

| 키 패턴                 | 타입  | 설명                                  |
| -------------------- | --- | ----------------------------------- |
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

가상 스레드 환경에서 Redis 클라이언트로 Jedis 대신 **Lettuce**를 사용합니다.
Jedis는 동기 블로킹 방식으로 가상 스레드를 캐리어 스레드에 핀닝(Pinning)할 수 있으나,
Lettuce는 Netty 기반 비동기 드라이버로 핀닝 없이 동작합니다.

### 로비 생성 시 맵 연결 정책

로비 생성 시 `mapId`는 선택 사항입니다.

```text
로비 생성: mapId 선택 사항
게임 시작: mapId 필수
```

이 구조를 선택한 이유는 로비가 게임 시작 전 대기실 역할을 하기 때문입니다.
방장은 먼저 로비를 만들고 참여자를 모은 뒤, 로비 내부에서 맵을 선택하거나 변경할 수 있습니다.

`mapId`가 전달된 경우 `LobbyService`에서 다음 순서로 검증합니다.

```text
1. 맵 존재 여부 확인
2. 삭제된 맵 여부 확인
3. 공개 맵이면 허용
4. 비공개 맵이면 소유자만 허용
```

검증 결과에 따른 HTTP 상태 코드는 다음과 같습니다.

| 상황                  | 상태 코드           |
| ------------------- | --------------- |
| 존재하지 않는 맵           | `404 Not Found` |
| 삭제된 맵               | `409 Conflict`  |
| 타인 소유 비공개 맵         | `403 Forbidden` |
| 공개 맵 또는 본인 소유 비공개 맵 | 로비 생성 허용        |

검증이 끝난 맵 정보는 `LobbyMapMetadata`로 변환하여 Redis 저장 계층에 전달합니다.
이를 통해 `LobbyRepositoryImpl`이 `QuizMap` 엔티티에 직접 의존하지 않도록 분리합니다.

### Lua 스크립트 기반 원자적 로비 생성

`create_lobby.lua`는 초대 코드 선점과 로비 Hash 저장을 원자적으로 처리합니다.

처리 대상:

```text
lobby:code:lock:{code}
lobby:{code}
lobby:public
```

로비 생성 시 아래 정보를 저장합니다.

```text
code
host_user_id
title
max_players
is_private
status
```

이슈 #62 이후 선택된 맵이 있는 경우 아래 필드도 같은 Lua 스크립트 안에서 함께 저장합니다.

```text
map_id
map_title
map_category
```

맵 미선택 로비의 경우 Java에서 빈 문자열을 전달하고, Lua 스크립트는 `mapId`가 빈 문자열이면 맵 관련 필드를 저장하지 않습니다.
이 방식으로 Redis Hash에 `"null"` 문자열이 저장되는 것을 방지합니다.

### Lua 스크립트 기반 원자적 입장 처리

`enter_lobby.lua`는 WebSocket 로비 입장 시 필요한 Redis 상태 변경을 단일 원자 연산으로 처리합니다.

처리 대상:

```text
lobby:{code}:participants
lobby:{code}:order
lobby:{code}:user_session:{userIdentifier}
lobby:{code}:user_session_seq:{userIdentifier}
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
5. 로비 내 유저별 현재 유효 wsSessionId 저장
6. 로비 내 유저별 현재 유효 세션 sequence 저장
7. 관련 키 TTL 설정
```

반환값은 다음과 같습니다.

| 반환값                         | 의미                                 |
| --------------------------- | ---------------------------------- |
| `ENTERED:{sequence}`        | 신규 입장 처리 완료                        |
| `ALREADY_JOINED:{sequence}` | 이미 참여 중인 유저의 중복 구독. order 중복 저장 없음 |
| `LOBBY_NOT_FOUND`           | 존재하지 않는 로비 코드로 구독 요청               |

입장 처리는 반드시 `/topic/lobby/{code}` 구독 시점에만 수행합니다.
`/topic/lobby/{code}/refresh`는 입장 처리를 트리거하지 않습니다.

### Lua 스크립트 기반 원자적 퇴장 처리

`leave_lobby.lua`는 참여자 제거 → 방장 위임 → 로비 폭파를 단일 트랜잭션으로 처리합니다.
Redis는 싱글 스레드로 Lua 스크립트를 실행하므로 다수의 동시 퇴장 시에도 Race Condition이 발생하지 않습니다.

반환값은 `DESTROYED`, `DELEGATED:{newHostId}`, `LEFT` 세 가지이며,
`LobbyRepositoryImpl`에서 `LeaveLobbyResult` sealed interface로 파싱하여
서비스 레이어가 Redis 반환 포맷을 알 필요 없도록 캡슐화합니다.

### Lua 스크립트 기반 원자적 강퇴 처리

`kick_lobby.lua`는 방장의 강퇴 요청을 원자적으로 처리합니다.

처리 대상:

```text
lobby:{code}
lobby:{code}:participants
lobby:{code}:order
lobby:{code}:kicked
lobby:{code}:user_session:{targetUserIdentifier}
lobby:{code}:user_session_seq:{targetUserIdentifier}
```

처리 순서:

```text
1. 로비 존재 여부 확인
2. 방장 정보 존재 여부 확인
3. 요청자가 방장인지 검증
4. 자기 자신 강퇴 요청인지 검증
5. 강퇴 대상이 참여자인지 검증
6. participants/order에서 강퇴 대상 제거
7. kicked Set에 강퇴 대상 추가
8. 강퇴 대상의 현재 wsSessionId 반환
9. 강퇴 대상의 user_session / user_session_seq 키 삭제
```

반환값은 다음과 같습니다.

| 반환값                          | 의미                |
| ---------------------------- | ----------------- |
| `KICKED:{targetWsSessionId}` | 강퇴 성공             |
| `LOBBY_NOT_FOUND`            | 존재하지 않는 로비        |
| `HOST_NOT_FOUND`             | 로비 방장 정보 없음       |
| `FORBIDDEN`                  | 요청자가 방장이 아님       |
| `CANNOT_KICK_SELF`           | 자기 자신 강퇴 시도       |
| `TARGET_NOT_PARTICIPANT`     | 강퇴 대상이 로비 참여자가 아님 |

강퇴 성공 후 `LobbyEventService`는 `KICK` 메시지를 로비 채팅 채널로 브로드캐스트하고, 로비 내부 refresh 이벤트를 발행합니다.

### Lua 스크립트 기반 원자적 게임 시작 처리

`start_lobby.lua`는 게임 시작 조건 중 Redis 기준으로 원자 검증 가능한 조건을 처리합니다.

처리 대상:

```text
lobby:{code}
lobby:{code}:participants
lobby:{code}:ready
lobby:public
```

검증 조건:

```text
1. 로비가 존재해야 한다.
2. 방장 정보가 존재해야 한다.
3. 요청자가 방장이어야 한다.
4. 로비 상태가 WAITING이어야 한다.
5. map_id가 존재해야 한다.
6. 방장 제외 참여자가 1명 이상이어야 한다.
7. 방장 제외 참여자의 활성 로비 세션 키가 존재해야 한다.
8. 방장 제외 모든 참여자가 ready 상태여야 한다.
```

반환값 계약은 `StartLobbyLuaResultCode` enum과 `StartLobbyLuaResultCodeContractTest`로 고정합니다.

| 반환값                                  | 의미                                             |
| ------------------------------------ | ---------------------------------------------- |
| `STARTED`                            | 게임 시작 성공                                       |
| `LOBBY_NOT_FOUND`                    | 로비 없음                                          |
| `HOST_NOT_FOUND`                     | 방장 정보 없음                                       |
| `FORBIDDEN`                          | 요청자가 방장이 아님                                    |
| `LOBBY_NOT_WAITING`                  | 로비 상태가 WAITING이 아님                             |
| `MAP_NOT_SELECTED`                   | 맵이 선택되지 않음                                     |
| `NO_PLAYER`                          | 방장 제외 참여자 없음                                   |
| `NOT_READY:{userIdentifier}`         | 준비하지 않은 참여자 존재                                 |
| `STALE_PARTICIPANT:{userIdentifier}` | participants에는 있지만 활성 로비 세션 키가 없는 stale 참여자 존재 |

알 수 없는 반환값 또는 `null` 반환값은 generic error로 처리하고, `[MONITORING_REQUIRED]` 로그와 metric을 남깁니다.

### 게임 시작 Redis-DB 상태 불일치 재처리

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

| reason                        | 보정 정책                       |
| ----------------------------- | --------------------------- |
| `START_DB_SYNC_FAILED`        | Redis 상태를 `WAITING`으로 롤백    |
| `START_DB_SNAPSHOT_NOT_FOUND` | DB 스냅샷이 없으므로 Redis 잔존 로비 삭제 |

운영 모니터링 대상:

| 항목                                           | 설명               |
| -------------------------------------------- | ---------------- |
| `lobby:start:reconciliation`                 | 재처리 큐 길이         |
| `[ALERT_REQUIRED]`                           | 즉시 운영 확인이 필요한 로그 |
| `[MONITORING_REQUIRED]`                      | 모니터링 연계가 필요한 로그  |
| `metric:lobby:start:reconciliation:enqueued` | 재처리 큐 적재 횟수      |
| `metric:lobby:start:reconciliation:success`  | 재처리 성공 횟수        |
| `metric:lobby:start:reconciliation:failed`   | 재처리 실패 횟수        |

### ready / participants 정합성 보정 정책

게임 시작 조건은 `participants`, `ready`, `user_session` 키의 정합성에 영향을 받습니다.

정책:

```text
1. participants Set을 현재 로비 참여자의 source of truth로 사용한다.
2. ready Set에는 있지만 participants Set에는 없는 값은 stale ready 데이터로 보고 게임 시작 직전에 제거한다.
3. participants Set에는 있지만 user_session 키가 없는 값은 stale participant로 보고 게임 시작을 거부한다.
4. participants Set에 있고 user_session 키도 있지만 ready가 아닌 값은 실제 미준비 유저로 보고 NOT_READY 처리한다.
5. NOT_READY 또는 STALE_PARTICIPANT 발생 시 ready/participants/session 정합성 진단 로그를 남긴다.
```

이 정책은 정상 참여자를 임의 삭제하지 않기 위한 선택입니다.
participants에 남아 있는 유저가 실제 미준비 유저인지 stale 유저인지 Lua 내부에서 완전히 판단하기 어렵기 때문에, user_session 키가 없는 경우만 `STALE_PARTICIPANT`로 분리합니다.

### Redis 직렬화 JsonMapper와 HTTP 응답 JsonMapper 분리

RedisTemplate은 타입 정보가 필요한 `GenericJacksonJsonRedisSerializer`를 사용합니다.
기존처럼 타입 정보가 포함된 `JsonMapper`를 Spring Bean으로 노출하면 Spring MVC HTTP 응답 직렬화에 해당 Mapper가 개입할 수 있습니다.

그 결과 REST 응답이 아래처럼 Jackson 타입 정보를 포함하는 형태로 내려갈 수 있습니다.

```json
[
  "java.util.ArrayList",
  [
    [
      "io.github.ascrew.monomatbe.domain.lobby.dto.LobbyRedisDto",
      {
        "code": "ABC123",
        "title": "K-POP 퀴즈방"
      }
    ]
  ]
]
```

이를 방지하기 위해 Redis 전용 JsonMapper는 `RedisConfig#createRedisJsonMapper()` private 메서드 내부에서만 생성합니다.

정책:

```text
1. RedisTemplate 값 직렬화:
   - 타입 정보 포함 JsonMapper 사용
   - Spring Bean으로 노출하지 않음

2. Pub/Sub 메시지 직렬화:
   - 타입 정보 없는 pubSubJsonMapper Bean 사용

3. HTTP 응답 직렬화:
   - Redis 직렬화용 JsonMapper가 개입하지 않음
   - 순수 DTO JSON 배열로 반환
```

기존 `jsonMapper` Bean 직접 주입 사용처는 없어야 하며, JsonMapper가 필요한 신규 코드에서는 목적에 맞는 Bean을 명시적으로 주입해야 합니다.

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
입장(Lua) → 퇴장(Lua) → 강퇴(Lua) → ready/start 검증 모두 동일한 키를 사용합니다.

### SETNX 기반 초대 코드 중복 방지

`LobbyRepositoryImpl`은 6자리 초대 코드를 생성할 때
`lobby:code:lock:{code}` 키를 Redis SET NX 명령으로 원자적으로 선점합니다.
선점 성공 시 해당 코드를 사용하고, 실패 시 최대 `LobbyDefaults.INVITE_CODE_MAX_RETRY`까지 재시도합니다.

락 TTL은 `LobbyDefaults.INVITE_CODE_LOCK_TTL`을 따르며, 로비 생성 실패 시 자동 해제되어 코드 공간이 반환됩니다.

### JWT 인증 구조

`JwtAuthenticationFilter`가 모든 요청의 Authorization 헤더에서 Bearer 토큰을 파싱합니다.
파싱 성공 시 `CustomPrincipal(userId, userIdentifier, userType)`을 생성하여 SecurityContext에 저장합니다.
컨트롤러에서는 `@AuthenticationPrincipal CustomPrincipal`로 주입받아 사용합니다.

[식별자 분리]

* `userId` (Long) : DB users.id FK 참조용
* `userIdentifier` (UUID String) : Redis/WebSocket 식별자

---

## 🌐 STOMP 채널 구조

| 방향         | 경로                            | 설명                          |
| ---------- | ----------------------------- | --------------------------- |
| 클라이언트 → 서버 | `/app/chat/global`            | 전체 채팅 메시지 송신                |
| 클라이언트 → 서버 | `/app/chat/lobby/{code}`      | 로비 채팅 메시지 송신                |
| 클라이언트 → 서버 | `/app/lobby/create`           | 로비 생성 이벤트 송신                |
| 클라이언트 → 서버 | `/app/lobby/{code}/update`    | 로비 정보 변경 이벤트 송신             |
| 클라이언트 → 서버 | `/app/lobby/{code}/kick`      | 방장의 로비 유저 강퇴 요청             |
| 서버 → 클라이언트 | `/topic/chat/global`          | 전체 채팅 메시지 수신                |
| 서버 → 클라이언트 | `/topic/lobby/{code}`         | 로비 채팅 메시지 수신 및 로비 입장 처리 트리거 |
| 서버 → 클라이언트 | `/topic/lobby/{code}/refresh` | 로비 내부 정보 새로고침 신호            |
| 서버 → 클라이언트 | `/topic/lobby/{code}/game`    | 게임 시작 이벤트 (`GAME_STARTED`)  |
| 서버 → 클라이언트 | `/topic/lobby/refresh`        | 전역 로비 리스트 새로고침 신호           |

> `/topic/lobby/{code}`는 로비 채팅 메시지 수신 채널이면서 WebSocket 입장 처리 트리거입니다.
> `/topic/lobby/{code}/refresh`는 로비 정보 새로고침 신호 수신 전용이며, 입장 처리 트리거가 아닙니다.

---

## 🔐 인증 구조

### 정식 회원 (Member)

로그인 성공 시 JWT를 발급하고 `user_sessions`에 서버 세션 추적 정보를 저장합니다.
맵 생성, 로비 호스팅 등 전체 기능에 접근할 수 있습니다.

### 게스트 (Guest)

닉네임 입력만으로 진입하며, 백엔드에서 UUID를 발급하여 `localStorage`에 저장합니다.
재방문 시 UUID로 자동 복원됩니다. 퀴즈 플레이 참여만 가능합니다.

### WebSocket 인증 흐름

```text
STOMP CONNECT 헤더: { userIdentifier: "UUID" }
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

---

## 🧩 로비 생성 시 맵 연결 흐름

```text
클라이언트
    │ POST /api/lobbies
    │ Body: { title, maxPlayers, isPrivate, mapId?, roundCount?, timeLimitSeconds? }
    ▼
LobbyController.createLobby()
    │ @AuthenticationPrincipal CustomPrincipal 주입
    ▼
LobbyService.createLobby()
    │ 1. principal 검증
    │ 2. host User 조회
    │ 3. mapId가 있으면 QuizMap 조회 및 권한 검증
    │ 4. LobbyMapMetadata 생성
    ▼
LobbyRepository.saveToRedis()
    │ create_lobby.lua 실행
    │ 초대 코드 선점 + Redis Hash 저장
    ▼
GameLobbyJpaRepository.save()
    │ GAME_LOBBY 스냅샷 저장
    ▼
CreateLobbyResponse 반환
```

### 맵 미선택 로비

`mapId`가 없으면 맵 검증을 수행하지 않습니다.

```json
{
  "mapId": null,
  "mapTitle": null,
  "mapCategory": null
}
```

Redis Hash에는 `map_id`, `map_title`, `map_category` 필드를 저장하지 않습니다.

### 맵 선택 로비

`mapId`가 있으면 검증 후 Redis Hash와 DB 스냅샷에 반영합니다.

```json
{
  "mapId": 1,
  "mapTitle": "K-POP 2세대",
  "mapCategory": "K-POP"
}
```

Redis Hash에는 아래 필드를 저장합니다.

```text
map_id
map_title
map_category
```

---

## 🧩 로비 ready 및 게임 시작 흐름

### ready 상태 변경

```text
클라이언트
    │ PATCH /api/lobbies/{code}/ready
    │ Body: { ready: true }
    ▼
LobbyController.updateReadyStatus()
    │ @AuthenticationPrincipal CustomPrincipal 주입
    ▼
LobbyService.updateReadyStatus()
    │ 1. principal 검증
    │ 2. Redis 로비 존재 여부 확인
    │ 3. 로비 상태 WAITING 확인
    │ 4. 참여자 여부 확인
    │ 5. 방장 ready 요청 차단
    ▼
LobbyRepository.updateReadyStatus()
    │ ready=true  → SADD lobby:{code}:ready userIdentifier
    │ ready=false → SREM lobby:{code}:ready userIdentifier
    ▼
LobbyEventService.notifyLobbyInfoRefresh()
    │ /topic/lobby/{code}/refresh 로 REFRESH_LOBBY_INFO 브로드캐스트
```

### canStart 계산

`GET /api/lobbies/{code}` 응답의 `canStart`는 FE의 게임 시작 버튼 활성화 기준입니다.

계산 조건:

```text
1. Redis 로비 상태가 WAITING
2. Redis map_id 존재
3. DB 기준 맵 문제 수가 roundCount 이상
4. Redis participants 기준 방장 제외 참여자 1명 이상
5. Redis ready Set 기준 방장 제외 모든 참여자가 ready
```

`canStart`는 조회 시점의 snapshot입니다.
실제 시작 가능 여부는 `POST /api/lobbies/{code}/start`에서 `start_lobby.lua`가 최종 검증합니다.

---

## 🧪 주요 검증 시나리오

### 로비 생성

| 시나리오                       | 기대 결과                      |
| -------------------------- | -------------------------- |
| `mapId` 없이 로비 생성           | `201 Created`, 맵 정보 `null` |
| 공개 맵 `mapId`로 로비 생성        | `201 Created`, 맵 정보 포함     |
| 본인 소유 비공개 맵 `mapId`로 로비 생성 | `201 Created`, 맵 정보 포함     |
| 타인 소유 비공개 맵 `mapId`로 로비 생성 | `403 Forbidden`            |
| 삭제된 맵 `mapId`로 로비 생성       | `409 Conflict`             |
| 존재하지 않는 `mapId`로 로비 생성     | `404 Not Found`            |
| 음수 또는 0 `mapId`로 로비 생성     | `400 Bad Request`          |

### Redis 저장

| 시나리오     | 기대 결과                                       |
| -------- | ------------------------------------------- |
| 맵 미선택 로비 | `map_id`, `map_title`, `map_category` 필드 없음 |
| 맵 선택 로비  | `map_id`, `map_title`, `map_category` 필드 있음 |
| 맵 미선택 로비 | `"null"` 문자열 저장 없음                          |
| ready 변경 | `lobby:{code}:ready` Set 추가/삭제              |
| 퇴장/강퇴    | ready Set에서 해당 userIdentifier 제거            |

### 로비 상세 / ready / start

| 시나리오                                | 기대 결과                                                |
| ----------------------------------- | ---------------------------------------------------- |
| 로비 상세 조회                            | 참여자 목록, ready 상태, canStart 반환                        |
| 방장이 ready 요청                        | `400 Bad Request`                                    |
| 일반 참여자가 ready 요청                    | `204 No Content`, refresh 브로드캐스트                     |
| canStart=true 상태에서 start 요청         | `204 No Content`, `GAME_STARTED` 브로드캐스트              |
| ready 미완료 상태에서 start 요청             | `409 Conflict`                                       |
| 맵 미선택 상태에서 start 요청                 | `409 Conflict`                                       |
| 맵 문제 수 부족 상태에서 start 요청             | `409 Conflict`                                       |
| stale participant가 있는 상태에서 start 요청 | `409 Conflict`, ready/participants/session 정합성 로그 기록 |
| Redis Lua 성공 후 DB 동기화 실패            | Redis rollback 또는 reconciliation queue 적재            |
