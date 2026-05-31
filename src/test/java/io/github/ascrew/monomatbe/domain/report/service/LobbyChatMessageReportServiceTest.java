package io.github.ascrew.monomatbe.domain.report.service;

import io.github.ascrew.monomatbe.domain.auth.entity.User;
import io.github.ascrew.monomatbe.domain.auth.entity.UserStatus;
import io.github.ascrew.monomatbe.domain.auth.entity.UserType;
import io.github.ascrew.monomatbe.domain.auth.repository.UserRepository;
import io.github.ascrew.monomatbe.domain.chat.service.LobbyRecentChatMessageFinder;
import io.github.ascrew.monomatbe.domain.chat.service.RecentChatMessageLookupException;
import io.github.ascrew.monomatbe.domain.lobby.LobbyUserAccessStatus;
import io.github.ascrew.monomatbe.domain.lobby.entity.GameLobby;
import io.github.ascrew.monomatbe.domain.lobby.repository.GameLobbyJpaRepository;
import io.github.ascrew.monomatbe.domain.lobby.repository.LobbyRepository;
import io.github.ascrew.monomatbe.domain.report.dto.LobbyChatMessageReportRequest;
import io.github.ascrew.monomatbe.domain.report.entity.LobbyChatMessageReportSnapshot;
import io.github.ascrew.monomatbe.domain.report.entity.Report;
import io.github.ascrew.monomatbe.domain.report.entity.ReportStatus;
import io.github.ascrew.monomatbe.domain.report.entity.ReportTargetType;
import io.github.ascrew.monomatbe.domain.report.repository.LobbyChatMessageReportSnapshotRepository;
import io.github.ascrew.monomatbe.domain.report.repository.ReportRepository;
import io.github.ascrew.monomatbe.global.websocket.dto.ChatMessageDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LobbyChatMessageReportServiceTest {

    private static final String INVITE_CODE = "ABC123";
    private static final String MESSAGE_ID = "22222222-2222-2222-2222-222222222222";
    private static final Long REPORTER_ID = 1L;
    private static final Long SENDER_ID = 2L;
    private static final Long LOBBY_ID = 10L;
    private static final Long REPORT_ID = 100L;
    private static final String REPORTER_IDENTIFIER = "11111111-1111-1111-1111-111111111111";
    private static final String SENDER_IDENTIFIER = "33333333-3333-3333-3333-333333333333";
    private static final String SENDER_NICKNAME = "신고대상";
    private static final String SENT_AT = "2026-05-30T12:00:00.123Z";

    private static final String LOCK_KEY =
            "lock:report:lobby-chat-message:1:10:22222222-2222-2222-2222-222222222222";
    private static final String LOCK_TOKEN = "lock-token";

    @Mock
    private ReportRepository reportRepository;

    @Mock
    private LobbyChatMessageReportSnapshotRepository snapshotRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private GameLobbyJpaRepository gameLobbyJpaRepository;

    @Mock
    private LobbyRepository lobbyRepository;

    @Mock
    private LobbyRecentChatMessageFinder lobbyRecentChatMessageFinder;

    @Mock
    private LobbyChatMessageReportLockManager lockManager;

    private LobbyChatMessageReportService service;

    @BeforeEach
    void setUp() {
        service = new LobbyChatMessageReportService(
                reportRepository,
                snapshotRepository,
                userRepository,
                gameLobbyJpaRepository,
                lobbyRepository,
                lobbyRecentChatMessageFinder,
                lockManager
        );
    }

    @Test
    @DisplayName("로비 채팅 메시지를 신고하면 Report와 Snapshot을 저장하고 트랜잭션 동기화가 없으면 finally에서 lock을 해제한다")
    void reportLobbyChatMessage_success_withoutTransactionSynchronization() {
        // given
        User reporter = createUser(REPORTER_ID, "reporter");
        GameLobby lobby = createLobby(LOBBY_ID, INVITE_CODE, false);
        ChatMessageDto chatMessage = reportableChatMessage();

        when(userRepository.findById(REPORTER_ID)).thenReturn(Optional.of(reporter));
        when(gameLobbyJpaRepository.findByInviteCode(INVITE_CODE)).thenReturn(Optional.of(lobby));
        when(lobbyRepository.getUserAccessStatus(INVITE_CODE, REPORTER_IDENTIFIER))
                .thenReturn(LobbyUserAccessStatus.PARTICIPANT);
        when(lobbyRecentChatMessageFinder.findByMessageId(INVITE_CODE, MESSAGE_ID))
                .thenReturn(Optional.of(chatMessage));
        when(lockManager.tryLock(REPORTER_ID, LOBBY_ID, MESSAGE_ID))
                .thenReturn(Optional.of(reportLock()));
        when(snapshotRepository.existsPendingReportByReporterAndLobbyAndMessageId(
                REPORTER_ID,
                LOBBY_ID,
                MESSAGE_ID,
                ReportTargetType.LOBBY_CHAT_MESSAGE,
                ReportStatus.PENDING
        )).thenReturn(false);
        when(reportRepository.save(any(Report.class))).thenAnswer(invocation -> {
            Report report = invocation.getArgument(0);
            report.prePersist();

            return Report.builder()
                    .id(REPORT_ID)
                    .reporter(report.getReporter())
                    .lobby(report.getLobby())
                    .targetType(report.getTargetType())
                    .targetId(report.getTargetId())
                    .targetReference(report.getTargetReference())
                    .reason(report.getReason())
                    .status(ReportStatus.PENDING)
                    .createdAt(report.getCreatedAt())
                    .build();
        });

        // when
        var response = service.reportLobbyChatMessage(
                INVITE_CODE,
                MESSAGE_ID,
                REPORTER_ID,
                REPORTER_IDENTIFIER,
                new LobbyChatMessageReportRequest("  부적절한 채팅입니다.  ")
        );

        // then
        assertThat(response.reportId()).isEqualTo(REPORT_ID);
        assertThat(response.reporterId()).isEqualTo(REPORTER_ID);
        assertThat(response.lobbyId()).isEqualTo(LOBBY_ID);
        assertThat(response.targetType()).isEqualTo(ReportTargetType.LOBBY_CHAT_MESSAGE.name());
        assertThat(response.targetId()).isEqualTo(LOBBY_ID);
        assertThat(response.targetReference()).isEqualTo(MESSAGE_ID);
        assertThat(response.reason()).isEqualTo("부적절한 채팅입니다.");
        assertThat(response.status()).isEqualTo(ReportStatus.PENDING.name());

        ArgumentCaptor<Report> reportCaptor = ArgumentCaptor.forClass(Report.class);
        verify(reportRepository).save(reportCaptor.capture());

        Report savedReport = reportCaptor.getValue();

        assertThat(savedReport.getReporter()).isEqualTo(reporter);
        assertThat(savedReport.getLobby()).isEqualTo(lobby);
        assertThat(savedReport.getTargetType()).isEqualTo(ReportTargetType.LOBBY_CHAT_MESSAGE);
        assertThat(savedReport.getTargetId()).isEqualTo(LOBBY_ID);
        assertThat(savedReport.getTargetReference()).isEqualTo(MESSAGE_ID);
        assertThat(savedReport.getReason()).isEqualTo("부적절한 채팅입니다.");

        ArgumentCaptor<LobbyChatMessageReportSnapshot> snapshotCaptor =
                ArgumentCaptor.forClass(LobbyChatMessageReportSnapshot.class);

        verify(snapshotRepository).save(snapshotCaptor.capture());

        LobbyChatMessageReportSnapshot snapshot = snapshotCaptor.getValue();

        assertThat(snapshot.getReport().getId()).isEqualTo(REPORT_ID);
        assertThat(snapshot.getMessageId()).isEqualTo(MESSAGE_ID);
        assertThat(snapshot.getSenderIdentifier()).isEqualTo(SENDER_IDENTIFIER);
        assertThat(snapshot.getSenderId()).isEqualTo(SENDER_ID);
        assertThat(snapshot.getSenderNickname()).isEqualTo(SENDER_NICKNAME);
        assertThat(snapshot.getContent()).isEqualTo("신고 대상 메시지");
        assertThat(snapshot.getMessageType()).isEqualTo(ChatMessageDto.MessageType.CHAT.name());
        assertThat(snapshot.getSentAt()).isEqualTo(LocalDateTime.of(
                2026,
                5,
                30,
                12,
                0,
                0,
                123_000_000
        ));

        verify(lockManager).unlock(reportLock());
    }

    @Test
    @DisplayName("트랜잭션 동기화가 활성화되어 있으면 lock 해제를 afterCompletion으로 지연한다")
    void reportLobbyChatMessage_unlocksAfterTransactionCompletionWhenSynchronizationActive() {
        // given
        User reporter = createUser(REPORTER_ID, "reporter");
        GameLobby lobby = createLobby(LOBBY_ID, INVITE_CODE, false);
        ChatMessageDto chatMessage = reportableChatMessage();

        when(userRepository.findById(REPORTER_ID)).thenReturn(Optional.of(reporter));
        when(gameLobbyJpaRepository.findByInviteCode(INVITE_CODE)).thenReturn(Optional.of(lobby));
        when(lobbyRepository.getUserAccessStatus(INVITE_CODE, REPORTER_IDENTIFIER))
                .thenReturn(LobbyUserAccessStatus.PARTICIPANT);
        when(lobbyRecentChatMessageFinder.findByMessageId(INVITE_CODE, MESSAGE_ID))
                .thenReturn(Optional.of(chatMessage));
        when(lockManager.tryLock(REPORTER_ID, LOBBY_ID, MESSAGE_ID))
                .thenReturn(Optional.of(reportLock()));
        when(snapshotRepository.existsPendingReportByReporterAndLobbyAndMessageId(
                REPORTER_ID,
                LOBBY_ID,
                MESSAGE_ID,
                ReportTargetType.LOBBY_CHAT_MESSAGE,
                ReportStatus.PENDING
        )).thenReturn(false);
        when(reportRepository.save(any(Report.class))).thenAnswer(invocation -> {
            Report report = invocation.getArgument(0);
            report.prePersist();

            return Report.builder()
                    .id(REPORT_ID)
                    .reporter(report.getReporter())
                    .lobby(report.getLobby())
                    .targetType(report.getTargetType())
                    .targetId(report.getTargetId())
                    .targetReference(report.getTargetReference())
                    .reason(report.getReason())
                    .status(ReportStatus.PENDING)
                    .createdAt(report.getCreatedAt())
                    .build();
        });

        TransactionSynchronizationManager.initSynchronization();

        try {
            // when
            var response = service.reportLobbyChatMessage(
                    INVITE_CODE,
                    MESSAGE_ID,
                    REPORTER_ID,
                    REPORTER_IDENTIFIER,
                    new LobbyChatMessageReportRequest("신고")
            );

            // then
            assertThat(response.reportId()).isEqualTo(REPORT_ID);

            verify(lockManager, never()).unlock(any());

            assertThat(TransactionSynchronizationManager.getSynchronizations())
                    .hasSize(1);

            TransactionSynchronization synchronization =
                    TransactionSynchronizationManager.getSynchronizations().get(0);

            synchronization.afterCompletion(TransactionSynchronization.STATUS_COMMITTED);

            verify(lockManager).unlock(reportLock());
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    @DisplayName("신고자 ID가 없으면 401을 반환한다")
    void reportLobbyChatMessage_failsWhenReporterIdIsNull() {
        assertThatThrownBy(() -> service.reportLobbyChatMessage(
                INVITE_CODE,
                MESSAGE_ID,
                null,
                REPORTER_IDENTIFIER,
                new LobbyChatMessageReportRequest("신고")
        ))
                .isInstanceOfSatisfying(ResponseStatusException.class, ex ->
                        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED));

        verify(lockManager, never()).tryLock(any(), any(), any());
        verify(reportRepository, never()).save(any());
        verify(snapshotRepository, never()).save(any());
    }

    @Test
    @DisplayName("신고자가 로비 참여자가 아니면 403을 반환한다")
    void reportLobbyChatMessage_failsWhenReporterIsNotParticipant() {
        User reporter = createUser(REPORTER_ID, "reporter");
        GameLobby lobby = createLobby(LOBBY_ID, INVITE_CODE, false);

        when(userRepository.findById(REPORTER_ID)).thenReturn(Optional.of(reporter));
        when(gameLobbyJpaRepository.findByInviteCode(INVITE_CODE)).thenReturn(Optional.of(lobby));
        when(lobbyRepository.getUserAccessStatus(INVITE_CODE, REPORTER_IDENTIFIER))
                .thenReturn(LobbyUserAccessStatus.NOT_PARTICIPANT);

        assertThatThrownBy(() -> service.reportLobbyChatMessage(
                INVITE_CODE,
                MESSAGE_ID,
                REPORTER_ID,
                REPORTER_IDENTIFIER,
                new LobbyChatMessageReportRequest("신고")
        ))
                .isInstanceOfSatisfying(ResponseStatusException.class, ex ->
                        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));

        verify(lobbyRecentChatMessageFinder, never()).findByMessageId(any(), any());
        verify(lockManager, never()).tryLock(any(), any(), any());
        verify(reportRepository, never()).save(any());
        verify(snapshotRepository, never()).save(any());
    }

    @Test
    @DisplayName("강퇴된 사용자는 채팅 메시지를 신고할 수 없다")
    void reportLobbyChatMessage_failsWhenReporterIsKicked() {
        User reporter = createUser(REPORTER_ID, "reporter");
        GameLobby lobby = createLobby(LOBBY_ID, INVITE_CODE, false);

        when(userRepository.findById(REPORTER_ID)).thenReturn(Optional.of(reporter));
        when(gameLobbyJpaRepository.findByInviteCode(INVITE_CODE)).thenReturn(Optional.of(lobby));
        when(lobbyRepository.getUserAccessStatus(INVITE_CODE, REPORTER_IDENTIFIER))
                .thenReturn(LobbyUserAccessStatus.KICKED);

        assertThatThrownBy(() -> service.reportLobbyChatMessage(
                INVITE_CODE,
                MESSAGE_ID,
                REPORTER_ID,
                REPORTER_IDENTIFIER,
                new LobbyChatMessageReportRequest("신고")
        ))
                .isInstanceOfSatisfying(ResponseStatusException.class, ex ->
                        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));

        verify(lobbyRecentChatMessageFinder, never()).findByMessageId(any(), any());
        verify(lockManager, never()).tryLock(any(), any(), any());
        verify(reportRepository, never()).save(any());
        verify(snapshotRepository, never()).save(any());
    }

    @Test
    @DisplayName("존재하지 않는 messageId면 404를 반환한다")
    void reportLobbyChatMessage_failsWhenMessageNotFound() {
        User reporter = createUser(REPORTER_ID, "reporter");
        GameLobby lobby = createLobby(LOBBY_ID, INVITE_CODE, false);

        when(userRepository.findById(REPORTER_ID)).thenReturn(Optional.of(reporter));
        when(gameLobbyJpaRepository.findByInviteCode(INVITE_CODE)).thenReturn(Optional.of(lobby));
        when(lobbyRepository.getUserAccessStatus(INVITE_CODE, REPORTER_IDENTIFIER))
                .thenReturn(LobbyUserAccessStatus.PARTICIPANT);
        when(lobbyRecentChatMessageFinder.findByMessageId(INVITE_CODE, MESSAGE_ID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.reportLobbyChatMessage(
                INVITE_CODE,
                MESSAGE_ID,
                REPORTER_ID,
                REPORTER_IDENTIFIER,
                new LobbyChatMessageReportRequest("신고")
        ))
                .isInstanceOfSatisfying(ResponseStatusException.class, ex ->
                        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));

        verify(lockManager, never()).tryLock(any(), any(), any());
        verify(reportRepository, never()).save(any());
        verify(snapshotRepository, never()).save(any());
    }

    @Test
    @DisplayName("최근 채팅 저장소 조회 실패 시 503을 반환한다")
    void reportLobbyChatMessage_failsWithServiceUnavailableWhenRecentChatLookupFails() {
        User reporter = createUser(REPORTER_ID, "reporter");
        GameLobby lobby = createLobby(LOBBY_ID, INVITE_CODE, false);

        when(userRepository.findById(REPORTER_ID)).thenReturn(Optional.of(reporter));
        when(gameLobbyJpaRepository.findByInviteCode(INVITE_CODE)).thenReturn(Optional.of(lobby));
        when(lobbyRepository.getUserAccessStatus(INVITE_CODE, REPORTER_IDENTIFIER))
                .thenReturn(LobbyUserAccessStatus.PARTICIPANT);
        when(lobbyRecentChatMessageFinder.findByMessageId(INVITE_CODE, MESSAGE_ID))
                .thenThrow(new RecentChatMessageLookupException(
                        "lookup failed",
                        new RuntimeException("redis down")
                ));

        assertThatThrownBy(() -> service.reportLobbyChatMessage(
                INVITE_CODE,
                MESSAGE_ID,
                REPORTER_ID,
                REPORTER_IDENTIFIER,
                new LobbyChatMessageReportRequest("신고")
        ))
                .isInstanceOfSatisfying(ResponseStatusException.class, ex ->
                        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));

        verify(lockManager, never()).tryLock(any(), any(), any());
        verify(reportRepository, never()).save(any());
        verify(snapshotRepository, never()).save(any());
    }

    @Test
    @DisplayName("자기 자신의 채팅 메시지는 신고할 수 없다")
    void reportLobbyChatMessage_failsWhenSelfMessage() {
        User reporter = createUser(REPORTER_ID, "reporter");
        GameLobby lobby = createLobby(LOBBY_ID, INVITE_CODE, false);

        ChatMessageDto selfMessage = ChatMessageDto.builder()
                .messageId(MESSAGE_ID)
                .type(ChatMessageDto.MessageType.CHAT)
                .roomId(INVITE_CODE)
                .sender(REPORTER_IDENTIFIER)
                .senderId(REPORTER_ID)
                .senderNickname("reporter")
                .content("내 메시지")
                .timestamp(SENT_AT)
                .sentAt(SENT_AT)
                .build();

        when(userRepository.findById(REPORTER_ID)).thenReturn(Optional.of(reporter));
        when(gameLobbyJpaRepository.findByInviteCode(INVITE_CODE)).thenReturn(Optional.of(lobby));
        when(lobbyRepository.getUserAccessStatus(INVITE_CODE, REPORTER_IDENTIFIER))
                .thenReturn(LobbyUserAccessStatus.PARTICIPANT);
        when(lobbyRecentChatMessageFinder.findByMessageId(INVITE_CODE, MESSAGE_ID))
                .thenReturn(Optional.of(selfMessage));

        assertThatThrownBy(() -> service.reportLobbyChatMessage(
                INVITE_CODE,
                MESSAGE_ID,
                REPORTER_ID,
                REPORTER_IDENTIFIER,
                new LobbyChatMessageReportRequest("신고")
        ))
                .isInstanceOfSatisfying(ResponseStatusException.class, ex ->
                        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));

        verify(lockManager, never()).tryLock(any(), any(), any());
        verify(reportRepository, never()).save(any());
        verify(snapshotRepository, never()).save(any());
    }

    @Test
    @DisplayName("동일 신고 lock 획득에 실패하면 409를 반환한다")
    void reportLobbyChatMessage_failsWhenDuplicateLockExists() {
        User reporter = createUser(REPORTER_ID, "reporter");
        GameLobby lobby = createLobby(LOBBY_ID, INVITE_CODE, false);

        when(userRepository.findById(REPORTER_ID)).thenReturn(Optional.of(reporter));
        when(gameLobbyJpaRepository.findByInviteCode(INVITE_CODE)).thenReturn(Optional.of(lobby));
        when(lobbyRepository.getUserAccessStatus(INVITE_CODE, REPORTER_IDENTIFIER))
                .thenReturn(LobbyUserAccessStatus.PARTICIPANT);
        when(lobbyRecentChatMessageFinder.findByMessageId(INVITE_CODE, MESSAGE_ID))
                .thenReturn(Optional.of(reportableChatMessage()));
        when(lockManager.tryLock(REPORTER_ID, LOBBY_ID, MESSAGE_ID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.reportLobbyChatMessage(
                INVITE_CODE,
                MESSAGE_ID,
                REPORTER_ID,
                REPORTER_IDENTIFIER,
                new LobbyChatMessageReportRequest("신고")
        ))
                .isInstanceOfSatisfying(ResponseStatusException.class, ex ->
                        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));

        verify(snapshotRepository, never()).existsPendingReportByReporterAndLobbyAndMessageId(
                any(),
                any(),
                any(),
                any(),
                any()
        );
        verify(reportRepository, never()).save(any());
        verify(snapshotRepository, never()).save(any());
        verify(lockManager, never()).unlock(any());
    }

    @Test
    @DisplayName("동일 사용자의 동일 메시지 PENDING 신고가 있으면 409를 반환하고 lock을 해제한다")
    void reportLobbyChatMessage_failsWhenDuplicatePendingReportExists() {
        User reporter = createUser(REPORTER_ID, "reporter");
        GameLobby lobby = createLobby(LOBBY_ID, INVITE_CODE, false);

        when(userRepository.findById(REPORTER_ID)).thenReturn(Optional.of(reporter));
        when(gameLobbyJpaRepository.findByInviteCode(INVITE_CODE)).thenReturn(Optional.of(lobby));
        when(lobbyRepository.getUserAccessStatus(INVITE_CODE, REPORTER_IDENTIFIER))
                .thenReturn(LobbyUserAccessStatus.PARTICIPANT);
        when(lobbyRecentChatMessageFinder.findByMessageId(INVITE_CODE, MESSAGE_ID))
                .thenReturn(Optional.of(reportableChatMessage()));
        when(lockManager.tryLock(REPORTER_ID, LOBBY_ID, MESSAGE_ID))
                .thenReturn(Optional.of(reportLock()));
        when(snapshotRepository.existsPendingReportByReporterAndLobbyAndMessageId(
                REPORTER_ID,
                LOBBY_ID,
                MESSAGE_ID,
                ReportTargetType.LOBBY_CHAT_MESSAGE,
                ReportStatus.PENDING
        )).thenReturn(true);

        assertThatThrownBy(() -> service.reportLobbyChatMessage(
                INVITE_CODE,
                MESSAGE_ID,
                REPORTER_ID,
                REPORTER_IDENTIFIER,
                new LobbyChatMessageReportRequest("신고")
        ))
                .isInstanceOfSatisfying(ResponseStatusException.class, ex ->
                        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));

        verify(reportRepository, never()).save(any());
        verify(snapshotRepository, never()).save(any());
        verify(lockManager).unlock(reportLock());
    }

    @Test
    @DisplayName("DB unique 제약 위반이 발생하면 409를 반환하고 lock을 해제한다")
    void reportLobbyChatMessage_failsWhenDatabaseUniqueConstraintViolated() {
        User reporter = createUser(REPORTER_ID, "reporter");
        GameLobby lobby = createLobby(LOBBY_ID, INVITE_CODE, false);

        when(userRepository.findById(REPORTER_ID)).thenReturn(Optional.of(reporter));
        when(gameLobbyJpaRepository.findByInviteCode(INVITE_CODE)).thenReturn(Optional.of(lobby));
        when(lobbyRepository.getUserAccessStatus(INVITE_CODE, REPORTER_IDENTIFIER))
                .thenReturn(LobbyUserAccessStatus.PARTICIPANT);
        when(lobbyRecentChatMessageFinder.findByMessageId(INVITE_CODE, MESSAGE_ID))
                .thenReturn(Optional.of(reportableChatMessage()));
        when(lockManager.tryLock(REPORTER_ID, LOBBY_ID, MESSAGE_ID))
                .thenReturn(Optional.of(reportLock()));
        when(snapshotRepository.existsPendingReportByReporterAndLobbyAndMessageId(
                REPORTER_ID,
                LOBBY_ID,
                MESSAGE_ID,
                ReportTargetType.LOBBY_CHAT_MESSAGE,
                ReportStatus.PENDING
        )).thenReturn(false);
        when(reportRepository.save(any(Report.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate pending report"));

        assertThatThrownBy(() -> service.reportLobbyChatMessage(
                INVITE_CODE,
                MESSAGE_ID,
                REPORTER_ID,
                REPORTER_IDENTIFIER,
                new LobbyChatMessageReportRequest("신고")
        ))
                .isInstanceOfSatisfying(ResponseStatusException.class, ex ->
                        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));

        verify(snapshotRepository, never()).save(any());
        verify(lockManager).unlock(reportLock());
    }

    @Test
    @DisplayName("sentAt과 timestamp가 모두 없으면 신고할 수 없는 메시지로 처리한다")
    void reportLobbyChatMessage_failsWhenMessageTimestampInvalid() {
        User reporter = createUser(REPORTER_ID, "reporter");
        GameLobby lobby = createLobby(LOBBY_ID, INVITE_CODE, false);

        ChatMessageDto invalidMessage = ChatMessageDto.builder()
                .messageId(MESSAGE_ID)
                .type(ChatMessageDto.MessageType.CHAT)
                .roomId(INVITE_CODE)
                .sender(SENDER_IDENTIFIER)
                .senderId(SENDER_ID)
                .senderNickname(SENDER_NICKNAME)
                .content("신고 대상 메시지")
                .build();

        when(userRepository.findById(REPORTER_ID)).thenReturn(Optional.of(reporter));
        when(gameLobbyJpaRepository.findByInviteCode(INVITE_CODE)).thenReturn(Optional.of(lobby));
        when(lobbyRepository.getUserAccessStatus(INVITE_CODE, REPORTER_IDENTIFIER))
                .thenReturn(LobbyUserAccessStatus.PARTICIPANT);
        when(lobbyRecentChatMessageFinder.findByMessageId(INVITE_CODE, MESSAGE_ID))
                .thenReturn(Optional.of(invalidMessage));

        assertThatThrownBy(() -> service.reportLobbyChatMessage(
                INVITE_CODE,
                MESSAGE_ID,
                REPORTER_ID,
                REPORTER_IDENTIFIER,
                new LobbyChatMessageReportRequest("신고")
        ))
                .isInstanceOfSatisfying(ResponseStatusException.class, ex ->
                        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));

        verify(lockManager, never()).tryLock(any(), any(), any());
        verify(reportRepository, never()).save(any());
        verify(snapshotRepository, never()).save(any());
    }

    @Test
    @DisplayName("CHAT 타입이 아닌 메시지는 신고할 수 없는 메시지로 처리한다")
    void reportLobbyChatMessage_failsWhenMessageTypeIsNotChat() {
        User reporter = createUser(REPORTER_ID, "reporter");
        GameLobby lobby = createLobby(LOBBY_ID, INVITE_CODE, false);

        ChatMessageDto systemMessage = ChatMessageDto.builder()
                .messageId(MESSAGE_ID)
                .type(ChatMessageDto.MessageType.SYSTEM)
                .roomId(INVITE_CODE)
                .sender(SENDER_IDENTIFIER)
                .senderId(SENDER_ID)
                .senderNickname(SENDER_NICKNAME)
                .content("시스템 메시지")
                .timestamp(SENT_AT)
                .sentAt(SENT_AT)
                .build();

        when(userRepository.findById(REPORTER_ID)).thenReturn(Optional.of(reporter));
        when(gameLobbyJpaRepository.findByInviteCode(INVITE_CODE)).thenReturn(Optional.of(lobby));
        when(lobbyRepository.getUserAccessStatus(INVITE_CODE, REPORTER_IDENTIFIER))
                .thenReturn(LobbyUserAccessStatus.PARTICIPANT);
        when(lobbyRecentChatMessageFinder.findByMessageId(INVITE_CODE, MESSAGE_ID))
                .thenReturn(Optional.of(systemMessage));

        assertThatThrownBy(() -> service.reportLobbyChatMessage(
                INVITE_CODE,
                MESSAGE_ID,
                REPORTER_ID,
                REPORTER_IDENTIFIER,
                new LobbyChatMessageReportRequest("신고")
        ))
                .isInstanceOfSatisfying(ResponseStatusException.class, ex ->
                        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));

        verify(lockManager, never()).tryLock(any(), any(), any());
        verify(reportRepository, never()).save(any());
        verify(snapshotRepository, never()).save(any());
    }

    private LobbyChatMessageReportLock reportLock() {
        return new LobbyChatMessageReportLock(
                LOCK_KEY,
                LOCK_TOKEN,
                REPORTER_ID,
                LOBBY_ID,
                MESSAGE_ID
        );
    }

    private ChatMessageDto reportableChatMessage() {
        return ChatMessageDto.builder()
                .messageId(MESSAGE_ID)
                .type(ChatMessageDto.MessageType.CHAT)
                .roomId(INVITE_CODE)
                .sender(SENDER_IDENTIFIER)
                .senderId(SENDER_ID)
                .senderNickname(SENDER_NICKNAME)
                .content("신고 대상 메시지")
                .timestamp(SENT_AT)
                .sentAt(SENT_AT)
                .build();
    }

    private User createUser(Long id, String username) {
        return User.builder()
                .id(id)
                .username(username)
                .userType(UserType.GUEST)
                .status(UserStatus.ACTIVE)
                .build();
    }

    private GameLobby createLobby(Long id, String inviteCode, boolean isDeleted) {
        User host = createUser(99L, "host");

        return GameLobby.builder()
                .id(id)
                .host(host)
                .inviteCode(inviteCode)
                .title("테스트 로비")
                .maxPlayers(8)
                .questionCount(5)
                .timeLimitSeconds(30)
                .isPrivate(false)
                .isDeleted(isDeleted)
                .build();
    }
}