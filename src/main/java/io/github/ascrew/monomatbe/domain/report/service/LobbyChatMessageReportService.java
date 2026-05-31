package io.github.ascrew.monomatbe.domain.report.service;

import io.github.ascrew.monomatbe.domain.auth.entity.User;
import io.github.ascrew.monomatbe.domain.auth.repository.UserRepository;
import io.github.ascrew.monomatbe.domain.chat.service.LobbyRecentChatMessageFinder;
import io.github.ascrew.monomatbe.domain.chat.service.RecentChatMessageLookupException;
import io.github.ascrew.monomatbe.domain.lobby.LobbyUserAccessStatus;
import io.github.ascrew.monomatbe.domain.lobby.entity.GameLobby;
import io.github.ascrew.monomatbe.domain.lobby.repository.GameLobbyJpaRepository;
import io.github.ascrew.monomatbe.domain.lobby.repository.LobbyRepository;
import io.github.ascrew.monomatbe.domain.report.dto.LobbyChatMessageReportRequest;
import io.github.ascrew.monomatbe.domain.report.dto.ReportResponse;
import io.github.ascrew.monomatbe.domain.report.entity.LobbyChatMessageReportSnapshot;
import io.github.ascrew.monomatbe.domain.report.entity.Report;
import io.github.ascrew.monomatbe.domain.report.entity.ReportStatus;
import io.github.ascrew.monomatbe.domain.report.entity.ReportTargetType;
import io.github.ascrew.monomatbe.domain.report.repository.LobbyChatMessageReportSnapshotRepository;
import io.github.ascrew.monomatbe.domain.report.repository.ReportRepository;
import io.github.ascrew.monomatbe.global.websocket.dto.ChatMessageDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * 로비 채팅 메시지 신고 서비스
 *
 * [책임]
 * - 로비 채팅 메시지 신고 생성
 * - 신고자/로비/참여자 검증
 * - Redis 최근 채팅 messageId 조회
 * - 자기 메시지 신고 차단
 * - 동일 사용자의 동일 메시지 PENDING 중복 신고 차단
 * - 동시 중복 신고 방지 lock 처리
 * - 신고 시점 채팅 메시지 스냅샷 저장
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LobbyChatMessageReportService {

    private static final String ERROR_INVALID_REPORTER =
            "유효하지 않은 인증 정보입니다. 다시 로그인해주세요.";
    private static final String ERROR_REPORTER_NOT_FOUND =
            "신고자를 찾을 수 없습니다.";
    private static final String ERROR_LOBBY_NOT_FOUND =
            "신고 대상 로비를 찾을 수 없습니다.";
    private static final String ERROR_DELETED_LOBBY =
            "삭제된 로비는 신고할 수 없습니다.";
    private static final String ERROR_LOBBY_CHAT_FORBIDDEN =
            "로비 참여자만 채팅 메시지를 신고할 수 있습니다.";
    private static final String ERROR_LOBBY_CHAT_KICKED =
            "강퇴된 로비의 채팅 메시지는 신고할 수 없습니다.";
    private static final String ERROR_MESSAGE_ID_REQUIRED =
            "신고 대상 채팅 메시지 ID가 필요합니다.";
    private static final String ERROR_CHAT_MESSAGE_NOT_FOUND =
            "신고 대상 채팅 메시지를 찾을 수 없습니다.";
    private static final String ERROR_RECENT_CHAT_LOOKUP_FAILED =
            "최근 채팅 저장소를 조회할 수 없습니다. 잠시 후 다시 시도해주세요.";
    private static final String ERROR_SELF_MESSAGE_REPORT =
            "자신이 작성한 채팅 메시지는 신고할 수 없습니다.";
    private static final String ERROR_DUPLICATE_REPORT =
            "이미 접수된 신고입니다.";
    private static final String ERROR_INVALID_REASON =
            "신고 사유는 비어 있을 수 없습니다.";
    private static final String ERROR_INVALID_CHAT_MESSAGE =
            "신고할 수 없는 채팅 메시지입니다.";

    private final ReportRepository reportRepository;
    private final LobbyChatMessageReportSnapshotRepository snapshotRepository;
    private final UserRepository userRepository;
    private final GameLobbyJpaRepository gameLobbyJpaRepository;
    private final LobbyRepository lobbyRepository;
    private final LobbyRecentChatMessageFinder lobbyRecentChatMessageFinder;
    private final LobbyChatMessageReportLockManager lockManager;

    @Transactional
    public ReportResponse reportLobbyChatMessage(
            String inviteCode,
            String messageId,
            Long reporterId,
            String reporterIdentifier,
            LobbyChatMessageReportRequest request
    ) {
        validateMessageId(messageId);

        User reporter = getReporter(reporterId);
        GameLobby lobby = getReportableLobby(inviteCode);

        validateReporterLobbyAccess(inviteCode, reporterIdentifier);

        ChatMessageDto chatMessage = getReportableChatMessage(inviteCode, messageId);

        validateNotSelfMessage(reporter.getId(), reporterIdentifier, chatMessage);

        String reason = normalizeReason(request.reason());

        return createReportWithLock(
                reporter,
                lobby,
                messageId,
                chatMessage,
                reason,
                inviteCode
        );
    }

    private ReportResponse createReportWithLock(
            User reporter,
            GameLobby lobby,
            String messageId,
            ChatMessageDto chatMessage,
            String reason,
            String inviteCode
    ) {
        boolean locked = lockManager.tryLock(
                reporter.getId(),
                lobby.getId(),
                messageId
        );

        if (!locked) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, ERROR_DUPLICATE_REPORT);
        }

        try {
            validateDuplicatePendingReport(
                    reporter.getId(),
                    lobby.getId(),
                    messageId
            );

            Report report = reportRepository.save(Report.builder()
                    .reporter(reporter)
                    .lobby(lobby)
                    .targetType(ReportTargetType.LOBBY_CHAT_MESSAGE)
                    .targetId(lobby.getId())
                    .targetReference(messageId)
                    .reason(reason)
                    .build());

            snapshotRepository.save(toSnapshot(report, chatMessage));

            log.info(
                    "로비 채팅 메시지 신고 접수 완료 - reportId: {}, reporterId: {}, lobbyId: {}, inviteCode: {}, messageId: {}",
                    report.getId(),
                    reporter.getId(),
                    lobby.getId(),
                    inviteCode,
                    messageId
            );

            return ReportResponse.from(report);
        } finally {
            lockManager.unlock(
                    reporter.getId(),
                    lobby.getId(),
                    messageId
            );
        }
    }

    private void validateMessageId(String messageId) {
        if (messageId == null || messageId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ERROR_MESSAGE_ID_REQUIRED);
        }
    }

    private User getReporter(Long reporterId) {
        if (reporterId == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, ERROR_INVALID_REPORTER);
        }

        return userRepository.findById(reporterId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        ERROR_REPORTER_NOT_FOUND
                ));
    }

    private GameLobby getReportableLobby(String inviteCode) {
        GameLobby lobby = gameLobbyJpaRepository.findByInviteCode(inviteCode)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        ERROR_LOBBY_NOT_FOUND
                ));

        if (Boolean.TRUE.equals(lobby.getIsDeleted())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, ERROR_DELETED_LOBBY);
        }

        return lobby;
    }

    private void validateReporterLobbyAccess(String inviteCode, String reporterIdentifier) {
        if (reporterIdentifier == null || reporterIdentifier.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, ERROR_INVALID_REPORTER);
        }

        LobbyUserAccessStatus accessStatus =
                lobbyRepository.getUserAccessStatus(inviteCode, reporterIdentifier);

        switch (accessStatus) {
            case PARTICIPANT -> {
                return;
            }
            case LOBBY_NOT_FOUND -> throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    ERROR_LOBBY_NOT_FOUND
            );
            case KICKED -> throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    ERROR_LOBBY_CHAT_KICKED
            );
            case NOT_PARTICIPANT -> throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    ERROR_LOBBY_CHAT_FORBIDDEN
            );
        }
    }

    private ChatMessageDto getReportableChatMessage(String inviteCode, String messageId) {
        try {
            ChatMessageDto chatMessage = lobbyRecentChatMessageFinder.findByMessageId(inviteCode, messageId)
                    .orElseThrow(() -> new ResponseStatusException(
                            HttpStatus.NOT_FOUND,
                            ERROR_CHAT_MESSAGE_NOT_FOUND
                    ));

            validateReportableChatMessage(chatMessage);

            return chatMessage;
        } catch (RecentChatMessageLookupException e) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    ERROR_RECENT_CHAT_LOOKUP_FAILED,
                    e
            );
        }
    }

    private void validateReportableChatMessage(ChatMessageDto chatMessage) {
        if (chatMessage.getMessageId() == null
                || chatMessage.getMessageId().isBlank()
                || chatMessage.getContent() == null
                || chatMessage.getContent().isBlank()
                || chatMessage.getType() != ChatMessageDto.MessageType.CHAT
                || resolveSentAtText(chatMessage) == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, ERROR_INVALID_CHAT_MESSAGE);
        }
    }

    private void validateNotSelfMessage(
            Long reporterId,
            String reporterIdentifier,
            ChatMessageDto chatMessage
    ) {
        boolean sameUserId = chatMessage.getSenderId() != null
                && chatMessage.getSenderId().equals(reporterId);

        boolean sameUserIdentifier = chatMessage.getSender() != null
                && chatMessage.getSender().equals(reporterIdentifier);

        if (sameUserId || sameUserIdentifier) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ERROR_SELF_MESSAGE_REPORT);
        }
    }

    private String normalizeReason(String reason) {
        if (reason == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ERROR_INVALID_REASON);
        }

        String normalizedReason = reason.trim();

        if (normalizedReason.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ERROR_INVALID_REASON);
        }

        return normalizedReason;
    }

    private void validateDuplicatePendingReport(
            Long reporterId,
            Long lobbyId,
            String messageId
    ) {
        boolean duplicated = snapshotRepository.existsPendingReportByReporterAndLobbyAndMessageId(
                reporterId,
                lobbyId,
                messageId,
                ReportTargetType.LOBBY_CHAT_MESSAGE,
                ReportStatus.PENDING
        );

        if (duplicated) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, ERROR_DUPLICATE_REPORT);
        }
    }

    private LobbyChatMessageReportSnapshot toSnapshot(
            Report report,
            ChatMessageDto chatMessage
    ) {
        return LobbyChatMessageReportSnapshot.builder()
                .report(report)
                .messageId(chatMessage.getMessageId())
                .senderIdentifier(chatMessage.getSender())
                .senderId(chatMessage.getSenderId())
                .senderNickname(chatMessage.getSenderNickname())
                .content(chatMessage.getContent())
                .messageType(chatMessage.getType().name())
                .sentAt(parseSentAt(chatMessage))
                .build();
    }

    private LocalDateTime parseSentAt(ChatMessageDto chatMessage) {
        String sentAtText = resolveSentAtText(chatMessage);

        try {
            return Instant.parse(sentAtText)
                    .atOffset(ZoneOffset.UTC)
                    .toLocalDateTime();
        } catch (RuntimeException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, ERROR_INVALID_CHAT_MESSAGE);
        }
    }

    private String resolveSentAtText(ChatMessageDto chatMessage) {
        if (chatMessage.getSentAt() != null && !chatMessage.getSentAt().isBlank()) {
            return chatMessage.getSentAt();
        }

        if (chatMessage.getTimestamp() != null && !chatMessage.getTimestamp().isBlank()) {
            return chatMessage.getTimestamp();
        }

        return null;
    }
}