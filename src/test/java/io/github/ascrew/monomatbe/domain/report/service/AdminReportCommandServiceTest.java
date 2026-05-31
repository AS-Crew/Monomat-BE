package io.github.ascrew.monomatbe.domain.report.service;

import io.github.ascrew.monomatbe.domain.auth.entity.User;
import io.github.ascrew.monomatbe.domain.auth.entity.UserRole;
import io.github.ascrew.monomatbe.domain.auth.entity.UserStatus;
import io.github.ascrew.monomatbe.domain.auth.entity.UserType;
import io.github.ascrew.monomatbe.domain.lobby.entity.GameLobby;
import io.github.ascrew.monomatbe.domain.report.entity.Report;
import io.github.ascrew.monomatbe.domain.report.entity.ReportStatus;
import io.github.ascrew.monomatbe.domain.report.entity.ReportTargetType;
import io.github.ascrew.monomatbe.domain.report.event.ReportResolvedEvent;
import io.github.ascrew.monomatbe.domain.report.repository.ReportRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminReportCommandServiceTest {

    private static final Long REPORT_ID = 1L;

    @Mock
    private ReportRepository reportRepository;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    private AdminReportCommandService adminReportCommandService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);

        adminReportCommandService = new AdminReportCommandService(
                reportRepository,
                eventPublisher
        );
    }

    @Test
    @DisplayName("PENDING 신고를 RESOLVED 상태로 처리하고 ReportResolvedEvent를 발행한다")
    void updateReportStatus_resolved() {
        // given
        Report report = report(ReportStatus.PENDING);

        when(reportRepository.findByIdForUpdate(REPORT_ID))
                .thenReturn(Optional.of(report));

        // when
        adminReportCommandService.updateReportStatus(
                REPORT_ID,
                ReportStatus.RESOLVED
        );

        // then
        assertThat(report.getStatus()).isEqualTo(ReportStatus.RESOLVED);
        assertThat(report.getResolvedAt()).isNotNull();

        ArgumentCaptor<ReportResolvedEvent> eventCaptor =
                ArgumentCaptor.forClass(ReportResolvedEvent.class);

        verify(eventPublisher).publishEvent(eventCaptor.capture());

        ReportResolvedEvent event = eventCaptor.getValue();

        assertThat(event.reportId()).isEqualTo(REPORT_ID);
        assertThat(event.reporterId()).isEqualTo(10L);
        assertThat(event.lobbyId()).isEqualTo(20L);
        assertThat(event.targetType()).isEqualTo(ReportTargetType.LOBBY);
        assertThat(event.targetId()).isEqualTo(20L);
        assertThat(event.targetReference()).isNull();
    }

    @Test
    @DisplayName("PENDING 신고를 DISMISSED 상태로 처리한다")
    void updateReportStatus_dismissed() {
        // given
        Report report = report(ReportStatus.PENDING);

        when(reportRepository.findByIdForUpdate(REPORT_ID))
                .thenReturn(Optional.of(report));

        // when
        adminReportCommandService.updateReportStatus(
                REPORT_ID,
                ReportStatus.DISMISSED
        );

        // then
        assertThat(report.getStatus()).isEqualTo(ReportStatus.DISMISSED);
        assertThat(report.getResolvedAt()).isNotNull();

        verify(eventPublisher, never()).publishEvent(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("PENDING으로 상태 변경 요청 시 400 예외를 던진다")
    void updateReportStatus_pending_throwsBadRequest() {
        assertThatThrownBy(() -> adminReportCommandService.updateReportStatus(
                REPORT_ID,
                ReportStatus.PENDING
        ))
                .isInstanceOf(ResponseStatusException.class)
                .extracting("statusCode")
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("존재하지 않는 신고 처리 시 404 예외를 던진다")
    void updateReportStatus_notFound_throwsNotFound() {
        // given
        when(reportRepository.findByIdForUpdate(REPORT_ID))
                .thenReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> adminReportCommandService.updateReportStatus(
                REPORT_ID,
                ReportStatus.RESOLVED
        ))
                .isInstanceOf(ResponseStatusException.class)
                .extracting("statusCode")
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("이미 처리된 신고를 다시 처리하면 409 예외를 던진다")
    void updateReportStatus_alreadyProcessed_throwsConflict() {
        // given
        Report report = report(ReportStatus.RESOLVED);

        when(reportRepository.findByIdForUpdate(REPORT_ID))
                .thenReturn(Optional.of(report));

        // when & then
        assertThatThrownBy(() -> adminReportCommandService.updateReportStatus(
                REPORT_ID,
                ReportStatus.DISMISSED
        ))
                .isInstanceOf(ResponseStatusException.class)
                .extracting("statusCode")
                .isEqualTo(HttpStatus.CONFLICT);
    }

    private Report report(ReportStatus status) {
        return Report.builder()
                .id(REPORT_ID)
                .reporter(user())
                .lobby(lobby())
                .targetType(ReportTargetType.LOBBY)
                .targetId(20L)
                .reason("신고 사유")
                .status(status)
                .createdAt(LocalDateTime.of(2026, 5, 31, 12, 0))
                .resolvedAt(status == ReportStatus.PENDING
                        ? null
                        : LocalDateTime.of(2026, 5, 31, 12, 10))
                .build();
    }

    private User user() {
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
                .host(user())
                .inviteCode("ABC123")
                .title("신고 테스트 로비")
                .maxPlayers(8)
                .questionCount(10)
                .timeLimitSeconds(30)
                .isPrivate(false)
                .build();
    }
}