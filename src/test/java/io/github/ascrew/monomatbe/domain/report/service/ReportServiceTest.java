package io.github.ascrew.monomatbe.domain.report.service;

import io.github.ascrew.monomatbe.domain.auth.entity.User;
import io.github.ascrew.monomatbe.domain.auth.entity.UserStatus;
import io.github.ascrew.monomatbe.domain.auth.entity.UserType;
import io.github.ascrew.monomatbe.domain.auth.repository.UserRepository;
import io.github.ascrew.monomatbe.domain.lobby.entity.GameLobby;
import io.github.ascrew.monomatbe.domain.lobby.repository.GameLobbyJpaRepository;
import io.github.ascrew.monomatbe.domain.report.config.ReportPolicyProperties;
import io.github.ascrew.monomatbe.domain.report.dto.LobbyReportRequest;
import io.github.ascrew.monomatbe.domain.report.dto.LobbyUserReportRequest;
import io.github.ascrew.monomatbe.domain.report.entity.Report;
import io.github.ascrew.monomatbe.domain.report.entity.ReportStatus;
import io.github.ascrew.monomatbe.domain.report.entity.ReportTargetType;
import io.github.ascrew.monomatbe.domain.report.repository.ReportRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ReportService 단위 테스트
 *
 * [검증 범위]
 * - 신고 생성 성공
 * - 중복 PENDING 신고 차단
 * - 자기 자신 신고 차단
 * - 신고 사유 정규화 및 공백 차단
 * - 신고 누적 카운트 조회
 * - 신고 임계값 정책 판단
 */
@ExtendWith(MockitoExtension.class)
class ReportServiceTest {

    private static final String INVITE_CODE = "ABC123";

    @Mock
    private ReportRepository reportRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private GameLobbyJpaRepository gameLobbyJpaRepository;

    private ReportPolicyProperties reportPolicyProperties;

    private ReportService reportService;

    @BeforeEach
    void setUp() {
        reportPolicyProperties = new ReportPolicyProperties();
        reportPolicyProperties.setLobbyReviewThreshold(3);
        reportPolicyProperties.setAutoPrivateEnabled(false);

        reportService = new ReportService(
                reportRepository,
                userRepository,
                gameLobbyJpaRepository,
                reportPolicyProperties
        );
    }

