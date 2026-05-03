package io.github.ascrew.monomatbe.domain.auth.service;

import io.github.ascrew.monomatbe.domain.auth.dto.RegisterResponse;
import io.github.ascrew.monomatbe.domain.auth.entity.User;
import io.github.ascrew.monomatbe.domain.auth.entity.UserStatus;
import io.github.ascrew.monomatbe.domain.auth.entity.UserType;
import io.github.ascrew.monomatbe.domain.auth.repository.UserCredentialRepository;
import io.github.ascrew.monomatbe.domain.auth.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest
@ActiveProfiles("test")
class RegisterAuthServiceTest {

    @Autowired
    private RegisterAuthService registerAuthService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UserCredentialRepository userCredentialRepository;

    @Test
    void register_success() {
        String uniqueLoginId = "member_" + System.nanoTime();
        String uniqueNickname = "nick_" + System.nanoTime();

        RegisterResponse response = registerAuthService.register(
                uniqueLoginId,
                "password123",
                uniqueNickname
        );

        assertNotNull(response.userId());
        assertEquals(uniqueLoginId, response.loginId());
        assertEquals(uniqueNickname, response.nickname());
        assertEquals(UserType.REGISTERED, response.userType());
    }

    @Test
    void register_duplicateLoginId_throwsConflict() {
        String loginId = "dup_login_" + System.nanoTime();

        registerAuthService.register(loginId, "password123", "dupNick_" + System.nanoTime());

        assertThrows(ResponseStatusException.class, () ->
                registerAuthService.register(loginId, "password123", "anotherNick_" + System.nanoTime()));
    }

    @Test
    void register_duplicateNickname_throwsConflict() {
        String nickname = "dup_nick_" + System.nanoTime();
        userRepository.saveAndFlush(User.builder()
                .username(nickname)
                .userType(UserType.REGISTERED)
                .status(UserStatus.ACTIVE)
                .build());

        assertThrows(ResponseStatusException.class, () ->
                registerAuthService.register("anotherLogin_" + System.nanoTime(), "password123", nickname));
    }
}
