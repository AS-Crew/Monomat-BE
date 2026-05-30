package io.github.ascrew.monomatbe.domain.user.service;

import io.github.ascrew.monomatbe.domain.auth.entity.User;
import io.github.ascrew.monomatbe.domain.auth.entity.UserStatus;
import io.github.ascrew.monomatbe.domain.auth.repository.UserRepository;
import io.github.ascrew.monomatbe.domain.user.dto.MyUserInfoResponse;
import io.github.ascrew.monomatbe.global.security.jwt.CustomPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * 사용자 조회 유스케이스를 담당하는 서비스
 *
 * 현재 단계에서는 로그인한 사용자의 기본 정보 조회만 담당한다.
 * 사용자 수정, 탈퇴, 프로필 이미지 등 쓰기 유스케이스가 추가되면 별도의 CommandService로 분리한다.
 */
@Service
@RequiredArgsConstructor
public class UserQueryService {

    private static final String ERROR_INVALID_PRINCIPAL =
            "유효하지 않은 인증 정보입니다. 다시 로그인해주세요.";
    private static final String ERROR_USER_NOT_FOUND =
            "사용자 정보를 찾을 수 없습니다. 다시 로그인해주세요.";
    private static final String ERROR_USER_BANNED =
            "정지된 사용자는 서비스를 이용할 수 없습니다.";
    private static final String ERROR_USER_DELETED =
            "탈퇴 또는 삭제된 사용자입니다. 다시 로그인해주세요.";
    private static final String ERROR_UNSUPPORTED_USER_STATUS =
            "사용자 상태가 유효하지 않습니다.";

    private final UserRepository userRepository;

    /**
     * 로그인한 사용자의 기본 정보를 조회한다.
     *
     * @param principal JWT 인증 후 SecurityContext에 저장된 사용자 주체
     * @return 내 사용자 기본 정보
     */
    @Transactional(readOnly = true)
    public MyUserInfoResponse getMyInfo(CustomPrincipal principal) {
        validatePrincipal(principal);

        User user = userRepository.findById(principal.userId())
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.UNAUTHORIZED,
                        ERROR_USER_NOT_FOUND
                ));

        validateUsableUser(user.getStatus());

        return toResponse(user);
    }

    private void validatePrincipal(CustomPrincipal principal) {
        if (principal == null || principal.userId() == null) {
            throw new ResponseStatusException(
                    HttpStatus.UNAUTHORIZED,
                    ERROR_INVALID_PRINCIPAL
            );
        }
    }

    private void validateUsableUser(UserStatus status) {
        if (status == null) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    ERROR_UNSUPPORTED_USER_STATUS
            );
        }

        switch (status) {
            case ACTIVE -> {
                return;
            }
            case BANNED -> throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    ERROR_USER_BANNED
            );
            case DELETED -> throw new ResponseStatusException(
                    HttpStatus.UNAUTHORIZED,
                    ERROR_USER_DELETED
            );
            default -> throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    ERROR_UNSUPPORTED_USER_STATUS
            );
        }
    }

    private MyUserInfoResponse toResponse(User user) {
        return MyUserInfoResponse.builder()
                .userId(user.getId())
                .username(user.getUsername())
                .userType(user.getUserType())
                .status(user.getStatus())
                .createdAt(user.getCreatedAt())
                .build();
    }
}