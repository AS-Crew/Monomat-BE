/*
 * 로비 퇴장 처리 결과를 표현하는 sealed interface.
 *
 * [sealed interface를 사용하는 이유]
 * 퇴장 처리 결과는 DESTROYED, DELEGATED, LEFT, ERROR 네 가지로 명확히 정해져 있습니다.
 * sealed interface로 구현체를 제한하면 컴파일러가 switch 표현식에서
 * 모든 케이스를 처리했는지 검증해줍니다.
 * 새로운 결과 타입이 추가될 때 처리 누락을 컴파일 타임에 방지할 수 있습니다.
 *
 * [record를 사용하는 이유]
 * 각 결과는 불변 데이터 컨테이너이므로 record가 적합합니다.
 * equals, hashCode, toString이 자동 생성되어 테스트 작성도 용이합니다.
 */
package io.github.ascrew.monomatbe.domain.lobby;

public sealed interface LeaveLobbyResult
        permits LeaveLobbyResult.Destroyed,
        LeaveLobbyResult.Delegated,
        LeaveLobbyResult.Left,
        LeaveLobbyResult.Error {

    /**
     * 방장이 퇴장하여 남은 인원이 없어 로비가 폭파된 경우.
     *
     * @param lobbyCode 폭파된 로비 코드
     */
    record Destroyed(String lobbyCode) implements LeaveLobbyResult {}

    /**
     * 방장이 퇴장했지만 남은 인원이 있어 방장이 위임된 경우.
     *
     * @param lobbyCode  위임이 발생한 로비 코드
     * @param newHostId  새로운 방장의 사용자 식별자
     */
    record Delegated(String lobbyCode, String newHostId) implements LeaveLobbyResult {}

    /**
     * 일반 유저가 정상 퇴장한 경우.
     *
     * @param lobbyCode 퇴장한 로비 코드
     * @param userId    퇴장한 사용자 식별자
     */
    record Left(String lobbyCode, String userId) implements LeaveLobbyResult {}

    /**
     * Lua 스크립트 실행 중 예상치 못한 오류가 발생한 경우.
     * 정상적인 게임 흐름에서는 발생하지 않아야 합니다.
     *
     * @param reason 오류 사유
     */
    record Error(String reason) implements LeaveLobbyResult {}
}