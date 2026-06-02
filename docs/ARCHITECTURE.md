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
│   │   │   ├── GuestLoginResponse.java             # 게스트 로그인 응답 DTO
│   │   │   ├── LoginRequest.java                   # 자체 로그인 요청 DTO
│   │   │   └── LoginResponse.java                  # 자체 로그인 응답 DTO
│   │   ├── entity/
│   │   │   ├── User.java
│   │   │   ├── GuestSession.java
│   │   │   ├── UserCredential.java
│   │   │   └── UserSession.java
│   │   ├── repository/
│   │   │   ├── UserRepository.java
│   │   │   ├── GuestSessionRepository.java
│   │   │   ├── UserCredentialRepository.java
│   │   │   └── UserSessionRepository.java
│   │   └── service/
│   │       ├── GuestAuthService.java
│   │       ├── RegisterAuthService.java
│   │       └── LoginAuthService.java
│   │
│   ├── chat/                                       # 채팅 도메인
│   │   ├── controller/
│   │   │   └── ChatController.java
│   │   └── service/
│   │       ├── ChatService.java
│   │       └── LobbyChatRateLimitService.java       # Redis 기반 로비 채팅 제한 정책
│   │
│   ├── lobby/                                      # 로비 도메인
│   │   ├── KickLobbyResult.java
│   │   ├── LeaveLobbyResult.java
│   │   ├── StartLobbyResult.java
│   │   ├── StartLobbyLuaResultCode.java
│   │   ├── controller/
│   │   │   ├── LobbyCommandController.java
│   │   │   ├── LobbyQueryController.java
│   │   │   └── LobbyEventController.java
│   │   ├── dto/
│   │   │   ├── CreateLobbyRequest.java
│   │   │   ├── CreateLobbyResponse.java
│   │   │   ├── JoinLobbyRequest.java
│   │   │   ├── JoinLobbyResponse.java
│   │   │   ├── KickLobbyPlayerRequest.java
│   │   │   ├── LobbyDetailResponse.java
│   │   │   ├── LobbyPlayerResponse.java
│   │   │   ├── LobbyMapMetadata.java
│   │   │   ├── LobbyRedisDto.java
│   │   │   ├── LobbySearchCondition.java
│   │   │   ├── LobbySortType.java
│   │   │   ├── UpdateLobbyMapRequest.java
│   │   │   └── UpdateLobbyReadyRequest.java
│   │   ├── entity/
│   │   │   ├── GameLobby.java
│   │   │   ├── LobbyDefaults.java
│   │   │   └── LobbyStatus.java
│   │   ├── repository/
│   │   │   ├── GameLobbyJpaRepository.java
│   │   │   ├── LobbyRepository.java
│   │   │   ├── LobbyRepositoryImpl.java
│   │   │   └── redis/
│   │   │       ├── LobbyInviteCodeGenerator.java
│   │   │       ├── LobbyLuaScriptExecutor.java
│   │   │       ├── LobbyLuaResultMapper.java
│   │   │       ├── LobbyRedisCommandRepository.java
│   │   │       ├── LobbyRedisQueryRepository.java
│   │   │       └── LobbyStartReconciliationRepository.java
│   │   └── service/
│   │       ├── LobbyCanStartPolicy.java
│   │       ├── LobbyCreateService.java
│   │       ├── LobbyJoinService.java
│   │       ├── LobbyLeaveEventHandler.java
│   │       ├── LobbyMapPolicy.java
│   │       ├── LobbyMapUpdateService.java
│   │       ├── LobbyPlayerNicknameResolver.java
│   │       ├── LobbyQueryService.java
│   │       ├── LobbyReadyService.java
│   │       ├── LobbyRealtimeNotifier.java
│   │       ├── LobbyStartPolicy.java
│   │       ├── LobbyStartService.java
│   │       └── LobbyStartReconciliationService.java
│   │
│   ├── map/                                        # 퀴즈 맵 도메인
│   │   ├── controller/
│   │   │   └── MapController.java
│   │   ├── dto/
│   │   │   └── ...
│   │   ├── entity/
│   │   │   ├── QuizMap.java
│   │   │   ├── MapItem.java
│   │   │   └── MapCategory.java
│   │   ├── repository/
│   │   │   ├── QuizMapJpaRepository.java
│   │   │   └── MapItemJpaRepository.java
│   │   └── service/
│   │       ├── MapService.java
│   │       ├── MapCacheEvictor.java
│   │       ├── MapItemPersistenceService.java
│   │       └── MapPublicationValidator.java
│   │
│   ├── youtube/                                    # YouTube oEmbed 검증 도메인
│   │   ├── client/
│   │   │   └── YoutubeOEmbedClient.java
│   │   ├── model/
│   │   │   └── YoutubeMetadata.java
│   │   └── service/
│   │       └── YoutubeValidationService.java
│   │
│   ├── report/                                     # 신고 도메인
│   │   ├── controller/
│   │   ├── dto/
│   │   ├── entity/
│   │   ├── repository/
│   │   └── service/
│   │
│   └── game/                                       # 인게임 플레이 도메인
│       ├── controller/
│       │   ├── GameEventController.java            # 인게임 WebSocket 엔드포인트
│       │   └── GameSessionController.java          # 인게임 REST 엔드포인트
│       ├── dto/
│       │   ├── CurrentRoundStatusResponse.java
│       │   ├── GameChatMessageDto.java
│       │   ├── ReadyToPlayRequest.java
│       │   ├── RoundCorrectResponse.java
│       │   ├── RoundMetadataDto.java
│       │   ├── RoundPlaybackStartedDto.java
│       │   └── RoundStartDto.java
│       ├── entity/
│       │   ├── GameSession.java
│       │   └── GameSessionPlayer.java
│       ├── exception/
│       │   ├── GameSessionAlreadyExistsException.java
│       │   └── NotEnoughMapItemsException.java
│       ├── repository/
│       │   ├── GameSessionJpaRepository.java
│       │   └── GameSessionPlayerJpaRepository.java
│       ├── service/
│       │   ├── GameAnswerService.java              # 정답 검증 및 판별 비즈니스
│       │   ├── GameParticipantResolver.java        # WebSocket 세션 참가자 검증
│       │   ├── GameRealtimeNotifier.java           # 인게임 Pub/Sub 브로드캐스트
│       │   ├── GameRoundEndService.java            # 라운드 종료 처리 및 점수 합산 반영 비즈니스
│       │   ├── GameRoundProgressService.java       # 라운드 진행 관리 및 타이머/스케줄러 조정 서비스
│       │   ├── GameRoundStartService.java          # 라운드 데이터 웜업 및 라운드 준비/재생 제어
│       │   ├── GameSessionCreateService.java       # 게임 세션 및 라운드 초기 데이터 생성
│       │   └── GameSessionQueryService.java        # 현재 게임 세션 및 라운드 상태 조회
│       └── support/
│           ├── FuzzyMatcher.java                   # 오타 허용 임계거리 판단 정책
│           └── LevenshteinDistance.java            # Levenshtein Distance 계산 (공간 복잡도 O(min(M, N)))
│
└── global/                                         # 전역 인프라 레이어
    ├── config/
    │   ├── RedisConfig.java
    │   ├── RedisScriptConfig.java
    │   ├── SchedulingConfig.java
    │   ├── WebSocketConfig.java
    │   └── SecurityConfig/
    │
    ├── constant/
    │   ├── RedisKeys.java
    │   ├── StompDestinations.java
    │   └── WebSocketHeaders.java
    │
    ├── redis/
    │   ├── RedisPublisher.java
    │   └── RedisSubscriber.java
    │
    ├── websocket/
    │   ├── CustomStompErrorHandler.java
    │   ├── LobbyEnterResultMapper.java
    │   ├── StompChannelInterceptor.java
    │   ├── WebSocketEventListener.java
    │   ├── WebSocketMetric.java
    │   ├── WebSocketSessionUtils.java
    │   ├── dto/
    │   │   └── ChatMessageDto.java
    │   ├── error/
    │   │   ├── StompErrorAction.java
    │   │   ├── StompErrorCode.java
    │   │   ├── StompErrorException.java
    │   │   └── StompErrorPayload.java
    │   └── event/
    │       └── PlayerLeaveEvent.java
    │
    └── security/
        ├── SecurityEndpoints.java
        └── jwt/
            ├── CustomPrincipal.java
            ├── JwtAuthenticationFilter.java
            ├── JwtClaims.java
            ├── JwtTokenProvider.java
            └── TokenWithExpiry.java
