package io.github.ascrew.monomatbe.domain.auth.controller;

import io.github.ascrew.monomatbe.domain.auth.dto.ForbiddenNicknameCreateRequest;
import io.github.ascrew.monomatbe.domain.auth.dto.ForbiddenNicknameResponse;
import io.github.ascrew.monomatbe.domain.auth.entity.ForbiddenNicknameWord;
import io.github.ascrew.monomatbe.domain.auth.entity.UserType;
import io.github.ascrew.monomatbe.domain.auth.service.AdminAccessValidator;
import io.github.ascrew.monomatbe.domain.auth.service.ForbiddenNicknameService;
import io.github.ascrew.monomatbe.global.security.jwt.CustomPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ForbiddenNicknameAdminControllerTest {

    private ForbiddenNicknameService forbiddenNicknameService;
    private AdminAccessValidator adminAccessValidator;
    private ForbiddenNicknameAdminController controller;

    private CustomPrincipal adminPrincipal;

    @BeforeEach
    void setUp() {
        forbiddenNicknameService = mock(ForbiddenNicknameService.class);
        adminAccessValidator = mock(AdminAccessValidator.class);

        controller = new ForbiddenNicknameAdminController(
                forbiddenNicknameService,
                adminAccessValidator
        );

        adminPrincipal = new CustomPrincipal(
                1L,
                "admin-user-identifier",
                UserType.REGISTERED
        );
    }

    @Test
    @DisplayName("관리자 금칙어 목록을 조회한다")
    void getForbiddenNicknames() {
        ForbiddenNicknameWord forbiddenWord = ForbiddenNicknameWord.create("관리자", "관리자");

        when(forbiddenNicknameService.getForbiddenWords())
                .thenReturn(List.of(forbiddenWord));

        ResponseEntity<List<ForbiddenNicknameResponse>> response =
                controller.getForbiddenNicknames(adminPrincipal);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(1, response.getBody().size());
        assertEquals("관리자", response.getBody().get(0).word());
        assertEquals("관리자", response.getBody().get(0).normalizedWord());

        verify(adminAccessValidator).validate(adminPrincipal);
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
                controller.createForbiddenNickname(request, adminPrincipal);

        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("관리자", response.getBody().word());
        assertEquals("관리자", response.getBody().normalizedWord());

        verify(adminAccessValidator).validate(adminPrincipal);
        verify(forbiddenNicknameService).addForbiddenWord("관리자");
    }

    @Test
    @DisplayName("관리자 금칙어를 삭제한다")
    void deleteForbiddenNickname() {
        ResponseEntity<Void> response =
                controller.deleteForbiddenNickname(1L, adminPrincipal);

        assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());

        verify(adminAccessValidator).validate(adminPrincipal);
        verify(forbiddenNicknameService).deleteForbiddenWord(1L);
    }

    @Test
    @DisplayName("관리자 권한이 없으면 목록 조회 서비스로 진입하지 않는다")
    void rejectGetForbiddenNicknamesWithoutAdminPermission() {
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "관리자 권한이 필요합니다."))
                .when(adminAccessValidator)
                .validate(adminPrincipal);

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> controller.getForbiddenNicknames(adminPrincipal)
        );

        assertEquals(HttpStatus.FORBIDDEN, exception.getStatusCode());
        verify(adminAccessValidator).validate(adminPrincipal);
        verifyNoInteractions(forbiddenNicknameService);
    }

    @Test
    @DisplayName("관리자 권한이 없으면 금칙어 추가 서비스로 진입하지 않는다")
    void rejectCreateForbiddenNicknameWithoutAdminPermission() {
        ForbiddenNicknameCreateRequest request = new ForbiddenNicknameCreateRequest("관리자");

        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "관리자 권한이 필요합니다."))
                .when(adminAccessValidator)
                .validate(adminPrincipal);

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> controller.createForbiddenNickname(request, adminPrincipal)
        );

        assertEquals(HttpStatus.FORBIDDEN, exception.getStatusCode());
        verify(adminAccessValidator).validate(adminPrincipal);
        verifyNoInteractions(forbiddenNicknameService);
    }

    @Test
    @DisplayName("관리자 권한이 없으면 금칙어 삭제 서비스로 진입하지 않는다")
    void rejectDeleteForbiddenNicknameWithoutAdminPermission() {
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "관리자 권한이 필요합니다."))
                .when(adminAccessValidator)
                .validate(adminPrincipal);

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> controller.deleteForbiddenNickname(1L, adminPrincipal)
        );

        assertEquals(HttpStatus.FORBIDDEN, exception.getStatusCode());
        verify(adminAccessValidator).validate(adminPrincipal);
        verifyNoInteractions(forbiddenNicknameService);
    }
}