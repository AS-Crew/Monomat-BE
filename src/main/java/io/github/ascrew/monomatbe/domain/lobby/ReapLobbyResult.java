/*
 * 빈 로비 폭파(reaper) 처리 결과를 표현하는 enum.
 *
 * [enum을 사용하는 이유]
 * reaper 결과는 부가 데이터 없이 상태만 구분하면 충분하다(대상 로비 코드는 입력값).
 * LeaveLobbyResult처럼 payload를 가질 필요가 없으므로 sealed interface 대신 enum이 적합하다.
 * switch에서 모든 케이스를 강제 검증할 수 있다.
 */
package io.github.ascrew.monomatbe.domain.lobby;

public enum ReapLobbyResult {

    /** 빈 로비(0명 또는 전원 오프라인)를 폭파했다. 후속으로 LobbyClosedEvent 발행 대상이다. */
    REAPED,

    /** 온라인 참여자가 1명 이상 존재하여 보존했다. */
    ALIVE,

    /** 생성 후 grace 기간이 지나지 않아 보존했다(구독 대기 중 보호). */
    TOO_YOUNG,

    /** Hash가 이미 없는 stale 인덱스를 정리했다(self-heal). */
    STALE_INDEX,

    /** Lua 실행 결과가 null이거나 알 수 없는 값이다. */
    ERROR;

    /**
     * reap_lobby.lua 반환 문자열을 결과 enum으로 매핑한다.
     *
     * @param raw Lua 반환 문자열 (null 가능)
     * @return 매핑된 결과. 알 수 없거나 null이면 ERROR.
     */
    public static ReapLobbyResult from(String raw) {
        if (raw == null) {
            return ERROR;
        }

        return switch (raw) {
            case "REAPED" -> REAPED;
            case "ALIVE" -> ALIVE;
            case "TOO_YOUNG" -> TOO_YOUNG;
            case "STALE_INDEX" -> STALE_INDEX;
            default -> ERROR;
        };
    }
}