```

---

## 🏛️ 아키텍처 원칙

### 의존 방향 규칙

```text
domain  →  global  (허용)
global  →  domain  (금지)
```

`global` 패키지는 도메인에 종속되지 않는 순수 인프라 레이어입니다.  
`global`이 `domain`을 직접 참조하면 순환 의존과 결합도 증가가 발생합니다.

### 의존 방향 역전 해결 사례

`WebSocketEventListener`는 `LobbyEventService`를 직접 호출하지 않고 `ApplicationEventPublisher`로 `PlayerLeaveEvent`를 발행합니다.

```text
global/WebSocketEventListener
    │ publishEvent(PlayerLeaveEvent)
    ▼
domain/LobbyLeaveEventHandler @EventListener
```

### 도메인 간 의존 규칙

동일한 `domain` 하위 도메인 간 참조는 비즈니스 흐름상 허용하지만, 영속성 엔티티 직접 결합은 최소화합니다.

예를 들어 로비 생성 시 맵을 연결할 때는 맵 접근 권한을 서비스 계층에서 검증한 뒤, Redis 저장 계층에는 `LobbyMapMetadata`만 전달합니다.

```text
LobbyCreateService
    ├── QuizMap 조회
    ├── 맵 존재/삭제/권한 검증
    └── LobbyMapMetadata 생성
            ▼
LobbyRepositoryImpl
    └── Redis 저장에 필요한 mapId, mapTitle, mapCategory만 사용
```

또한, 인게임 플레이 중에 이루어지는 채팅 및 정답 제출은 대기실/로비를 다루는 `chat` 도메인과의 순환 의존을 방지하기 위해 완전히 격리되어 있습니다. 인게임 채팅 엔드포인트 `/app/game/{code}/chat`은 `game` 도메인의 `GameEventController`가 수용하여 `GameAnswerService`로 직접 제어를 위임합니다.

```text
GameEventController (인게임 채팅 송신 수용)
     │
     ▼
GameAnswerService (정답 여부 검증 및 라우팅)
     ├── (최초 정답자) -> Redis 정답자 Set 등록 및 SYSTEM 공지 발행 + 개별 성공 통지(/user/queue/game/answers)
     └── (오답 및 정답자 채팅) -> CHAT 타입 브로드캐스트 (스포일러 방지를 위해 정답 키워드 포함 시 *** 마스킹)
