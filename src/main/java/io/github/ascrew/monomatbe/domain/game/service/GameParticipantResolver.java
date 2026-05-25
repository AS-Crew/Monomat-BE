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
        List<User> users = new ArrayList<>();

        List<User> sessionUsers = userSessionRepository.findBySessionIdIn(participantIdentifiers)
                .stream()
                .map(UserSession::getUser)
                .toList();
        users.addAll(sessionUsers);

        List<User> guestUsers = guestSessionRepository.findByGuestTokenIn(participantIdentifiers)
                .stream()
                .map(GuestSession::getUser)
                .toList();
        users.addAll(guestUsers);

        return users;
    }
}
