package io.github.ascrew.monomatbe.domain.report.controller;

import io.github.ascrew.monomatbe.domain.auth.entity.UserType;
import io.github.ascrew.monomatbe.domain.report.dto.LobbyChatMessageReportRequest;
import io.github.ascrew.monomatbe.domain.report.dto.LobbyReportRequest;
import io.github.ascrew.monomatbe.domain.report.dto.LobbyUserReportRequest;
import io.github.ascrew.monomatbe.domain.report.dto.ReportResponse;
import io.github.ascrew.monomatbe.domain.report.entity.ReportStatus;
import io.github.ascrew.monomatbe.domain.report.entity.ReportTargetType;
import io.github.ascrew.monomatbe.domain.report.service.LobbyChatMessageReportService;
import io.github.ascrew.monomatbe.domain.report.service.ReportService;
import io.github.ascrew.monomatbe.global.security.jwt.CustomPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.http.HttpStatus;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReportControllerTest {

    private static final String INVITE_CODE = "ABC123";
    private static final String MESSAGE_ID = "22222222-2222-2222-2222-222222222222";
    private static final Long REPORTER_ID = 1L;
    private static final Long TARGET_USER_ID = 2L;
    private static final Long LOBBY_ID = 10L;
    private static final Long REPORT_ID = 100L;
    private static final String REPORTER_IDENTIFIER = "11111111-1111-1111-1111-111111111111";

    @Mock
    private ReportService reportService;

    @Mock
    private LobbyChatMessageReportService lobbyChatMessageReportService;

    private ReportController reportController;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);

        reportController = new ReportController(
                reportService,
                lobbyChatMessageReportService
        );
    }

    @Test
    @DisplayName("로비 신고 요청을 ReportService로 위임하고 201을 반환한다")
    void reportLobby_delegatesToService() {
        // given
        LobbyReportRequest request = new LobbyReportRequest("로비 신고");
        CustomPrincipal principal = principal();

        ReportResponse expected = reportResponse(
                ReportTargetType.LOBBY,
                LOBBY_ID,
                "로비 신고"
        );

        when(reportService.reportLobby(
                INVITE_CODE,
                REPORTER_ID,
                request
        )).thenReturn(expected);

        // when
        var response = reportController.reportLobby(
                INVITE_CODE,
                request,
                principal
        );

        // then
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isEqualTo(expected);

        verify(reportService).reportLobby(
                INVITE_CODE,
                REPORTER_ID,
                request
        );
    }

    @Test
    @DisplayName("로비 유저 신고 요청을 ReportService로 위임하고 201을 반환한다")
    void reportLobbyUser_delegatesToService() {
        // given
        LobbyUserReportRequest request = new LobbyUserReportRequest("유저 신고");
        CustomPrincipal principal = principal();

        ReportResponse expected = reportResponse(
                ReportTargetType.LOBBY_USER,
                TARGET_USER_ID,
                "유저 신고"
        );

        when(reportService.reportLobbyUser(
                INVITE_CODE,
                REPORTER_ID,
                TARGET_USER_ID,
                request
        )).thenReturn(expected);

        // when
        var response = reportController.reportLobbyUser(
                INVITE_CODE,
                TARGET_USER_ID,
                request,
                principal
        );

        // then
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isEqualTo(expected);

        verify(reportService).reportLobbyUser(
                INVITE_CODE,
                REPORTER_ID,
                TARGET_USER_ID,
                request
        );
    }

    @Test
    @DisplayName("로비 채팅 메시지 신고 요청을 LobbyChatMessageReportService로 위임하고 201을 반환한다")
    void reportLobbyChatMessage_delegatesToService() {
        // given
        LobbyChatMessageReportRequest request =
                new LobbyChatMessageReportRequest("채팅 메시지 신고");
        CustomPrincipal principal = principal();

        ReportResponse expected = reportResponse(
                ReportTargetType.LOBBY_CHAT_MESSAGE,
                LOBBY_ID,
                "채팅 메시지 신고"
        );

        when(lobbyChatMessageReportService.reportLobbyChatMessage(
                INVITE_CODE,
                MESSAGE_ID,
                REPORTER_ID,
                REPORTER_IDENTIFIER,
                request
        )).thenReturn(expected);

        // when
        var response = reportController.reportLobbyChatMessage(
                INVITE_CODE,
                MESSAGE_ID,
                request,
                principal
        );

        // then
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isEqualTo(expected);

        verify(lobbyChatMessageReportService).reportLobbyChatMessage(
                INVITE_CODE,
                MESSAGE_ID,
                REPORTER_ID,
                REPORTER_IDENTIFIER,
                request
        );
    }

    private CustomPrincipal principal() {
        return new CustomPrincipal(
                REPORTER_ID,
                REPORTER_IDENTIFIER,
                UserType.GUEST
        );
    }

    private ReportResponse reportResponse(
            ReportTargetType targetType,
            Long targetId,
            String reason
    ) {
        return ReportResponse.builder()
                .reportId(REPORT_ID)
                .reporterId(REPORTER_ID)
                .lobbyId(LOBBY_ID)
                .targetType(targetType.name())
                .targetId(targetId)
                .reason(reason)
                .status(ReportStatus.PENDING.name())
                .createdAt(LocalDateTime.of(2026, 5, 30, 12, 0))
                .build();
    }
}