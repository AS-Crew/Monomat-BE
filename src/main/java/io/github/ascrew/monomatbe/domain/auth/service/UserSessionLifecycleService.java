package io.github.ascrew.monomatbe.domain.auth.service;

import io.github.ascrew.monomatbe.domain.auth.entity.UserSession;
import io.github.ascrew.monomatbe.domain.auth.entity.UserSessionStatus;
import io.github.ascrew.monomatbe.domain.auth.entity.UserType;
import io.github.ascrew.monomatbe.domain.auth.repository.UserSessionRepository;
import io.github.ascrew.monomatbe.global.constant.RedisKeys;
import io.github.ascrew.monomatbe.global.websocket.event.UserSessionRevokedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class UserSessionLifecycleService {

    private final UserSessionRepository userSessionRepository;
    private final StringRedisTemplate redisTemplate;
    private final ApplicationEventPublisher eventPublisher;

    @Value("${auth.session.max-active-per-user-registered:1}")
    private int maxActivePerRegisteredUser;

    @Value("${auth.session.max-active-per-user-guest:2}")
    private int maxActivePerGuestUser;

    @Value("${auth.session.inactive-retention-days:7}")
    private long inactiveRetentionDays;

    @Transactional
    public void enforceActiveSessionLimit(Long userId, UserType userType, String currentSessionId, LocalDateTime now) {
        List<UserSession> activeSessions =
                userSessionRepository.findByUser_IdAndStatusOrderByCreatedAtAsc(userId, UserSessionStatus.ACTIVE);

        int maxActivePerUser = resolveMaxActiveSessionCount(userType);
        if (activeSessions.size() <= maxActivePerUser) {
            return;
        }

        int overflow = activeSessions.size() - maxActivePerUser;
        List<UserSession> targets = activeSessions.stream()
                .filter(session -> !session.getSessionId().equals(currentSessionId))
                .limit(overflow)
                .toList();

        if (targets.isEmpty()) {
            return;
        }

        targets.forEach(session -> session.markRevoked(now));
        registerAfterCommitSessionDelete(targets.stream().map(UserSession::getSessionId).toList());
    }

    @Transactional
    public void revokeAllActiveSessions(Long userId, LocalDateTime now) {
        List<UserSession> activeSessions =
                userSessionRepository.findByUser_IdAndStatusOrderByCreatedAtAsc(userId, UserSessionStatus.ACTIVE);
        if (activeSessions.isEmpty()) {
            return;
        }

        List<String> revokedSessionIds = activeSessions.stream().map(UserSession::getSessionId).toList();

        activeSessions.forEach(session -> session.markRevoked(now));
        registerAfterCommitSessionDelete(revokedSessionIds);
        // Redis active 마커 삭제(위 등록) 이후 실행되도록 뒤에 등록한다.
        // 이미 구독된 기존 WebSocket 세션이 브로드캐스트를 계속 받지 않도록, 해당 사용자에게 종료 알림을 보낸다. (#204)
        registerAfterCommitSessionRevokedEvent(revokedSessionIds);
    }

    @Transactional
    public void markSessionLogout(Long userId, String sessionId, LocalDateTime now) {
        userSessionRepository.findBySessionIdAndStatus(sessionId, UserSessionStatus.ACTIVE)
                .filter(session -> session.getUser().getId().equals(userId))
                .ifPresent(session -> {
                    session.markLogout(now);
                    registerAfterCommitSessionDelete(List.of(session.getSessionId()));
                });
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markSessionRevokedCompensating(String sessionId, LocalDateTime now) {
        userSessionRepository.findBySessionId(sessionId).ifPresent(session -> {
            session.markRevoked(now);
            redisTemplate.delete(RedisKeys.refreshTokenKey(sessionId));
            redisTemplate.delete(RedisKeys.activeSessionKey(sessionId));
        });
    }

    @Transactional
    public void expireAndCleanupSessions(LocalDateTime now) {
        List<UserSession> expiredActiveSessions =
                userSessionRepository.findByStatusAndExpiresAtBefore(UserSessionStatus.ACTIVE, now);
        if (!expiredActiveSessions.isEmpty()) {
            expiredActiveSessions.forEach(session -> session.markExpired(now));
            registerAfterCommitSessionDelete(expiredActiveSessions.stream().map(UserSession::getSessionId).toList());
        }

        Set<UserSessionStatus> inactiveStatuses =
                EnumSet.of(UserSessionStatus.LOGOUT, UserSessionStatus.EXPIRED, UserSessionStatus.REVOKED);
        LocalDateTime retentionThreshold = now.minusDays(inactiveRetentionDays);
        List<UserSession> cleanupTargets =
                userSessionRepository.findByStatusInAndUpdatedAtBefore(inactiveStatuses, retentionThreshold);
        if (!cleanupTargets.isEmpty()) {
            registerAfterCommitSessionDelete(cleanupTargets.stream().map(UserSession::getSessionId).toList());
            userSessionRepository.deleteAllInBatch(cleanupTargets);
        }
    }

    private int resolveMaxActiveSessionCount(UserType userType) {
        if (userType == UserType.GUEST) {
            return maxActivePerGuestUser;
        }
        return maxActivePerRegisteredUser;
    }

    private void registerAfterCommitSessionDelete(List<String> sessionIds) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                sessionIds.forEach(sessionId -> {
                    redisTemplate.delete(RedisKeys.refreshTokenKey(sessionId));
                    redisTemplate.delete(RedisKeys.activeSessionKey(sessionId));
                });
            }
        });
    }

    /**
     * 커밋 이후 revoke된 세션 사용자에게 종료 알림을 보내도록 이벤트를 발행한다.
     *
     * UserSessionRevokedEventListener가 수신하여 /user/queue/auth로 STOMP 알림을 전송한다.
     * 커밋 이후에 발행하므로 DB/Redis 상태가 확정된 뒤 FE가 disconnect한다.
     */
    private void registerAfterCommitSessionRevokedEvent(List<String> sessionIds) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                eventPublisher.publishEvent(new UserSessionRevokedEvent(sessionIds));
            }
        });
    }
}
