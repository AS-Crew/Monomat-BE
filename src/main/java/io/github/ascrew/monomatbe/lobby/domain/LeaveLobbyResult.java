package io.github.ascrew.monomatbe.lobby.domain;

public sealed interface LeaveLobbyResult {
    // 1. 방장이 나가고 남은 인원이 없어 로비가 폭파됨
    record Destroyed(String lobbyCode) implements LeaveLobbyResult {}

    // 2. 방장이 나갔지만 남은 인원이 있어 방장이 위임됨
    record Delegated(String lobbyCode, String newHostId) implements LeaveLobbyResult {}

    // 3. 일반 유저가 정상 퇴장함
    record Left(String lobbyCode, String userId) implements LeaveLobbyResult {}

    // 4. 스크립트 실행 중 에러 발생
    record Error(String reason) implements LeaveLobbyResult {}
}

