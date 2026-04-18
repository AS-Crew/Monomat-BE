package io.github.ascrew.monomatbe.service;

import io.github.ascrew.monomatbe.repository.LobbyRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.security.Principal;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class LobbyEventService {

    // 로비 ID 패턴: 영문 대문자 및 숫자 조합 6~12자리
    private static final Pattern LOBBY_CODE_PATTERN = Pattern.compile("^[A-Z0-9]{6,12}$");

    private final SimpMessagingTemplate messagingTemplate;
    private final LobbyRepository lobbyRepository;

    // 로비 목록 새로고침 알림 송신
    public void notifyLobbyListRefresh() {
        messagingTemplate.convertAndSend("/topic/lobby/refresh", "REFRESH_LOBBY_LIST");
    }

    // [내부 전용] 실제 WebSocket 메시지 전송 로직 분리
    private void sendLobbyRefreshSignal(String code) {
        messagingTemplate.convertAndSend("/topic/lobby/" + code + "/refresh", "REFRESH_LOBBY_INFO");
    }

    // 특정 로비 정보 새로고침 알림 송신. 외부 호출용(검증 포함)
    public void notifyLobbyInfoRefresh(String code, Principal principal) {
        if (!validateLobbyAccess(code, principal)) {
            return;
        }
        sendLobbyRefreshSignal(code);
    }

    // 유저 퇴장 처리 비즈니스 로직. 방장 위임 및 로비 삭제 관리
    public void handlePlayerLeave(String code, String userId) {
        if (!StringUtils.hasText(code) || !StringUtils.hasText(userId)) return;

        // [필요 시 주석 해제하여 사용]
        // log.info("유저 퇴장 처리 시작 - 로비: {}, 유저: {}", code, userId);

        // 1. 현재 방장 확인
        String currentHost = lobbyRepository.getHostId(code);

        // 2. Redis에서 참여자 제거
        lobbyRepository.removeParticipant(code, userId);

        // 3. 남은 인원 확인
        Long participantCount = lobbyRepository.getParticipantCount(code);

        // 4. 인원이 0명이면 로비 파괴
        if (participantCount == null || participantCount == 0) {
            destroyLobby(code);
            notifyLobbyListRefresh();
            return;
        }

        // 5. 나간 사람이 방장이었으면 차순위 유저에게 위임
        if (userId.equals(currentHost)) {
            delegateHost(code);
        }

        // 6. 로비 내 유저들에게 상태 변경 알림 (내부 알림 전송 메서드 재사용)
        sendLobbyRefreshSignal(code);
    }

    // 로비 액세스 권한 검증. 패턴, 존재 여부, 참여 여부 확인.
    private boolean validateLobbyAccess(String code, Principal principal) {
        if (!StringUtils.hasText(code) || !LOBBY_CODE_PATTERN.matcher(code).matches()) {
            return false;
        }

        if (principal == null || !StringUtils.hasText(principal.getName())) {
            return false;
        }

        String userId = principal.getName();

        if (!lobbyRepository.existsByCode(code)) {
            // 필요 시 주석 해제하여 사용
            // log.warn("존재하지 않는 로비 접근 시도: {}", code);
            return false;
        }

        if (!lobbyRepository.isParticipant(code, userId)) {
            // 필요 시 주석 해제하여 사용
            // log.warn("권한 없는 유저의 로비 접근 시도: {} (유저: {})", code, userId);
            return false;
        }

        return true;
    }

    // 방장 위임 로직. 입장 순서가 가장 빠른 다음 유저 선출.
    private void delegateHost(String code) {
        String nextHostId = lobbyRepository.getNextHostCandidate(code);
        if (nextHostId != null) {
            lobbyRepository.updateHostId(code, nextHostId);
            // 필요 시 주석 해제하여 사용
            // log.info("방장 위임 완료 - 로비: {}, 새 방장: {}", code, nextHostId);
        }
    }

    // 로비 파괴 로직. 공개 목록 제외 및 Redis 데이터 완전 삭제 수행.
    private void destroyLobby(String code) {
        lobbyRepository.removeFromPublicList(code);
        lobbyRepository.deleteLobby(code);
        // [필요 시 주석 해제하여 사용]
        // log.info("로비 삭제 처리 완료 (인원 0명) - 로비: {}", code);
    }
    }
