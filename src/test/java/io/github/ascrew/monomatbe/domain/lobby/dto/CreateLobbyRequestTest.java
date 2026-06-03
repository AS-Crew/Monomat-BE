package io.github.ascrew.monomatbe.domain.lobby.dto;

import io.github.ascrew.monomatbe.domain.lobby.entity.LobbyDefaults;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class CreateLobbyRequestTest {

    private static ValidatorFactory validatorFactory;
    private static Validator validator;

    @BeforeAll
    static void setUpValidator() {
        validatorFactory = Validation.buildDefaultValidatorFactory();
        validator = validatorFactory.getValidator();
    }

    @AfterAll
    static void closeValidatorFactory() {
        validatorFactory.close();
    }

    @Test
    @DisplayName("maxPlayers를 생략하면 기본값 4명이 적용된다")
    void appliesDefaultMaxPlayersWhenNull() {
        CreateLobbyRequest request = createRequest(
                null,
                LobbyDefaults.DEFAULT_QUESTION_COUNT,
                LobbyDefaults.DEFAULT_TIME_LIMIT_SECONDS
        );

        assertThat(request.maxPlayers()).isEqualTo(LobbyDefaults.DEFAULT_MAX_PLAYERS);
    }

    @Test
    @DisplayName("questionCount를 생략하면 기본값 10개가 적용된다")
    void appliesDefaultQuestionCountWhenNull() {
        CreateLobbyRequest request = createRequest(
                LobbyDefaults.DEFAULT_MAX_PLAYERS,
                null,
                LobbyDefaults.DEFAULT_TIME_LIMIT_SECONDS
        );

        assertThat(request.questionCount()).isEqualTo(LobbyDefaults.DEFAULT_QUESTION_COUNT);
    }

    @Test
    @DisplayName("timeLimitSeconds를 생략하면 기본값 30초가 적용된다")
    void appliesDefaultTimeLimitSecondsWhenNull() {
        CreateLobbyRequest request = createRequest(
                LobbyDefaults.DEFAULT_MAX_PLAYERS,
                LobbyDefaults.DEFAULT_QUESTION_COUNT,
                null
        );

        assertThat(request.timeLimitSeconds()).isEqualTo(LobbyDefaults.DEFAULT_TIME_LIMIT_SECONDS);
    }

    @Test
    @DisplayName("maxPlayers, questionCount, timeLimitSeconds를 모두 생략하면 기본값이 모두 적용된다")
    void appliesAllDefaultsWhenNullableFieldsAreNull() {
        CreateLobbyRequest request = createRequest(null, null, null);

        assertThat(request.maxPlayers()).isEqualTo(LobbyDefaults.DEFAULT_MAX_PLAYERS);
        assertThat(request.questionCount()).isEqualTo(LobbyDefaults.DEFAULT_QUESTION_COUNT);
        assertThat(request.timeLimitSeconds()).isEqualTo(LobbyDefaults.DEFAULT_TIME_LIMIT_SECONDS);
    }

    @Test
    @DisplayName("questionCount가 최소값 1보다 작으면 검증에 실패한다")
    void failsValidationWhenQuestionCountIsLessThanMin() {
        CreateLobbyRequest request = createRequest(
                LobbyDefaults.DEFAULT_MAX_PLAYERS,
                LobbyDefaults.MIN_QUESTION_COUNT - 1,
                LobbyDefaults.DEFAULT_TIME_LIMIT_SECONDS
        );

        assertHasViolation(
                request,
                "questionCount",
                "문제 갯수는 " + LobbyDefaults.MIN_QUESTION_COUNT + " 이상이어야 합니다."
        );
    }

    @Test
    @DisplayName("questionCount가 최소값 1이면 검증에 성공한다")
    void passesValidationWhenQuestionCountIsMin() {
        CreateLobbyRequest request = createRequest(
                LobbyDefaults.DEFAULT_MAX_PLAYERS,
                LobbyDefaults.MIN_QUESTION_COUNT,
                LobbyDefaults.DEFAULT_TIME_LIMIT_SECONDS
        );

        assertThat(validator.validate(request)).isEmpty();
    }

    @Test
    @DisplayName("questionCount가 최대값 50이면 검증에 성공한다")
    void passesValidationWhenQuestionCountIsMax() {
        CreateLobbyRequest request = createRequest(
                LobbyDefaults.DEFAULT_MAX_PLAYERS,
                LobbyDefaults.MAX_QUESTION_COUNT,
                LobbyDefaults.DEFAULT_TIME_LIMIT_SECONDS
        );

        assertThat(validator.validate(request)).isEmpty();
    }

    @Test
    @DisplayName("questionCount가 최대값 50을 초과하면 검증에 실패한다")
    void failsValidationWhenQuestionCountExceedsMax() {
        CreateLobbyRequest request = createRequest(
                LobbyDefaults.DEFAULT_MAX_PLAYERS,
                LobbyDefaults.MAX_QUESTION_COUNT + 1,
                LobbyDefaults.DEFAULT_TIME_LIMIT_SECONDS
        );

        assertHasViolation(
                request,
                "questionCount",
                "문제 갯수는 " + LobbyDefaults.MAX_QUESTION_COUNT + " 이하이어야 합니다."
        );
    }

    @Test
    @DisplayName("maxPlayers가 최소값 2보다 작으면 검증에 실패한다")
    void failsValidationWhenMaxPlayersIsLessThanMin() {
        CreateLobbyRequest request = createRequest(
                LobbyDefaults.MIN_PLAYERS - 1,
                LobbyDefaults.DEFAULT_QUESTION_COUNT,
                LobbyDefaults.DEFAULT_TIME_LIMIT_SECONDS
        );

        assertHasViolation(
                request,
                "maxPlayers",
                "최대 인원은 " + LobbyDefaults.MIN_PLAYERS + "명 이상이어야 합니다."
        );
    }

    @Test
    @DisplayName("maxPlayers가 최소값 2이면 검증에 성공한다")
    void passesValidationWhenMaxPlayersIsMin() {
        CreateLobbyRequest request = createRequest(
                LobbyDefaults.MIN_PLAYERS,
                LobbyDefaults.DEFAULT_QUESTION_COUNT,
                LobbyDefaults.DEFAULT_TIME_LIMIT_SECONDS
        );

        assertThat(validator.validate(request)).isEmpty();
    }

    @Test
    @DisplayName("maxPlayers가 최대값 8이면 검증에 성공한다")
    void passesValidationWhenMaxPlayersIsMax() {
        CreateLobbyRequest request = createRequest(
                LobbyDefaults.MAX_PLAYERS,
                LobbyDefaults.DEFAULT_QUESTION_COUNT,
                LobbyDefaults.DEFAULT_TIME_LIMIT_SECONDS
        );

        assertThat(validator.validate(request)).isEmpty();
    }

    @Test
    @DisplayName("maxPlayers가 최대값 8을 초과하면 검증에 실패한다")
    void failsValidationWhenMaxPlayersExceedsMax() {
        CreateLobbyRequest request = createRequest(
                LobbyDefaults.MAX_PLAYERS + 1,
                LobbyDefaults.DEFAULT_QUESTION_COUNT,
                LobbyDefaults.DEFAULT_TIME_LIMIT_SECONDS
        );

        assertHasViolation(
                request,
                "maxPlayers",
                "최대 인원은 " + LobbyDefaults.MAX_PLAYERS + "명 이하이어야 합니다."
        );
    }

    @Test
    @DisplayName("timeLimitSeconds가 최소값 10보다 작으면 검증에 실패한다")
    void failsValidationWhenTimeLimitSecondsIsLessThanMin() {
        CreateLobbyRequest request = createRequest(
                LobbyDefaults.DEFAULT_MAX_PLAYERS,
                LobbyDefaults.DEFAULT_QUESTION_COUNT,
                LobbyDefaults.MIN_TIME_LIMIT_SECONDS - 1
        );

        assertHasViolation(
                request,
                "timeLimitSeconds",
                "제한 시간은 " + LobbyDefaults.MIN_TIME_LIMIT_SECONDS + "초 이상이어야 합니다."
        );
    }

    @Test
    @DisplayName("timeLimitSeconds가 최소값 10이면 검증에 성공한다")
    void passesValidationWhenTimeLimitSecondsIsMin() {
        CreateLobbyRequest request = createRequest(
                LobbyDefaults.DEFAULT_MAX_PLAYERS,
                LobbyDefaults.DEFAULT_QUESTION_COUNT,
                LobbyDefaults.MIN_TIME_LIMIT_SECONDS
        );

        assertThat(validator.validate(request)).isEmpty();
    }

    @Test
    @DisplayName("timeLimitSeconds가 최대값 120이면 검증에 성공한다")
    void passesValidationWhenTimeLimitSecondsIsMax() {
        CreateLobbyRequest request = createRequest(
                LobbyDefaults.DEFAULT_MAX_PLAYERS,
                LobbyDefaults.DEFAULT_QUESTION_COUNT,
                LobbyDefaults.MAX_TIME_LIMIT_SECONDS
        );

        assertThat(validator.validate(request)).isEmpty();
    }

    @Test
    @DisplayName("timeLimitSeconds가 최대값 120을 초과하면 검증에 실패한다")
    void failsValidationWhenTimeLimitSecondsExceedsMax() {
        CreateLobbyRequest request = createRequest(
                LobbyDefaults.DEFAULT_MAX_PLAYERS,
                LobbyDefaults.DEFAULT_QUESTION_COUNT,
                LobbyDefaults.MAX_TIME_LIMIT_SECONDS + 1
        );

        assertHasViolation(
                request,
                "timeLimitSeconds",
                "제한 시간은 " + LobbyDefaults.MAX_TIME_LIMIT_SECONDS + "초 이하이어야 합니다."
        );
    }

    private static CreateLobbyRequest createRequest(
            Integer maxPlayers,
            Integer questionCount,
            Integer timeLimitSeconds
    ) {
        return new CreateLobbyRequest(
                "테스트 로비",
                maxPlayers,
                false,
                null,
                questionCount,
                timeLimitSeconds
        );
    }

    private static void assertHasViolation(
            CreateLobbyRequest request,
            String propertyPath,
            String message
    ) {
        Set<ConstraintViolation<CreateLobbyRequest>> violations = validator.validate(request);

        assertThat(violations)
                .anySatisfy(violation -> {
                    assertThat(violation.getPropertyPath().toString()).isEqualTo(propertyPath);
                    assertThat(violation.getMessage()).isEqualTo(message);
                });
    }
}