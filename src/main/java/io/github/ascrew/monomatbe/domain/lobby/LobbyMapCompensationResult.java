package io.github.ascrew.monomatbe.domain.lobby;

/**
 * 로비 맵 변경 보상 복구 Lua 실행 결과
 *
 * [정책 배경]
 * 맵 변경 트랜잭션 보상 복구는 항상 안전하게 동작해야 한다.
 * 그러나 보상 시점에 다른 트랜잭션(start_lobby.lua)이 status를 PLAYING으로 바꿨다면
 * 이미 시작된 로비의 맵 메타데이터를 임의로 되돌려서는 안 된다.
 *
 * 이 enum은 compensate_lobby_map.lua가 정상 실행된 경우의 도메인 결과만 표현한다.
 * Redis 연결 단절·타임아웃·Lua 스크립트 로딩 실패 등 인프라 예외는 enum 값이 아닌
 * RuntimeException으로 전파되며, 호출자(LobbyMapUpdateService)가 별도로 처리한다.
 */
public enum LobbyMapCompensationResult {

    /** Redis 맵 메타데이터를 oldMetadata로 복구했다. */
    COMPENSATED,

    /** status가 더 이상 WAITING이 아니므로 안전을 위해 복구를 수행하지 않았다. */
    SKIPPED_NOT_WAITING,

    /** Redis 로비가 더 이상 존재하지 않는다 (이미 폭파/삭제됨). */
    LOBBY_NOT_FOUND
}
