package io.github.ascrew.monomatbe.domain.report.service;

import io.github.ascrew.monomatbe.domain.auth.entity.User;
import io.github.ascrew.monomatbe.domain.auth.repository.UserRepository;
import io.github.ascrew.monomatbe.domain.lobby.entity.GameLobby;
import io.github.ascrew.monomatbe.domain.lobby.repository.GameLobbyJpaRepository;
import io.github.ascrew.monomatbe.domain.report.dto.LobbyReportRequest;
import io.github.ascrew.monomatbe.domain.report.dto.LobbyUserReportRequest;
import io.github.ascrew.monomatbe.domain.report.dto.ReportCountResponse;
import io.github.ascrew.monomatbe.domain.report.dto.ReportResponse;
import io.github.ascrew.monomatbe.domain.report.entity.Report;
import io.github.ascrew.monomatbe.domain.report.entity.ReportStatus;
import io.github.ascrew.monomatbe.domain.report.entity.ReportTargetType;
import io.github.ascrew.monomatbe.domain.report.repository.ReportRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * 신고 유스케이스 서비스
 *
 * [책임]
 * - 로비 신고 생성
 * - 로비 내 유저 신고 생성
 * - 신고 사유 정규화
 * - 동일 사용자의 동일 대상 PENDING 중복 신고 방지
 * - 신고 누적 카운트 조회
 *
 * [로비 유저 신고 검증 범위]
 * 현재 Redis 참여자 검증은 userIdentifier 기준으로 제공된다.
 * 이번 신고 API는 targetUserId(Long)를 URL로 받으므로,
 * 이 단계에서는 DB users 존재 여부까지만 검증한다.
 *
 * 추후 userId ↔ userIdentifier 매핑 정책이 명확해지면 Redis 참여자 여부 검증을 추가할 수 있다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReportService {

    // =========================================================
    // 에러 메시지 상수
    // =========================================================

    private static final String ERROR_INVALID_REPORTER =
            "유효하지 않은 인증 정보입니다. 다시 로그인해주세요.";
    private static final String ERROR_REPORTER_NOT_FOUND =
            "신고자를 찾을 수 없습니다.";
    private static final String ERROR_LOBBY_NOT_FOUND =
            "신고 대상 로비를 찾을 수 없습니다.";
    private static final String ERROR_DELETED_LOBBY =
            "삭제된 로비는 신고할 수 없습니다.";
    private static final String ERROR_TARGET_USER_NOT_FOUND =
            "신고 대상 유저를 찾을 수 없습니다.";
    private static final String ERROR_SELF_REPORT =
            "자기 자신은 신고할 수 없습니다.";
    private static final String ERROR_DUPLICATE_REPORT =
            "이미 접수된 신고입니다.";
    private static final String ERROR_INVALID_REASON =
            "신고 사유는 비어 있을 수 없습니다.";

    // =========================================================
    // 로그 메시지 상수
    // =========================================================

    private static final String LOG_LOBBY_REPORT_CREATED =
            "로비 신고 접수 완료 - reportId: {}, reporterId: {}, lobbyId: {}, inviteCode: {}";
    private static final String LOG_LOBBY_USER_REPORT_CREATED =
            "로비 유저 신고 접수 완료 - reportId: {}, reporterId: {}, lobbyId: {}, inviteCode: {}, targetUserId: {}";
    private static final String LOG_DUPLICATE_REPORT =
            "중복 신고 요청 차단 - reporterId: {}, lobbyId: {}, targetType: {}, targetId: {}";

    private final ReportRepository reportRepository;
    private final UserRepository userRepository;
    private final GameLobbyJpaRepository gameLobbyJpaRepository;

    /**
     * 로비 자체 신고를 생성한다.
     *
     * [처리 순서]
     * 1. 신고자 ID 검증
     * 2. 신고자 User 조회
     * 3. inviteCode 기준 로비 조회
     * 4. 삭제된 로비 여부 확인
     * 5. 신고 사유 trim 정규화
     * 6. 동일 사용자의 동일 로비 PENDING 신고 중복 확인
     * 7. Report 저장
     *
     * @param inviteCode 로비 초대 코드
     * @param reporterId 신고자 users.id
     * @param request 신고 요청 DTO
     * @return 생성된 신고 응답
     */
    @Transactional
    public ReportResponse reportLobby(
            String inviteCode,
            Long reporterId,
            LobbyReportRequest request
    ) {
        User reporter = getReporter(reporterId);
        GameLobby lobby = getReportableLobby(inviteCode);
        String reason = normalizeReason(request.reason());

        Long lobbyId = lobby.getId();

        validateDuplicatePendingReport(
                reporter.getId(),
                lobbyId,
                ReportTargetType.LOBBY,
                lobbyId
        );

        Report report = reportRepository.save(Report.builder()
                .reporter(reporter)
                .lobby(lobby)
                .targetType(ReportTargetType.LOBBY)
                .targetId(lobbyId)
                .reason(reason)
                .build());

        log.info(
                LOG_LOBBY_REPORT_CREATED,
                report.getId(),
                reporter.getId(),
                lobbyId,
                inviteCode
        );

        return ReportResponse.from(report);
    }

    /**
     * 로비 내 특정 유저 신고를 생성한다.
     *
     * [처리 순서]
     * 1. 신고자 ID 검증
     * 2. 신고자 User 조회
     * 3. 신고 대상 User 조회
     * 4. 자기 자신 신고 차단
     * 5. inviteCode 기준 로비 조회
     * 6. 삭제된 로비 여부 확인
     * 7. 신고 사유 trim 정규화
     * 8. 동일 사용자의 동일 로비/동일 대상 PENDING 신고 중복 확인
     * 9. Report 저장
     *
     * @param inviteCode 로비 초대 코드
     * @param reporterId 신고자 users.id
     * @param targetUserId 신고 대상 users.id
     * @param request 신고 요청 DTO
     * @return 생성된 신고 응답
     */
    @Transactional
    public ReportResponse reportLobbyUser(
            String inviteCode,
            Long reporterId,
            Long targetUserId,
            LobbyUserReportRequest request
    ) {
        User reporter = getReporter(reporterId);
        User targetUser = getTargetUser(targetUserId);

        if (reporter.getId().equals(targetUser.getId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ERROR_SELF_REPORT);
        }

        GameLobby lobby = getReportableLobby(inviteCode);
        String reason = normalizeReason(request.reason());

        validateDuplicatePendingReport(
                reporter.getId(),
                lobby.getId(),
                ReportTargetType.LOBBY_USER,
                targetUser.getId()
        );

        Report report = reportRepository.save(Report.builder()
                .reporter(reporter)
                .lobby(lobby)
                .targetType(ReportTargetType.LOBBY_USER)
                .targetId(targetUser.getId())
                .reason(reason)
                .build());

        log.info(
                LOG_LOBBY_USER_REPORT_CREATED,
                report.getId(),
                reporter.getId(),
                lobby.getId(),
                inviteCode,
                targetUser.getId()
        );

        return ReportResponse.from(report);
    }

    /**
     * 특정 신고 대상의 PENDING 신고 누적 수를 조회한다.
     *
     * 예:
     * - 특정 로비 신고 누적 수
     * - 특정 유저 신고 누적 수
     *
     * @param targetType 신고 대상 타입
     * @param targetId 신고 대상 ID
     * @return 신고 누적 카운트 응답
     */
    @Transactional(readOnly = true)
    public ReportCountResponse countPendingReportsByTarget(
            ReportTargetType targetType,
            Long targetId
    ) {
        long count = reportRepository.countByTargetTypeAndTargetIdAndStatus(
                targetType,
                targetId,
                ReportStatus.PENDING
        );

        return ReportCountResponse.builder()
                .targetType(targetType.name())
                .targetId(targetId)
                .lobbyId(null)
                .status(ReportStatus.PENDING.name())
                .count(count)
                .build();
    }

    /**
     * 특정 로비에서 발생한 PENDING 신고 누적 수를 조회한다.
     *
     * 로비 자체 신고와 로비 유저 신고를 모두 포함한다.
     *
     * @param lobbyId GAME_LOBBY.id
     * @return 신고 누적 카운트 응답
     */
    @Transactional(readOnly = true)
    public ReportCountResponse countPendingReportsByLobby(Long lobbyId) {
        long count = reportRepository.countByLobbyIdAndStatus(
                lobbyId,
                ReportStatus.PENDING
        );

        return ReportCountResponse.builder()
                .targetType(null)
                .targetId(null)
                .lobbyId(lobbyId)
                .status(ReportStatus.PENDING.name())
                .count(count)
                .build();
    }

    /**
     * 신고자 ID를 검증하고 User 엔티티를 조회한다.
     */
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

    /**
     * 신고 대상 유저를 조회한다.
     */
    private User getTargetUser(Long targetUserId) {
        if (targetUserId == null) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    ERROR_TARGET_USER_NOT_FOUND
            );
        }

        return userRepository.findById(targetUserId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        ERROR_TARGET_USER_NOT_FOUND
                ));
    }

    /**
     * 신고 가능한 로비를 조회한다.
     *
     * [삭제 로비 차단]
     * 로비 폭파 후 Soft Delete된 DB 스냅샷은 신고 대상으로 받지 않는다.
     * 신고는 로비가 존재하는 동안 접수하는 것을 기본 정책으로 둔다.
     */
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

    /**
     * 신고 사유를 정규화한다.
     *
     * DTO의 @NotBlank가 1차 검증을 수행하지만,
     * 서비스가 다른 경로에서 호출될 수 있으므로 최소 방어선을 유지한다.
     */
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

    /**
     * 동일 사용자의 동일 로비/동일 대상 PENDING 신고 중복 여부를 확인한다.
     */
    private void validateDuplicatePendingReport(
            Long reporterId,
            Long lobbyId,
            ReportTargetType targetType,
            Long targetId
    ) {
        boolean duplicated = reportRepository.existsByReporterIdAndLobbyIdAndTargetTypeAndTargetIdAndStatus(
                reporterId,
                lobbyId,
                targetType,
                targetId,
                ReportStatus.PENDING
        );

        if (duplicated) {
            log.info(
                    LOG_DUPLICATE_REPORT,
                    reporterId,
                    lobbyId,
                    targetType,
                    targetId
            );
            throw new ResponseStatusException(HttpStatus.CONFLICT, ERROR_DUPLICATE_REPORT);
        }
    }
}