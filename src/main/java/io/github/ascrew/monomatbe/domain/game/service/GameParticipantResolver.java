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
        java.util.Map<Long, User> userMap = new java.util.HashMap<>();

        userSessionRepository.findBySessionIdIn(participantIdentifiers)
                .forEach(session -> userMap.put(session.getUser().getId(), session.getUser()));

        guestSessionRepository.findByGuestTokenIn(participantIdentifiers)
                .forEach(session -> userMap.put(session.getUser().getId(), session.getUser()));

        return new ArrayList<>(userMap.values());
    }
}