```

---

## 📋 공개 로비 목록 조회 정책

공개 로비 목록 조회는 `GET /api/lobbies`에서 처리합니다.

```text
Client
    │ GET /api/lobbies?keyword=&mapCategory=&sort=&page=&size=
    ▼
LobbyQueryController
    ▼
LobbySearchCondition
    ▼
LobbyQueryService
    │ - WAITING / PLAYING 상태 노출
    │ - FINISHED 상태 제외
    │ - keyword 제목 검색
    │ - mapCategory 필터링
    │ - latest / most_players / most_available 정렬
    │ - page / size 기준 페이징
    │ - Redis 손상 mapCategory 로비 제외
    │
    ├─ [필터 없음 + 정렬 인덱스 존재]
    │      Redis ZSET 인덱스에서 page 범위의 lobbyCode만 조회
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

### 노출 정책

- 공개 로비 목록에는 `WAITING`, `PLAYING` 상태 로비가 노출됩니다.
- `FINISHED` 상태 로비는 목록에서 제외됩니다.
- `WAITING` 로비는 입장 가능한 로비입니다.
- `PLAYING` 로비는 목록에는 노출되지만 입장은 허용하지 않습니다.
- 클라이언트는 `status=PLAYING` 로비를 “진행 중”으로 표시하고 입장 버튼을 비활성화해야 합니다.

### 정렬 인덱스

| Redis Key | Type | Member | Score | 갱신 시점 |
| --- | --- | --- | --- | --- |
| `lobby:public:latest` | ZSET | lobby code | `created_at_epoch_millis` | 공개 로비 생성/삭제 |
| `lobby:public:most_players` | ZSET | lobby code | `current_players` | 생성/입장/퇴장/강퇴 |
| `lobby:public:most_available` | ZSET | lobby code | `max_players - current_players` | 생성/입장/퇴장/강퇴 |

필터가 있는 경우 ZSET page 범위를 먼저 자르면 전체 필터링 결과 기준의 page가 깨질 수 있습니다.  
따라서 `keyword`, `mapCategory` 필터가 있는 경우는 전체 공개 로비 조회 후 Java 필터/정렬/페이징으로 처리합니다.

---

## 🔄 실시간 메시지 흐름

### 채팅 메시지 흐름

```text
클라이언트
    │ STOMP SEND /app/chat/global 또는 /app/chat/lobby/{code}
    ▼
ChatController
    ▼
ChatService
    │ 1. STOMP 세션에서 userIdentifier 추출
    │ 2. 클라이언트 sender / roomId / timestamp 무시
    │ 3. content null / blank / length 검증
    │ 4. 일반 사용자 메시지는 CHAT 타입만 허용
    │
    ├─ [global chat]
    │      RedisPublisher.publish(/topic/chat/global)
    │
    └─ [lobby chat]
           1. 로비 존재 여부 검증
           2. 강퇴 유저 차단
           3. 로비 참여자 여부 검증
           4. Redis 기반 쿨타임 검증
           5. Redis 기반 반복 메시지 검증
           6. RedisPublisher.publish(/topic/lobby/{code})
    ▼
Redis Pub/Sub
    ▼
RedisSubscriber.onMessage()
    ▼
SimpMessagingTemplate.convertAndSend()
    ▼
클라이언트
```

#### 로비 채팅 송신 destination

| 구분 | STOMP destination | 설명 |
| --- | --- | --- |
| 전체 채팅 송신 | `/app/chat/global` | 전체 채팅 메시지 송신 |
| 로비 채팅 송신 | `/app/chat/lobby/{code}` | 특정 로비 채팅 메시지 송신 |

#### 로비 채팅 수신 destination

| 구분 | STOMP destination | 설명 |
| --- | --- | --- |
| 전체 채팅 수신 | `/topic/chat/global` | 전체 채팅 메시지 수신 |
| 로비 채팅 수신 | `/topic/lobby/{code}` | 로비 채팅 및 로비 시스템 메시지 수신 |
| 로비 정보 refresh | `/topic/lobby/{code}/refresh` | 로비 상세 정보 재조회 신호 |
| 게임 시작 이벤트 | `/topic/lobby/{code}/game` | 대기실 → 인게임 전환 신호 |

#### 클라이언트 송신 payload

클라이언트는 일반 채팅만 보낼 수 있습니다.

```json
{
  "type": "CHAT",
  "content": "안녕하세요"
}
```

서버는 클라이언트가 보낸 `sender`, `roomId`, `timestamp`를 신뢰하지 않습니다.  
해당 값들은 서버에서 다음 기준으로 다시 구성합니다.

| 필드 | 서버 처리 |
| --- | --- |
| `type` | 일반 사용자 송신은 `CHAT`만 허용 |
| `roomId` | STOMP destination의 `{code}` 또는 `"global"` 값으로 덮어씀 |
| `sender` | STOMP 세션의 `userIdentifier`로 덮어씀 |
| `content` | trim 후 blank / length 검증 |
| `timestamp` | 서버 시각으로 생성 |

#### 서버 송신 payload 공통 구조

```json
{
  "type": "CHAT",
  "roomId": "ABC123",
  "sender": "user-identifier",
  "content": "안녕하세요",
  "timestamp": "2026-05-26T12:00:00"
}
```

| 필드 | 타입 | 설명 |
| --- | --- | --- |
| `type` | string | 메시지 타입 |
| `roomId` | string | `"global"` 또는 로비 코드 |
| `sender` | string | 메시지 주체 userIdentifier |
| `content` | string | 표시할 메시지 본문 |
| `timestamp` | string | 서버 발행 시각 |

