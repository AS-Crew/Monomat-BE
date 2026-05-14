package io.github.ascrew.monomatbe.domain.auth.service;

import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Component
@RequiredArgsConstructor
public class AuthSessionCleanupScheduler {

    private final UserSessionLifecycleService userSessionLifecycleService;

    @Scheduled(fixedDelayString = "${auth.session.cleanup-fixed-delay-ms:600000}")
    public void cleanupSessions() {
        userSessionLifecycleService.expireAndCleanupSessions(LocalDateTime.now());
    }
}
