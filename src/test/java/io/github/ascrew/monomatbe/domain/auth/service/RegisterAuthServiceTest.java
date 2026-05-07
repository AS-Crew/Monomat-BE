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
import org.springframework.http.HttpStatus;
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
    void register_trimsInputValues() {
        String uniqueLoginId = "member_" + System.nanoTime();
        String uniqueNickname = "nick_" + System.nanoTime();

        RegisterResponse response = registerAuthService.register(
                "  " + uniqueLoginId + "  ",
                "  password123  ",
                "  " + uniqueNickname + "  "
        );

        assertEquals(uniqueLoginId, response.loginId());
        assertEquals(uniqueNickname, response.nickname());
    }

    @Test
    void register_duplicateLoginId_throwsConflict() {
        String loginId = "dup_login_" + System.nanoTime();

        registerAuthService.register(loginId, "password123", "dupNick_" + System.nanoTime());

        ResponseStatusException exception = assertThrows(ResponseStatusException.class, () ->
                registerAuthService.register(loginId, "password123", "anotherNick_" + System.nanoTime()));
        assertEquals(HttpStatus.CONFLICT, exception.getStatusCode());
        assertEquals("이미 사용 중인 로그인 ID입니다.", exception.getReason());
    }

    @Test
    void register_duplicateNickname_throwsConflict() {
        String nickname = "dup_nick_" + System.nanoTime();
        userRepository.saveAndFlush(User.builder()
                .username(nickname)
                .userType(UserType.REGISTERED)
                .status(UserStatus.ACTIVE)
                .build());

        ResponseStatusException exception = assertThrows(ResponseStatusException.class, () ->
                registerAuthService.register("anotherLogin_" + System.nanoTime(), "password123", nickname));
        assertEquals(HttpStatus.CONFLICT, exception.getStatusCode());
        assertEquals("이미 사용 중인 닉네임입니다.", exception.getReason());
    }

    @Test
    void register_nullPassword_throwsBadRequest() {
        ResponseStatusException exception = assertThrows(ResponseStatusException.class, () ->
                registerAuthService.register("login_" + System.nanoTime(), null, "nick_" + System.nanoTime()));
        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
        assertEquals("비밀번호는 비어 있을 수 없습니다.", exception.getReason());
    }

    @Test
    void register_blankLoginId_throwsBadRequest() {
        ResponseStatusException exception = assertThrows(ResponseStatusException.class, () ->
                registerAuthService.register("   ", "password123", "nick_" + System.nanoTime()));
        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
        assertEquals("로그인 ID는 비어 있을 수 없습니다.", exception.getReason());
    }
}