#### 메시지 타입 계약

| type | 발행 주체 | destination | 설명 | FE 처리 기준 |
| --- | --- | --- | --- | --- |
| `CHAT` | 사용자 | `/topic/chat/global`, `/topic/lobby/{code}` | 일반 채팅 | 일반 말풍선 |
| `SYSTEM` | 서버 | `/topic/lobby/{code}` | 범용 시스템 안내 | 시스템 안내 스타일 |
| `ENTER` | 서버 | `/topic/lobby/{code}` | 로비 입장 알림 | 입장 안내 |
| `LEAVE` | 서버 | `/topic/lobby/{code}` | 로비 퇴장 알림 | 퇴장 안내 |
| `KICK` | 서버 | `/topic/lobby/{code}` | 강퇴 알림 | 강퇴 안내 또는 모달 |
| `READY_CHANGED` | 서버 | `/topic/lobby/{code}` | ready 상태 변경 알림 | ready 변경 안내 |
| `HOST_CHANGED` | 서버 | `/topic/lobby/{code}` | 방장 위임 알림 | 방장 변경 안내 |

FE는 `content` 문자열을 파싱하지 않습니다.  
화면 분기는 반드시 `type` 기준으로 처리합니다.

#### 로비 채팅 검증 정책

로비 채팅은 다음 조건을 모두 만족해야 전송됩니다.

| 검증 | 실패 시 처리 |
| --- | --- |
| 로비가 Redis에 존재해야 함 | 404 |
| 송신자가 `lobby:{code}:kicked`에 없어야 함 | 403 |
| 송신자가 `lobby:{code}:participants`에 있어야 함 | 403 |
| `content`가 null 또는 blank가 아니어야 함 | 400 |
| `content`가 500자 이하여야 함 | 400 |
| 사용자 송신 타입은 `CHAT`이어야 함 | 400 |
| 1초 쿨타임을 통과해야 함 | 429 |
| 5초 이내 동일 메시지 반복이 아니어야 함 | 429 |

#### 로비 채팅 제한 Redis Key

| Redis Key | Type | Value | TTL | 설명 |
| --- | --- | --- | --- | --- |
| `chat:lobby:{code}:cooldown:{userIdentifier}` | String | `"1"` | 1초 | 같은 사용자의 로비 채팅 연속 전송 제한 |
| `chat:lobby:{code}:recent:{userIdentifier}` | String | SHA-256(content) | 5초 | 같은 메시지 단기 반복 전송 제한 |

반복 메시지 제한은 원문이 아니라 SHA-256 해시를 저장합니다.  
채팅 본문에 개인정보가 포함될 수 있으므로 제한 검증 목적에는 해시만 저장합니다.

#### 서버 시스템 메시지 예시

READY_CHANGED:

```json
{
  "type": "READY_CHANGED",
  "roomId": "ABC123",
  "sender": "user-identifier",
  "content": "user-identifier님이 준비 완료 상태로 변경했습니다.",
  "timestamp": "2026-05-26T12:00:00"
}
```

HOST_CHANGED:

```json
{
  "type": "HOST_CHANGED",
  "roomId": "ABC123",
  "sender": "new-host-identifier",
  "content": "new-host-identifier님이 새로운 방장이 되었습니다.",
  "timestamp": "2026-05-26T12:00:00"
}
```

KICK:

```json
{
  "type": "KICK",
  "roomId": "ABC123",
  "sender": "target-user-identifier",
  "content": "target-user-identifier님이 강퇴되었습니다.",
  "timestamp": "2026-05-26T12:00:00"
}
```

### 인게임 메시지 및 동기화 흐름

#### 1. 인게임 준비 및 YouTube IFrame 동기화 흐름 (#103)
게임 세션 시작 혹은 다음 라운드 진행 시, 비디오가 정확히 동기화되어 모든 사용자에게 동시에 재생될 수 있도록 2단계 동기화가 이루어집니다.

```text
클라이언트 (방장/참가자)                               서버
      │                                                │
      │ 1. [ROUND_READY] 브로드캐스트 수신              │
      │    (videoId, startTime, endTime, limit)        │
      │    - 스포일러 방지를 위해 정답/메타 생략       │
      │    - YouTube IFrame API 로딩 및 버퍼링 시작    │
      │◄───────────────────────────────────────────────┤
      │                                                │
      │ 2. YouTube IFrame 로딩 완료 (ready-to-play)     │
      │    SEND /app/game/{code}/ready-to-play         │
      ├───────────────────────────────────────────────►│ 1. ready count 증가 (Redis Set)
      │                                                │ 2. 모든 세션의 준비 완료 취합 또는
      │                                                │    10초 타임아웃 발생 감지
      │                                                │ 3. 재생 시작 조건 충족 시
      │                                                │
      │ 3. [ROUND_PLAYBACK_STARTED] 브로드캐스트 수신   │
      │    (roundNo, serverStartedAt, duration)        │
      │◄───────────────────────────────────────────────┤
      │                                                │
      │ 4. 재생 시간 보정 및 강제 재생                 │
      │    - (현재 서버시간 - serverStartedAt) 만큼       │
      │      seekTo() 후 playVideo() 수행              │
      ▼                                                ▼
```

#### 2. 단일 채팅창 기반 인게임 정답 제출 및 판별 흐름 (#104)
사용자는 단일 채팅창에서 일반 채팅 대화와 정답 제출을 동시에 수행합니다. 시스템은 사용자가 아직 정답을 맞추지 못했을 경우 정답 여부를 판별하여 처리합니다.

