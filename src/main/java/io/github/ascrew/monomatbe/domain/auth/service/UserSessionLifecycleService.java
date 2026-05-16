package io.github.ascrew.monomatbe.domain.auth.service;

import io.github.ascrew.monomatbe.domain.auth.entity.UserSession;
import io.github.ascrew.monomatbe.domain.auth.entity.UserSessionStatus;
import io.github.ascrew.monomatbe.domain.auth.entity.UserType;
import io.github.ascrew.monomatbe.domain.auth.repository.UserSessionRepository;
import io.github.ascrew.monomatbe.global.constant.RedisKeys;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
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

        activeSessions.forEach(session -> session.markRevoked(now));
        registerAfterCommitSessionDelete(activeSessions.stream().map(UserSession::getSessionId).toList());
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
}
