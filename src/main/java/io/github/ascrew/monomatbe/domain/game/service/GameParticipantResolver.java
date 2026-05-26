package io.github.ascrew.monomatbe.domain.game.service;

import io.github.ascrew.monomatbe.domain.auth.entity.GuestSession;
import io.github.ascrew.monomatbe.domain.auth.entity.User;
import io.github.ascrew.monomatbe.domain.auth.entity.UserSession;
import io.github.ascrew.monomatbe.domain.auth.repository.GuestSessionRepository;
import io.github.ascrew.monomatbe.domain.auth.repository.UserSessionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
@RequiredArgsConstructor
public class GameParticipantResolver {

    private final UserSessionRepository userSessionRepository;
    private final GuestSessionRepository guestSessionRepository;

    public List<User> resolveUsers(List<String> participantIdentifiers) {
        java.util.Map<String, User> resolvedByIdentifier = new java.util.HashMap<>();

        userSessionRepository.findBySessionIdIn(participantIdentifiers)
                .forEach(session -> resolvedByIdentifier.put(session.getSessionId(), session.getUser()));

        guestSessionRepository.findByGuestTokenIn(participantIdentifiers)
                .forEach(session -> resolvedByIdentifier.put(session.getGuestToken(), session.getUser()));

        return participantIdentifiers.stream()
                .map(resolvedByIdentifier::get)
                .filter(java.util.Objects::nonNull)
                .toList();
    }
}