```text
클라이언트 (미정답자)                                 서버
      │                                                │
      │ 1. 채팅/정답 입력 (SEND /app/game/{code}/chat)   │
      ├───────────────────────────────────────────────►│ 1. 사용자가 이미 정답을 맞춘 상태인지 확인 (Redis Set)
      │                                                │ 2. (미정답자일 시) 정답 여부 대소문자/공백 제거 비교
      │                                                │ 3. Fuzzy Match(Levenshtein Distance)로 오타 판단
      │                                                │
      │                    [정답인 경우]               │
      │                    - 일반 채팅 브로드캐스트 차단
      │                    - Redis 정답자 Set 등록
      │                    - 전체 공지 브로드캐스트 (SYSTEM 타입)
      │                      "/topic/game/{code}/chat"
      │◄───────────────────────────────────────────────┤
      │                                                │
      │                    - 개별 정답 성공 통지 (ROUND_CORRECT)
      │                      "/user/queue/game/answers"
      │◄───────────────────────────────────────────────┤
      │                                                │
      │                    [오답/일반채팅인 경우]      │
      │                    - 일반 채팅 브로드캐스트 (CHAT 타입)
      │                      "/topic/game/{code}/chat"
      │◄───────────────────────────────────────────────┤
```

```text
클라이언트 (이미 정답을 맞춘 사람)                     서버
      │                                                │
      │ 1. 채팅 입력 (SEND /app/game/{code}/chat)       │
      ├───────────────────────────────────────────────►│ 1. 사용자가 이미 정답을 맞춘 상태인지 확인 (Redis Set)
      │                                                │ 2. 입력된 채팅 본문에 정답 키워드가 포함되는지 확인
      │                                                │ 3. (스포일러 방지) 정답 포함 시 본문 "***" 마스킹
      │                                                │
      │ 2. 마스킹된 일반 채팅 수신 (CHAT 타입)        │
      │◄───────────────────────────────────────────────┤
```

---

## WebSocket 로비 입장 흐름

로비 입장은 `SessionSubscribeEvent` 이후가 아니라, `StompChannelInterceptor.preSend()`에서 SUBSCRIBE 통과 전에 처리합니다.

```text
클라이언트
    │ STOMP SUBSCRIBE /topic/lobby/{code}
    ▼
StompChannelInterceptor.preSend()
    │ 1. SUBSCRIBE 명령 검증
    │ 2. /topic/lobby/{code} 구독인지 확인
    │ 3. 세션에서 userIdentifier 추출
    │ 4. wsSessionId 추출
    │ 5. sessionSequence 추출
    │ 6. enter_lobby.lua 실행
    │
    ├─ 성공
    │   ├─ ENTERED
    │   ├─ ALREADY_JOINED
    │   └─ SESSION_REPLACED:{previousWsSessionId}
    │        ▼
    │      SUBSCRIBE 프레임 통과
    │        ▼
    │      SessionSubscribeEvent 발생
    │        ▼
    │      WebSocketEventListener 후처리
    │        ├─ ENTER 메시지 발행
    │        └─ 로비 refresh 브로드캐스트
    │
    └─ 실패
        ├─ Lua 반환값을 StompErrorCode로 변환
        ├─ ws:connection:{wsSessionId} 보상 삭제
        └─ StompErrorException 발생
              ▼
           CustomStompErrorHandler
              ▼
           STOMP ERROR JSON payload 응답
```

> 로비 입장 처리는 `/topic/lobby/{code}` 구독 통과 전에만 수행합니다.  
> `/topic/lobby/{code}/refresh`, `/topic/lobby/{code}/game` 구독은 로비 정보 갱신/게임 시작 이벤트 수신용이며, 입장 처리 대상이 아닙니다.

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
실제 참여자 등록은 `SUBSCRIBE /topic/lobby/{code}` 시점에 `enter_lobby.lua`로 원자 처리합니다.

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

### 최신 세션 유지 정책

동일한 `userIdentifier`가 같은 로비에 여러 WebSocket 세션으로 진입할 경우, `CONNECT` 시 Redis `INCR`로 발급한 `sessionSequence`가 더 큰 세션을 최신 세션으로 봅니다.

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
LobbyLeaveEventHandler.handlePlayerLeave(@EventListener)
    ▼
leave_lobby.lua
    ├── DESTROYED → 전역 로비 리스트 새로고침
    ├── DELEGATED → HOST_CHANGED 메시지 브로드캐스트 + 로비 내부 새로고침
    └── LEFT      → 로비 내부 새로고침
```

### stale DISCONNECT 정책

동일 userIdentifier의 새 WebSocket 세션이 기존 세션을 대체한 후, 이전 세션의 DISCONNECT가 늦게 도착할 수 있습니다.

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
    ▼
LobbyEventService.kickLobbyPlayer()
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
    ├── 대상 ws:connection 키 삭제
    ├── KICK 메시지 로비 채팅 채널로 브로드캐스트
    └── 로비 내부 refresh 브로드캐스트
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
    │ 3. DB GAME_LOBBY 스냅샷 조회 및 PESSIMISTIC_WRITE 락 획득
    │ 4. 선택된 맵 존재/삭제 여부 확인
    │ 5. 맵 문제 수 >= roundCount 검증
    │ 6. 트랜잭션 동기화 활성 여부 확인
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
GameSessionCreateService.createGameSession()
    ├── MapItem 조회 및 셔플
    ├── DB GameSession, GameSessionPlayer 스냅샷 생성
    └── init_game_session.lua 로 Redis 인게임 세션 초기화
    ▼
afterCommit
    ├── /topic/lobby/{code}/game → GAME_STARTED
    ├── /topic/lobby/{code}/refresh → REFRESH_LOBBY_INFO
    └── /topic/game/{code}/round → RoundStartDto
```

