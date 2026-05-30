package io.github.ascrew.monomatbe.domain.user.service;

import io.github.ascrew.monomatbe.domain.auth.entity.User;
import io.github.ascrew.monomatbe.domain.auth.entity.UserStatus;
import io.github.ascrew.monomatbe.domain.auth.entity.UserType;
import io.github.ascrew.monomatbe.domain.auth.repository.UserRepository;
import io.github.ascrew.monomatbe.domain.user.dto.MyUserInfoResponse;
import io.github.ascrew.monomatbe.global.security.jwt.CustomPrincipal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserQueryServiceTest {

    private static final Long USER_ID = 1L;
    private static final String USER_IDENTIFIER = "11111111-1111-1111-1111-111111111111";
    private static final String USERNAME = "모노유저";
    private static final LocalDateTime CREATED_AT = LocalDateTime.of(2026, 5, 29, 12, 0);

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private UserQueryService userQueryService;

    @Nested
    @DisplayName("내 사용자 정보 조회")
    class GetMyInfo {

        @Test
        @DisplayName("ACTIVE 회원 사용자는 내 사용자 정보를 조회할 수 있다")
        void getMyInfo_returnsMyUserInfo_whenRegisteredUserIsActive() {
            CustomPrincipal principal = principal(USER_ID, UserType.REGISTERED);
            User user = user(UserType.REGISTERED, UserStatus.ACTIVE);

            when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));

            MyUserInfoResponse response = userQueryService.getMyInfo(principal);

            assertThat(response.userId()).isEqualTo(USER_ID);
            assertThat(response.username()).isEqualTo(USERNAME);
            assertThat(response.userType()).isEqualTo(UserType.REGISTERED);
            assertThat(response.status()).isEqualTo(UserStatus.ACTIVE);
            assertThat(response.createdAt()).isEqualTo(CREATED_AT);

            verify(userRepository).findById(USER_ID);
        }

        @Test
        @DisplayName("ACTIVE 게스트 사용자는 userType이 GUEST로 매핑되어 반환된다")
        void getMyInfo_returnsGuestUserType_whenGuestUserIsActive() {
            CustomPrincipal principal = principal(USER_ID, UserType.GUEST);
            User user = user(UserType.GUEST, UserStatus.ACTIVE);

            when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));

            MyUserInfoResponse response = userQueryService.getMyInfo(principal);

            assertThat(response.userId()).isEqualTo(USER_ID);
            assertThat(response.username()).isEqualTo(USERNAME);
            assertThat(response.userType()).isEqualTo(UserType.GUEST);
            assertThat(response.status()).isEqualTo(UserStatus.ACTIVE);
            assertThat(response.createdAt()).isEqualTo(CREATED_AT);

            verify(userRepository).findById(USER_ID);
        }

        @Test
        @DisplayName("principal이 null이면 401을 반환한다")
        void getMyInfo_throwsUnauthorized_whenPrincipalIsNull() {
            assertThatThrownBy(() -> userQueryService.getMyInfo(null))
                    .isInstanceOfSatisfying(ResponseStatusException.class, exception ->
                            assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED)
                    );
        }

        @Test
        @DisplayName("principal의 userId가 null이면 401을 반환한다")
        void getMyInfo_throwsUnauthorized_whenPrincipalUserIdIsNull() {
            CustomPrincipal principal = principal(null, UserType.REGISTERED);

            assertThatThrownBy(() -> userQueryService.getMyInfo(principal))
                    .isInstanceOfSatisfying(ResponseStatusException.class, exception ->
                            assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED)
                    );
        }

        @Test
        @DisplayName("DB에서 사용자를 찾을 수 없으면 401을 반환한다")
        void getMyInfo_throwsUnauthorized_whenUserDoesNotExist() {
            CustomPrincipal principal = principal(USER_ID, UserType.REGISTERED);

            when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> userQueryService.getMyInfo(principal))
                    .isInstanceOfSatisfying(ResponseStatusException.class, exception ->
                            assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED)
                    );

            verify(userRepository).findById(USER_ID);
        }

        @Test
        @DisplayName("BANNED 사용자는 403을 반환한다")
        void getMyInfo_throwsForbidden_whenUserIsBanned() {
            CustomPrincipal principal = principal(USER_ID, UserType.REGISTERED);
            User user = user(UserType.REGISTERED, UserStatus.BANNED);

            when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));

            assertThatThrownBy(() -> userQueryService.getMyInfo(principal))
                    .isInstanceOfSatisfying(ResponseStatusException.class, exception ->
                            assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN)
                    );

            verify(userRepository).findById(USER_ID);
        }

        @Test
        @DisplayName("DELETED 사용자는 401을 반환한다")
        void getMyInfo_throwsUnauthorized_whenUserIsDeleted() {
            CustomPrincipal principal = principal(USER_ID, UserType.REGISTERED);
            User user = user(UserType.REGISTERED, UserStatus.DELETED);

            when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));

            assertThatThrownBy(() -> userQueryService.getMyInfo(principal))
                    .isInstanceOfSatisfying(ResponseStatusException.class, exception ->
                            assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED)
                    );

            verify(userRepository).findById(USER_ID);
        }

        @Test
        @DisplayName("사용자 상태가 null이면 409를 반환한다")
        void getMyInfo_throwsConflict_whenUserStatusIsNull() {
            CustomPrincipal principal = principal(USER_ID, UserType.REGISTERED);
            User user = user(UserType.REGISTERED, null);

            when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));

            assertThatThrownBy(() -> userQueryService.getMyInfo(principal))
                    .isInstanceOfSatisfying(ResponseStatusException.class, exception ->
                            assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.CONFLICT)
                    );

            verify(userRepository).findById(USER_ID);
        }
    }

    private CustomPrincipal principal(Long userId, UserType userType) {
        return new CustomPrincipal(
                userId,
                USER_IDENTIFIER,
                userType
        );
    }

    private User user(UserType userType, UserStatus status) {
        return User.builder()
                .id(USER_ID)
                .username(USERNAME)
                .userType(userType)
                .status(status)
                .createdAt(CREATED_AT)
                .updatedAt(CREATED_AT)
                .build();
    }
}