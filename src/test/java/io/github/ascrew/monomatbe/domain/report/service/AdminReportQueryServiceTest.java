package io.github.ascrew.monomatbe.domain.report.service;

import io.github.ascrew.monomatbe.domain.auth.entity.User;
import io.github.ascrew.monomatbe.domain.auth.entity.UserRole;
import io.github.ascrew.monomatbe.domain.auth.entity.UserStatus;
import io.github.ascrew.monomatbe.domain.auth.entity.UserType;
import io.github.ascrew.monomatbe.domain.lobby.entity.GameLobby;
import io.github.ascrew.monomatbe.domain.report.entity.LobbyChatMessageReportSnapshot;
import io.github.ascrew.monomatbe.domain.report.entity.Report;
import io.github.ascrew.monomatbe.domain.report.entity.ReportStatus;
import io.github.ascrew.monomatbe.domain.report.entity.ReportTargetType;
import io.github.ascrew.monomatbe.domain.report.repository.LobbyChatMessageReportSnapshotRepository;
import io.github.ascrew.monomatbe.domain.report.repository.ReportRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminReportQueryServiceTest {

    private static final Long REPORT_ID = 1L;

    @Mock
    private ReportRepository reportRepository;

    @Mock
    private LobbyChatMessageReportSnapshotRepository chatMessageReportSnapshotRepository;

    private AdminReportQueryService adminReportQueryService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);

        adminReportQueryService = new AdminReportQueryService(
                reportRepository,
                chatMessageReportSnapshotRepository
        );
    }

    @Test
    @DisplayName("targetType과 status가 모두 있으면 두 조건으로 신고 목록을 조회한다")
    void getReports_withTargetTypeAndStatus() {
        // given
        Report report = report(
                ReportTargetType.LOBBY_USER,
                30L,
                null,
                ReportStatus.PENDING
        );

        when(reportRepository.findByTargetTypeAndStatus(
                eq(ReportTargetType.LOBBY_USER),
                eq(ReportStatus.PENDING),
                any(Pageable.class)
        )).thenReturn(new PageImpl<>(List.of(report)));

        // when
        var response = adminReportQueryService.getReports(
                ReportTargetType.LOBBY_USER,
                ReportStatus.PENDING,
                0,
                20
        );

        // then
        assertThat(response.items()).hasSize(1);
        assertThat(response.page()).isEqualTo(0);
        assertThat(response.size()).isEqualTo(20);
        assertThat(response.hasNext()).isFalse();

        var item = response.items().getFirst();
        assertThat(item.reportId()).isEqualTo(REPORT_ID);
        assertThat(item.targetType()).isEqualTo(ReportTargetType.LOBBY_USER);
        assertThat(item.status()).isEqualTo(ReportStatus.PENDING);

        verify(reportRepository).findByTargetTypeAndStatus(
                eq(ReportTargetType.LOBBY_USER),
                eq(ReportStatus.PENDING),
                any(Pageable.class)
        );
    }

    @Test
    @DisplayName("필터가 없으면 전체 신고 목록을 조회한다")
    void getReports_withoutFilters() {
        // given
        Report report = report(
                ReportTargetType.LOBBY,
                20L,
                null,
                ReportStatus.PENDING
        );

        when(reportRepository.findAllBy(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(report)));

        // when
        var response = adminReportQueryService.getReports(
                null,
                null,
                null,
                null
        );

        // then
        assertThat(response.items()).hasSize(1);
        assertThat(response.page()).isEqualTo(0);
        assertThat(response.size()).isEqualTo(20);

        verify(reportRepository).findAllBy(any(Pageable.class));
    }

    @Test
    @DisplayName("page가 음수이면 400 예외를 던진다")
    void getReports_negativePage_throwsBadRequest() {
        assertThatThrownBy(() -> adminReportQueryService.getReports(
                null,
                null,
                -1,
                20
        ))
                .isInstanceOf(ResponseStatusException.class)
                .extracting("statusCode")
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("size가 허용 범위를 벗어나면 400 예외를 던진다")
    void getReports_invalidSize_throwsBadRequest() {
        assertThatThrownBy(() -> adminReportQueryService.getReports(
                null,
                null,
                0,
                101
        ))
                .isInstanceOf(ResponseStatusException.class)
                .extracting("statusCode")
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("신고 상세 조회 시 공통 신고 정보를 반환한다")
    void getReportDetail_returnsDetail() {
        // given
        Report report = report(
                ReportTargetType.LOBBY_USER,
                30L,
                null,
                ReportStatus.PENDING
        );

        when(reportRepository.findDetailById(REPORT_ID))
                .thenReturn(Optional.of(report));

        // when
        var response = adminReportQueryService.getReportDetail(REPORT_ID);

        // then
        assertThat(response.reportId()).isEqualTo(REPORT_ID);
        assertThat(response.reporterId()).isEqualTo(10L);
        assertThat(response.lobbyId()).isEqualTo(20L);
        assertThat(response.targetType()).isEqualTo(ReportTargetType.LOBBY_USER);
        assertThat(response.status()).isEqualTo(ReportStatus.PENDING);
        assertThat(response.chatMessageSnapshot()).isNull();
    }

    @Test
    @DisplayName("채팅 메시지 신고 상세 조회 시 스냅샷을 함께 반환한다")
    void getReportDetail_chatMessageReport_returnsSnapshot() {
        // given
        Report report = report(
                ReportTargetType.LOBBY_CHAT_MESSAGE,
                20L,
                "message-1",
                ReportStatus.PENDING
        );

        LobbyChatMessageReportSnapshot snapshot = LobbyChatMessageReportSnapshot.builder()
                .id(100L)
                .report(report)
                .messageId("message-1")
                .senderIdentifier("sender-uuid")
                .senderId(30L)
                .senderNickname("sender")
                .content("신고 대상 메시지")
                .messageType("CHAT")
                .sentAt(LocalDateTime.of(2026, 5, 31, 12, 0))
                .createdAt(LocalDateTime.of(2026, 5, 31, 12, 1))
                .build();

        when(reportRepository.findDetailById(REPORT_ID))
                .thenReturn(Optional.of(report));
        when(chatMessageReportSnapshotRepository.findByReportId(REPORT_ID))
                .thenReturn(Optional.of(snapshot));

        // when
        var response = adminReportQueryService.getReportDetail(REPORT_ID);

        // then
        assertThat(response.reportId()).isEqualTo(REPORT_ID);
        assertThat(response.chatMessageSnapshot()).isNotNull();
        assertThat(response.chatMessageSnapshot().snapshotId()).isEqualTo(100L);
        assertThat(response.chatMessageSnapshot().messageId()).isEqualTo("message-1");
        assertThat(response.chatMessageSnapshot().content()).isEqualTo("신고 대상 메시지");
    }

    @Test
    @DisplayName("존재하지 않는 신고 상세 조회 시 404 예외를 던진다")
    void getReportDetail_notFound_throwsNotFound() {
        // given
        when(reportRepository.findDetailById(REPORT_ID))
                .thenReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> adminReportQueryService.getReportDetail(REPORT_ID))
                .isInstanceOf(ResponseStatusException.class)
                .extracting("statusCode")
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    private Report report(
            ReportTargetType targetType,
            Long targetId,
            String targetReference,
            ReportStatus status
    ) {
        return Report.builder()
                .id(REPORT_ID)
                .reporter(reporter())
                .lobby(lobby())
                .targetType(targetType)
                .targetId(targetId)
                .targetReference(targetReference)
                .reason("신고 사유")
                .status(status)
                .createdAt(LocalDateTime.of(2026, 5, 31, 12, 0))
                .resolvedAt(null)
                .build();
    }

    private User reporter() {
        return User.builder()
                .id(10L)
                .username("reporter")
                .userType(UserType.REGISTERED)
                .status(UserStatus.ACTIVE)
                .role(UserRole.USER)
                .build();
    }

    private GameLobby lobby() {
        return GameLobby.builder()
                .id(20L)
                .host(reporter())
                .inviteCode("ABC123")
                .title("신고 테스트 로비")
                .maxPlayers(8)
                .questionCount(10)
                .timeLimitSeconds(30)
                .isPrivate(false)
                .build();
    }
}