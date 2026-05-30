package io.github.ascrew.monomatbe.domain.user.controller;

import io.github.ascrew.monomatbe.domain.auth.entity.UserStatus;
import io.github.ascrew.monomatbe.domain.auth.entity.UserType;
import io.github.ascrew.monomatbe.domain.user.dto.MyUserInfoResponse;
import io.github.ascrew.monomatbe.domain.user.service.UserQueryService;
import io.github.ascrew.monomatbe.global.security.jwt.CustomPrincipal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserControllerTest {

    private static final Long USER_ID = 1L;
    private static final String USER_IDENTIFIER = "11111111-1111-1111-1111-111111111111";
    private static final String USERNAME = "모노유저";
    private static final LocalDateTime CREATED_AT = LocalDateTime.of(2026, 5, 29, 12, 0);

    @Mock
    private UserQueryService userQueryService;

    @InjectMocks
    private UserController userController;

    @Test
    @DisplayName("GET /api/users/me 요청을 서비스에 위임하고 200 OK로 반환한다")
    void getMyInfo_delegatesToServiceAndReturnsOk() {
        CustomPrincipal principal = new CustomPrincipal(
                USER_ID,
                USER_IDENTIFIER,
                UserType.REGISTERED
        );

        MyUserInfoResponse serviceResponse = MyUserInfoResponse.builder()
                .userId(USER_ID)
                .username(USERNAME)
                .userType(UserType.REGISTERED)
                .status(UserStatus.ACTIVE)
                .createdAt(CREATED_AT)
                .build();

        when(userQueryService.getMyInfo(principal)).thenReturn(serviceResponse);

        ResponseEntity<MyUserInfoResponse> response = userController.getMyInfo(principal);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(serviceResponse);

        verify(userQueryService).getMyInfo(principal);
    }
}