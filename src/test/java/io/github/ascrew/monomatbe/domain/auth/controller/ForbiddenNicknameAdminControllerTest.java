package io.github.ascrew.monomatbe.domain.auth.controller;

import io.github.ascrew.monomatbe.domain.auth.dto.ForbiddenNicknameCreateRequest;
import io.github.ascrew.monomatbe.domain.auth.dto.ForbiddenNicknameResponse;
import io.github.ascrew.monomatbe.domain.auth.entity.ForbiddenNicknameWord;
import io.github.ascrew.monomatbe.domain.auth.entity.UserType;
import io.github.ascrew.monomatbe.domain.auth.service.ForbiddenNicknameService;
import io.github.ascrew.monomatbe.global.security.jwt.CustomPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ForbiddenNicknameAdminControllerTest {

    private static final String ADMIN_PRE_AUTHORIZE_EXPRESSION =
            "@adminAccessValidator.isAdmin(authentication)";

    private ForbiddenNicknameService forbiddenNicknameService;
    private ForbiddenNicknameAdminController controller;
    private Authentication authentication;

    @BeforeEach
    void setUp() {
        forbiddenNicknameService = mock(ForbiddenNicknameService.class);
        controller = new ForbiddenNicknameAdminController(forbiddenNicknameService);
        authentication = authenticatedToken(
                new CustomPrincipal(1L, "admin-session-identifier", UserType.REGISTERED)
        );
    }

    @Test
    @DisplayName("관리자 금칙어 목록을 조회한다")
    void getForbiddenNicknames() {
        ForbiddenNicknameWord forbiddenWord = ForbiddenNicknameWord.create("관리자", "관리자");

        when(forbiddenNicknameService.getForbiddenWords())
                .thenReturn(List.of(forbiddenWord));

        ResponseEntity<List<ForbiddenNicknameResponse>> response =
                controller.getForbiddenNicknames();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(1, response.getBody().size());
        assertEquals("관리자", response.getBody().get(0).word());
        assertEquals("관리자", response.getBody().get(0).normalizedWord());

        verify(forbiddenNicknameService).getForbiddenWords();
    }

    @Test
    @DisplayName("관리자 금칙어를 추가한다")
    void createForbiddenNickname() {
        ForbiddenNicknameCreateRequest request = new ForbiddenNicknameCreateRequest("관리자");
        ForbiddenNicknameWord savedWord = ForbiddenNicknameWord.create("관리자", "관리자");

        when(forbiddenNicknameService.addForbiddenWord("관리자"))
                .thenReturn(savedWord);

        ResponseEntity<ForbiddenNicknameResponse> response =
                controller.createForbiddenNickname(request, authentication);

        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("관리자", response.getBody().word());
        assertEquals("관리자", response.getBody().normalizedWord());

        verify(forbiddenNicknameService).addForbiddenWord("관리자");
    }

    @Test
    @DisplayName("관리자 금칙어를 삭제한다")
    void deleteForbiddenNickname() {
        ResponseEntity<Void> response =
                controller.deleteForbiddenNickname(1L, authentication);

        assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());

        verify(forbiddenNicknameService).deleteForbiddenWord(1L);
    }

    @Test
    @DisplayName("금칙어 목록 조회 API는 관리자 인가 SpEL을 사용한다")
    void getForbiddenNicknamesHasAdminPreAuthorize() throws NoSuchMethodException {
        Method method = ForbiddenNicknameAdminController.class.getMethod("getForbiddenNicknames");

        assertAdminPreAuthorize(method);
    }

    @Test
    @DisplayName("금칙어 추가 API는 관리자 인가 SpEL을 사용한다")
    void createForbiddenNicknameHasAdminPreAuthorize() throws NoSuchMethodException {
        Method method = ForbiddenNicknameAdminController.class.getMethod(
                "createForbiddenNickname",
                ForbiddenNicknameCreateRequest.class,
                Authentication.class
        );

        assertAdminPreAuthorize(method);
    }

    @Test
    @DisplayName("금칙어 삭제 API는 관리자 인가 SpEL을 사용한다")
    void deleteForbiddenNicknameHasAdminPreAuthorize() throws NoSuchMethodException {
        Method method = ForbiddenNicknameAdminController.class.getMethod(
                "deleteForbiddenNickname",
                Long.class,
                Authentication.class
        );

        assertAdminPreAuthorize(method);
    }

    private void assertAdminPreAuthorize(Method method) {
        PreAuthorize preAuthorize = method.getAnnotation(PreAuthorize.class);

        assertNotNull(preAuthorize);
        assertEquals(ADMIN_PRE_AUTHORIZE_EXPRESSION, preAuthorize.value());
    }

    private Authentication authenticatedToken(Object principal) {
        return new TestingAuthenticationToken(
                principal,
                null,
                "ROLE_USER"
        );
    }
}