`GAME_STARTED` 이벤트와 첫 라운드 이벤트는 DB 트랜잭션 커밋 이후에만 발행합니다.  
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
    ├── /topic/lobby/{code}/game 으로 GAME_STARTED 브로드캐스트
    └── /topic/game/{code}/round 로 첫 번째 라운드 RoundStartDto 브로드캐스트
```

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
| `chat:lobby:{code}:cooldown:{userIdentifier}` | String | 로비 채팅 쿨타임 제한 |
| `chat:lobby:{code}:recent:{userIdentifier}` | String | 로비 채팅 동일 메시지 반복 제한 |
| `auth:guest:session:{token}` | Hash | 게스트 세션 정보 |
| `auth:refresh:{sessionId}` | String | Refresh Token |
| `auth:session:active:{sessionId}` | String | 활성 세션 |
| `auth:blacklist:access:{accessTokenHash}` | String | Access Token 블랙리스트 |
| `user_status:{userIdentifier}` | String | 사용자 온라인 상태 |
| `user_status:{userIdentifier}:sessions` | Set | 사용자별 활성 WebSocket 세션 목록 |
| `ws:connection:{wsSessionId}` | Hash | WebSocket 세션 → userId, lobbyCode 매핑 |
| `map:public:list:v:{version}:p:{page}:s:{size}` | String | 공개 맵 목록 페이지 캐시 |
| `map:public:{mapId}` | String | 공개 맵 단건 캐시 |
| `map:public:list:version` | String | 공개 맵 목록 캐시 버전 |
| `youtube:oembed:success:{videoId}` | String | YouTube oEmbed 성공 캐시 |
| `youtube:oembed:failure:{videoId}` | String | YouTube oEmbed 실패 negative cache |
| `game:session:{lobbyCode}` | Hash | 인게임 세션 메타데이터 |
| `game:session:{lobbyCode}:rounds` | List | 출제된 라운드 목록 |
| `game:session:{lobbyCode}:players` | Hash | 인게임 플레이어 점수 |
| `game:session:{lobbyCode}:round:{roundNo}:data` | Hash | 라운드 정답(JSON list), 비디오 제목, 아티스트 메타데이터 및 최초 정답자 ID (`first_correct_user_id`) |
| `game:session:{lobbyCode}:round:{roundNo}:correct_players` | Set | 해당 라운드에서 이미 정답을 맞춘 플레이어 목록 (스포일러 판정용) |
| `game:session:{lobbyCode}:round:{roundNo}:correct_times` | Hash | 해당 라운드 정답 제출 시간 정보 |
| `game:session:{lobbyCode}:round:{roundNo}:ended_lock` | String | 라운드 종료 중복 실행 방지 분산 락 |
| `game:session:{lobbyCode}:round:{roundNo}:ready_players` | Set | 해당 라운드 시작 전 YouTube IFrame 준비 완료 신호를 보낸 플레이어 목록 |


### `lobby:{code}` Hash 구조

| 필드 | 설명 |
| --- | --- |
| `code` | 로비 초대 코드 |
| `host_user_id` | 현재 방장 userIdentifier |
| `title` | 로비 제목 |
| `max_players` | 최대 참여 인원 |
| `current_players` | 현재 참여 인원 캐시 |
| `is_private` | 비공개 여부 |
| `status` | 로비 상태 |
| `map_id` | 선택된 맵 ID |
| `map_title` | 선택된 맵 제목 |
| `map_category` | 선택된 맵 카테고리 원본 값 |
| `created_at_epoch_millis` | Redis TIME 기준 생성 시각 |

`map_id`, `map_title`, `map_category`는 로비 생성 시 `mapId`가 전달된 경우에만 저장합니다.  
맵이 선택되지 않은 로비는 위 세 필드를 저장하지 않습니다.

### `game:session:{lobbyCode}` Hash 구조

| 필드 | 설명 |
| --- | --- |
| `lobby_code` | 게임 로비 초대 코드 |
| `status` | 인게임 상태 (`READY`, `PLAYING`, `FINISHED`) |
| `current_round_no` | 현재 진행 중인 라운드 번호 (1-based) |
| `time_limit_seconds` | 라운드별 재생 시간 제한 |
| `playback_started_at` | 현재 라운드의 비디오 재생 개시 시각 (Clock sync용, 아직 재생 전이면 null) |
| `round_ended_at:{roundNo}` | 특정 라운드가 종료된 시각 (Epoch milliseconds, 결과 화면 대기 10초 및 중복 정답 차단 검사에 사용) |

### `game:session:{lobbyCode}:round:{roundNo}:data` Hash 구조

| 필드 | 설명 |
| --- | --- |
| `answers` | JSON 직렬화된 정답 목록 문자열 (예: `["곡제목1", "곡제목2"]`) |
| `title` | 정답 공개용 곡 공식 타이틀 |
| `artist` | 정답 공개용 가수명 |

---

## 로비 ready 상태 저장 구조

```text
lobby:{code}:ready
```

정책:

```text
1. 방장은 ready 대상에서 제외한다.
2. 일반 참여자만 ready 상태를 변경할 수 있다.
3. ready 변경은 PATCH /api/lobbies/{code}/ready에서 처리한다.
4. ready 변경 성공 시 /topic/lobby/{code}로 READY_CHANGED 시스템 메시지를 브로드캐스트한다.
5. ready 변경 성공 시 /topic/lobby/{code}/refresh로 REFRESH_LOBBY_INFO를 브로드캐스트한다.
6. 퇴장/강퇴 시 ready Set에서 해당 userIdentifier를 제거한다.
7. 게임 시작 직전 ready Set에는 있지만 participants Set에는 없는 stale ready 데이터를 정리한다.
```

---

## ⚡ 핵심 설계 결정

### Java 21 Virtual Thread + Lettuce

가상 스레드 환경에서 Redis 클라이언트로 Jedis 대신 Lettuce를 사용합니다.

Jedis는 동기 블로킹 방식으로 가상 스레드를 캐리어 스레드에 핀닝할 수 있으나, Lettuce는 Netty 기반 비동기 드라이버로 핀닝 위험을 줄입니다.

### Redis Pub/Sub + WebSocket 브로드캐스트

멀티 인스턴스 환경에서는 특정 서버에 연결된 WebSocket 세션만 로컬 메모리에 존재합니다.

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

### Redis 기반 로비 채팅 제한

로비 채팅 제한은 서버 메모리가 아니라 Redis에 저장합니다.

```text
chat:lobby:{code}:cooldown:{userIdentifier}
chat:lobby:{code}:recent:{userIdentifier}
```

이 구조는 WebSocket 서버가 여러 인스턴스로 늘어나도 동일 사용자에 대한 제한을 일관되게 적용할 수 있습니다.

---

## 게임 시작 Redis-DB 상태 불일치 재처리

게임 시작은 Redis Lua가 먼저 원자 검증 및 Redis 상태 전환을 수행하고, 이후 DB `GAME_LOBBY` 상태를 `PLAYING`으로 동기화합니다.

```text
Redis: PLAYING
DB: WAITING
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

