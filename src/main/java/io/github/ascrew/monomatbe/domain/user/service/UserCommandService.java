package io.github.ascrew.monomatbe.domain.user.service;

import io.github.ascrew.monomatbe.domain.auth.entity.User;
import io.github.ascrew.monomatbe.domain.auth.entity.UserStatus;
import io.github.ascrew.monomatbe.domain.auth.entity.UserType;
import io.github.ascrew.monomatbe.domain.auth.repository.UserRepository;
import io.github.ascrew.monomatbe.domain.chat.service.ChatSenderProfileCacheEvictor;
import io.github.ascrew.monomatbe.domain.user.dto.MyUserInfoResponse;
import io.github.ascrew.monomatbe.domain.user.dto.UpdateNicknameRequest;
import io.github.ascrew.monomatbe.global.security.jwt.CustomPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

/**
 * 사용자 쓰기 유스케이스를 담당하는 서비스
 *
 * [책임]
 * - 정식 회원 닉네임 변경
 * - 사용자 상태 검증
 * - 닉네임 중복 검증
 * - 닉네임 변경 후 채팅 발신자 프로필 캐시 무효화 예약
 */
@Service
@RequiredArgsConstructor
public class UserCommandService {

    private static final String ERROR_INVALID_PRINCIPAL =
            "유효하지 않은 인증 정보입니다. 다시 로그인해주세요.";
    private static final String ERROR_USER_NOT_FOUND =
            "사용자 정보를 찾을 수 없습니다. 다시 로그인해주세요.";
    private static final String ERROR_REGISTERED_USER_ONLY =
            "정식 회원만 닉네임을 변경할 수 있습니다.";
    private static final String ERROR_USER_BANNED =
            "정지된 사용자는 서비스를 이용할 수 없습니다.";
    private static final String ERROR_USER_DELETED =
            "탈퇴 또는 삭제된 사용자입니다. 다시 로그인해주세요.";
    private static final String ERROR_UNSUPPORTED_USER_STATUS =
            "사용자 상태가 유효하지 않습니다.";
    private static final String ERROR_NICKNAME_REQUIRED =
            "닉네임을 입력해주세요.";
    private static final String ERROR_NICKNAME_TOO_LONG =
            "닉네임은 50자 이하로 입력해주세요.";
    private static final String ERROR_NICKNAME_DUPLICATED =
            "이미 사용 중인 닉네임입니다.";

    private static final int NICKNAME_MAX_LENGTH = 50;

    private final UserRepository userRepository;
    private final ChatSenderProfileCacheEvictor chatSenderProfileCacheEvictor;

    /**
     * 정식 회원 닉네임을 변경한다.
     *
     * @param principal 인증 주체
     * @param request 닉네임 변경 요청
     * @return 변경 후 내 사용자 정보
     */
    @Transactional
    public MyUserInfoResponse updateMyNickname(
            CustomPrincipal principal,
            UpdateNicknameRequest request
    ) {
        validatePrincipal(principal);

        User user = userRepository.findById(principal.userId())
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.UNAUTHORIZED,
                        ERROR_USER_NOT_FOUND
                ));

        validateRegisteredUser(user);
        validateUsableUser(user.getStatus());

        String nickname = normalizeNickname(request.username());

        if (nickname.equals(user.getUsername())) {
            return toResponse(user);
        }

        validateNicknameDuplicated(nickname);

        user.updateUsername(nickname);
        registerChatSenderProfileCacheEviction(user.getId());

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

    private void validateRegisteredUser(User user) {
        if (user.getUserType() != UserType.REGISTERED) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    ERROR_REGISTERED_USER_ONLY
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

    private String normalizeNickname(String username) {
        if (username == null) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    ERROR_NICKNAME_REQUIRED
            );
        }

        String nickname = username.trim();

        if (nickname.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    ERROR_NICKNAME_REQUIRED
            );
        }

        if (nickname.length() > NICKNAME_MAX_LENGTH) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    ERROR_NICKNAME_TOO_LONG
            );
        }

        return nickname;
    }

    private void validateNicknameDuplicated(String nickname) {
        if (userRepository.existsByUsername(nickname)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    ERROR_NICKNAME_DUPLICATED
            );
        }
    }

    private void registerChatSenderProfileCacheEviction(Long userId) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    chatSenderProfileCacheEvictor.evictByUserId(userId);
                }
            });
            return;
        }

        chatSenderProfileCacheEvictor.evictByUserId(userId);
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