    @Test
    void reportLobby_success() {
        User reporter = createUser(1L, "reporter");
        GameLobby lobby = createLobby(10L, INVITE_CODE, false);

        when(userRepository.findById(1L)).thenReturn(Optional.of(reporter));
        when(gameLobbyJpaRepository.findByInviteCode(INVITE_CODE)).thenReturn(Optional.of(lobby));
        when(reportRepository.existsByReporterIdAndLobbyIdAndTargetTypeAndTargetIdAndStatus(
                1L,
                10L,
                ReportTargetType.LOBBY,
                10L,
                ReportStatus.PENDING
        )).thenReturn(false);
        when(reportRepository.save(any(Report.class))).thenAnswer(invocation -> {
            Report report = invocation.getArgument(0);
            report.prePersist();

            return Report.builder()
                    .id(100L)
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

        var response = reportService.reportLobby(
                INVITE_CODE,
                1L,
                new LobbyReportRequest("  부적절한 로비 제목입니다.  ")
        );

        assertThat(response.reportId()).isEqualTo(100L);
        assertThat(response.reporterId()).isEqualTo(1L);
        assertThat(response.lobbyId()).isEqualTo(10L);
        assertThat(response.targetType()).isEqualTo(ReportTargetType.LOBBY.name());
        assertThat(response.targetId()).isEqualTo(10L);
        assertThat(response.targetReference()).isNull();
        assertThat(response.reason()).isEqualTo("부적절한 로비 제목입니다.");
        assertThat(response.status()).isEqualTo(ReportStatus.PENDING.name());

        verify(reportRepository).save(any(Report.class));
    }

    @Test
    void reportLobby_duplicatePendingReport_throws409() {
        User reporter = createUser(1L, "reporter");
        GameLobby lobby = createLobby(10L, INVITE_CODE, false);

        when(userRepository.findById(1L)).thenReturn(Optional.of(reporter));
        when(gameLobbyJpaRepository.findByInviteCode(INVITE_CODE)).thenReturn(Optional.of(lobby));
        when(reportRepository.existsByReporterIdAndLobbyIdAndTargetTypeAndTargetIdAndStatus(
                1L,
                10L,
                ReportTargetType.LOBBY,
                10L,
                ReportStatus.PENDING
        )).thenReturn(true);

        assertThatThrownBy(() -> reportService.reportLobby(
                INVITE_CODE,
                1L,
                new LobbyReportRequest("중복 신고")
        ))
                .isInstanceOfSatisfying(ResponseStatusException.class, ex ->
                        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.CONFLICT))
                .hasMessageContaining("이미 접수된 신고입니다.");

        verify(reportRepository, never()).save(any());
    }

    @Test
    void reportLobbyUser_success() {
        User reporter = createUser(1L, "reporter");
        User targetUser = createUser(2L, "target");
        GameLobby lobby = createLobby(10L, INVITE_CODE, false);

        when(userRepository.findById(1L)).thenReturn(Optional.of(reporter));
        when(userRepository.findById(2L)).thenReturn(Optional.of(targetUser));
        when(gameLobbyJpaRepository.findByInviteCode(INVITE_CODE)).thenReturn(Optional.of(lobby));
        when(reportRepository.existsByReporterIdAndLobbyIdAndTargetTypeAndTargetIdAndStatus(
                1L,
                10L,
                ReportTargetType.LOBBY_USER,
                2L,
                ReportStatus.PENDING
        )).thenReturn(false);
        when(reportRepository.save(any(Report.class))).thenAnswer(invocation -> {
            Report report = invocation.getArgument(0);
            report.prePersist();

            return Report.builder()
                    .id(101L)
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

        var response = reportService.reportLobbyUser(
                INVITE_CODE,
                1L,
                2L,
                new LobbyUserReportRequest("채팅 도배")
        );

        assertThat(response.reportId()).isEqualTo(101L);
        assertThat(response.reporterId()).isEqualTo(1L);
        assertThat(response.lobbyId()).isEqualTo(10L);
        assertThat(response.targetType()).isEqualTo(ReportTargetType.LOBBY_USER.name());
        assertThat(response.targetId()).isEqualTo(2L);
        assertThat(response.targetReference()).isNull();
        assertThat(response.reason()).isEqualTo("채팅 도배");
        assertThat(response.status()).isEqualTo(ReportStatus.PENDING.name());

        verify(reportRepository).save(any(Report.class));
    }

    @Test
    void reportLobbyUser_selfReport_throws400() {
        User reporter = createUser(1L, "reporter");

        when(userRepository.findById(1L)).thenReturn(Optional.of(reporter));

        assertThatThrownBy(() -> reportService.reportLobbyUser(
                INVITE_CODE,
                1L,
                1L,
                new LobbyUserReportRequest("자기 자신 신고")
        ))
                .isInstanceOfSatisfying(ResponseStatusException.class, ex ->
                        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST))
                .hasMessageContaining("자기 자신은 신고할 수 없습니다.");

        verify(gameLobbyJpaRepository, never()).findByInviteCode(any());
        verify(reportRepository, never()).save(any());
    }

    @Test
    void reportLobby_blankReason_throws400() {
        User reporter = createUser(1L, "reporter");
        GameLobby lobby = createLobby(10L, INVITE_CODE, false);

        when(userRepository.findById(1L)).thenReturn(Optional.of(reporter));
        when(gameLobbyJpaRepository.findByInviteCode(INVITE_CODE)).thenReturn(Optional.of(lobby));

        assertThatThrownBy(() -> reportService.reportLobby(
                INVITE_CODE,
                1L,
                new LobbyReportRequest("   ")
        ))
                .isInstanceOfSatisfying(ResponseStatusException.class, ex ->
                        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST))
                .hasMessageContaining("신고 사유는 비어 있을 수 없습니다.");

        verify(reportRepository, never()).save(any());
    }

    @Test
    void reportLobby_deletedLobby_throws409() {
        User reporter = createUser(1L, "reporter");
        GameLobby deletedLobby = createLobby(10L, INVITE_CODE, true);

        when(userRepository.findById(1L)).thenReturn(Optional.of(reporter));
        when(gameLobbyJpaRepository.findByInviteCode(INVITE_CODE)).thenReturn(Optional.of(deletedLobby));

        assertThatThrownBy(() -> reportService.reportLobby(
                INVITE_CODE,
                1L,
                new LobbyReportRequest("삭제된 로비 신고")
        ))
                .isInstanceOfSatisfying(ResponseStatusException.class, ex ->
                        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.CONFLICT))
                .hasMessageContaining("삭제된 로비는 신고할 수 없습니다.");

        verify(reportRepository, never()).save(any());
    }