---

## 운영 및 관측성 기준

### WebSocket Metric

`WebSocketMetric`은 활성 WebSocket 세션 수를 Prometheus에 노출합니다.

```text
CONNECT 성공 → increment
DISCONNECT 처리 → decrement
```

### Redis 장애 처리

| 영역 | 장애 처리 |
| --- | --- |
| 인증 refresh 저장소 | `503 Service Unavailable` |
| WebSocket CONNECT 온라인 상태 저장 실패 | STOMP ERROR `CONNECT_ONLINE_STATUS_FAILED` |
| 로비 입장 Lua 실패 | STOMP ERROR `LOBBY_ENTER_TEMPORARILY_UNAVAILABLE` |
| 로비 채팅 제한 Redis 처리 실패 | `503 Service Unavailable` |
| Pub/Sub 발행 실패 | 로컬 WebSocket fallback 또는 로그/관측성 처리 |
| 게임 시작 Redis-DB 불일치 | 보상 롤백 또는 reconciliation 큐 적재 |

### 로그 기준

| 상황 | 로그 기준 |
| --- | --- |
| Lua 알 수 없는 반환값 | `error` + monitoring required |
| Redis-DB 게임 시작 불일치 | `error` + reconciliation enqueue |
| 최대 재시도 초과 | `error` + alert required |
| READY_CHANGED / HOST_CHANGED / KICK 메시지 발행 실패 | `error` + alert required |
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

### 로비 채팅

FE는 로비 채팅 수신 시 `type` 기준으로 UI를 분기해야 합니다.

```text
CHAT          → 일반 채팅 말풍선
SYSTEM        → 시스템 안내
ENTER         → 입장 안내
LEAVE         → 퇴장 안내
KICK          → 강퇴 안내
READY_CHANGED → ready 상태 변경 안내
HOST_CHANGED  → 방장 변경 안내
```

FE는 `content` 문자열을 파싱하지 않습니다.  
서버가 내려준 `type`을 기준으로 스타일과 동작을 결정합니다.

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

### 인게임 플레이 및 동기화

#### 1) 유튜브 IFrame API 동기화 및 Clock Skew 보정
- FE는 인게임 화면 진입 시 `GET /api/system/time`을 호출하여 서버와 클라이언트 간의 **시간차(Clock Skew)**를 계산하고 보관해야 합니다.
- 라운드가 시작되어 `ROUND_READY` 수신 후 YouTube 비디오 로드가 완료되면 즉시 `SEND /app/game/{code}/ready-to-play`를 전송해야 합니다. (전체 준비가 완료되지 않아 대기하는 동안 비디오는 재생하지 않고 정지 또는 일시정지 상태여야 함)
- 모든 유저의 준비 완료 혹은 타임아웃으로 `ROUND_PLAYBACK_STARTED` 메시지를 받으면, FE는 즉시 비디오를 재생해야 합니다.
- **재생 지점 보정**: 네트워크 딜레이 등으로 인해 메시지 수신 시점이 비디오 시작 타임라인보다 늦을 수 있으므로, `(현재 서버 기준 밀리초 - serverStartedAt)` 만큼 비디오를 앞으로 감기(`seekTo`)한 후 재생(`playVideo`)하여 정확히 1초 미만의 싱크차를 맞춥니다.

