package io.github.ascrew.monomatbe.common.constant;

import lombok.RequiredArgsConstructor;

/*
 * 시스템 내에서 사용되는 모든 Redis Key의 포맷을 중앙에서 관리하는 Enum
 * 하드코딩(Magic String)으로 인한 오타 방지 및 유지보수성 향상
 */
@RequiredArgsConstructor
public enum RedisKey {

    // 로비 관련 키
    LOBBY_INFO("lobby:%s"),
    LOBBY_PARTICIPANTS("lobby:%s:participants"),
    LOBBY_ORDER("lobby:%s:order"),
    PUBLIC_LOBBIES("lobby:public"),

    // 유저 상태 및 웹소켓 연결 매핑 키
    WS_CONNECTION("ws:connection:%s"),
    USER_STATUS("user_status:%s"),
    USER_ROOM("user_room:%s");

    private final String format;

    /*
     * 동적 파라미터를 받아 완성된 Redis Key 문자열을 반환합니다.
     * 파라미터가 필요 없는 경우(PUBLIC_LOBBIES 등) 빈 인자로 호출 가능합니다.
     */
    public String of(String... args) {
        return String.format(format, (Object[]) args);
    }
}