    @Test
    void countPendingReportsByTarget_success() {
        when(reportRepository.countByTargetTypeAndTargetIdAndStatus(
                ReportTargetType.LOBBY,
                10L,
                ReportStatus.PENDING
        )).thenReturn(2L);

        var response = reportService.countPendingReportsByTarget(
                ReportTargetType.LOBBY,
                10L
        );

        assertThat(response.targetType()).isEqualTo(ReportTargetType.LOBBY.name());
        assertThat(response.targetId()).isEqualTo(10L);
        assertThat(response.status()).isEqualTo(ReportStatus.PENDING.name());
        assertThat(response.count()).isEqualTo(2L);
    }

    @Test
    void evaluateLobbyModerationPolicy_underThreshold_reviewNotRequired() {
        when(reportRepository.countByLobbyIdAndStatus(10L, ReportStatus.PENDING))
                .thenReturn(2L);

        var response = reportService.evaluateLobbyModerationPolicy(10L);

        assertThat(response.lobbyId()).isEqualTo(10L);
        assertThat(response.pendingReportCount()).isEqualTo(2L);
        assertThat(response.reviewThreshold()).isEqualTo(3);
        assertThat(response.reviewRequired()).isFalse();
        assertThat(response.autoPrivateEnabled()).isFalse();
        assertThat(response.autoPrivateCandidate()).isFalse();
    }

    @Test
    void evaluateLobbyModerationPolicy_overThreshold_reviewRequired() {
        when(reportRepository.countByLobbyIdAndStatus(10L, ReportStatus.PENDING))
                .thenReturn(3L);

        var response = reportService.evaluateLobbyModerationPolicy(10L);

        assertThat(response.lobbyId()).isEqualTo(10L);
        assertThat(response.pendingReportCount()).isEqualTo(3L);
        assertThat(response.reviewThreshold()).isEqualTo(3);
        assertThat(response.reviewRequired()).isTrue();
        assertThat(response.autoPrivateEnabled()).isFalse();
        assertThat(response.autoPrivateCandidate()).isFalse();
    }

    @Test
    void evaluateLobbyModerationPolicy_autoPrivateCandidate_whenEnabledAndOverThreshold() {
        reportPolicyProperties.setAutoPrivateEnabled(true);

        when(reportRepository.countByLobbyIdAndStatus(10L, ReportStatus.PENDING))
                .thenReturn(5L);

        var response = reportService.evaluateLobbyModerationPolicy(10L);

        assertThat(response.lobbyId()).isEqualTo(10L);
        assertThat(response.pendingReportCount()).isEqualTo(5L);
        assertThat(response.reviewThreshold()).isEqualTo(3);
        assertThat(response.reviewRequired()).isTrue();
        assertThat(response.autoPrivateEnabled()).isTrue();
        assertThat(response.autoPrivateCandidate()).isTrue();
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