#### 2) 단일 채팅창 연동 및 스포일러 방지 필터링
- **수신 분기**: 인게임 채팅 채널 `/topic/game/{code}/chat`을 구독하고, 수신된 메시지의 `type`을 확인합니다:
  - `CHAT`: 일반 사용자의 채팅 내용입니다. 본문 `content`가 `"***"`로 왔다면 이는 **정답을 맞춘 사용자가 정답 키워드를 누설하지 않도록 서버에서 마스킹한 스포일러 방지 본문**이므로, 그대로 화면에 출력합니다.
  - `SYSTEM`: 누군가 새로 정답을 맞췄을 때 브로드캐스트되는 공지입니다. (예: `"닉네임님이 정답을 맞췄습니다!"`)
- **송신 규칙**: 사용자가 텍스트를 입력하고 전송할 때는 모두 `SEND /app/game/{code}/chat`으로 단일하게 송신합니다.
- **개별 결과 수신**: 자신이 제출한 채팅이 정답에 해당할 경우, 서버는 일반 채팅 브로드캐스트를 중단(Drop)하고, 해당 사용자에게 `/user/queue/game/answers` 채널로 개별 정답 성공 통지(`ROUND_CORRECT`)를 보냅니다.
  - 이 통지에는 오타 허용으로 정답 처리되었는지를 알 수 있는 `isFuzzy` 필드가 포함되어 있으므로, FE는 이를 활용하여 사용자에게 "오타 허용 정답!" 같은 전용 연출을 표시할 수 있습니다.

#### 3) 라운드 종료 결과 노출 및 랭킹 갱신 (핵심 계약)
- **라운드 종료 수신**: FE는 `/topic/game/{code}/round-end` 채널을 구독하여 라운드 종료 이벤트를 수신합니다.
  - 이 이벤트는 각 라운드의 제한 시간이 종료되거나 모든 플레이어가 정답을 입력했을 때 서버에 의해 자동으로 트리거됩니다.
- **결과 노출 데이터**: 수신된 `RoundMetadataDto`에는 정답 곡 정보(`title`, `artist`, `answer`, `thumbnailUrl`)와 함께 플레이어들의 득점 및 실시간 순위 정보인 `rankings` 리스트가 포함되어 있습니다.
- **랭킹 및 가점 연출**: `rankings` 리스트 내부의 각 객체는 `PlayerRankingDto` 타입으로, 플레이어의 현재 총점(`score`), 순위(`rank`), 그리고 해당 라운드에서 획득한 가점(`scoreAdded` - 1등 140점, 그 외 정답자 100점, 오답자 0점)을 가지고 있습니다. FE는 `scoreAdded`를 활용하여 "+140" 등 가점 애니메이션을 UI상에 연출하고 랭킹 리스트를 갱신합니다.
- **자동 전환**: 라운드가 종료된 후 10초간 결과 화면을 노출한 뒤, 서버에 의해 자동으로 다음 라운드가 준비 상태(`ROUND_READY`)로 넘어가게 되므로 FE는 이에 맞춰 인게임 화면으로 복귀하여 비디오 재생 준비 신호를 다시 송신해야 합니다. 마지막 라운드인 경우에는 로비 및 게임 상태가 `FINISHED`로 변경되며 결과화면으로 자동 전환됩니다.

#### 4) FE 연동 핵심 계약 (시간 동기화, 의미 차이, 중복 수신 방지)
- **ROUND_READY와 ROUND_PLAYBACK_STARTED의 의미 차이**:
  - `ROUND_READY`: 새로운 라운드가 개시되어 문제(비디오) 로딩을 준비하라는 신호입니다. 비디오 메타데이터(`videoId`, `youtubeUrl` 등)를 전달하며, FE는 이 시점에 비디오 IFrame을 백그라운드에 로드하고 준비(`ready-to-play` 전송)를 마쳐야 하며, 전체 플레이어가 준비되기 전까지 비디오는 재생하지 않고 대기(정지/일시정지) 상태여야 합니다.
  - `ROUND_PLAYBACK_STARTED`: 모든 참가자의 준비가 완료되었거나 대기 타임아웃(10초)이 경과하여 비디오 재생을 실제로 개시하라는 신호입니다. FE는 이 신호를 받은 즉시 비디오 재생을 시작하며, Clock Skew 보정된 시작 시각을 기준으로 재생 지점(`seekTo`)을 동기화합니다.
- **중복 이벤트 수신 시 처리 기준**:
  - 분산 환경 또는 일시적인 네트워크 재접속 과정에서 동일한 `ROUND_READY` 또는 `ROUND_PLAYBACK_STARTED` 이벤트가 중복 수신될 수 있습니다.
  - FE는 각 이벤트의 `roundNo` 필드를 확인하여 **이미 처리 중이거나 완료된 라운드 번호의 이벤트는 멱등적으로 무시**해야 합니다.
  - 특히 비디오 재생 개시(`ROUND_PLAYBACK_STARTED`)의 경우, 동일한 `roundNo`에 대해 이미 재생이 진행 중이라면 중복 재생 명령이나 `seekTo` 보정 연산을 수행하지 않아야 화면 끊김이나 비정상 상태를 방지할 수 있습니다.
- **게임 종료 정책**:
  - 게임의 최종 종료는 별도 `GAME_FINISHED` 이벤트를 발행하지 않고, 라운드 종료 결과 알림(`ROUND_END`) 내 `isLastRound=true`인 것을 기준으로 처리합니다. 
  - FE는 `isLastRound=true` 조건이 들어오면 다음 라운드 준비를 하지 않고 최종 스코어보드 및 결과 연출 화면으로 전환합니다.


