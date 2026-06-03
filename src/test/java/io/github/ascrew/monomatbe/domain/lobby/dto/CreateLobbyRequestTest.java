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
        CreateLobbyRequest request = new CreateLobbyRequest(
                "테스트 로비",
                null,
                false,
                null,
                LobbyDefaults.DEFAULT_QUESTION_COUNT,
                LobbyDefaults.DEFAULT_TIME_LIMIT_SECONDS
        );

        assertThat(request.maxPlayers()).isEqualTo(LobbyDefaults.DEFAULT_MAX_PLAYERS);
    }

    @Test
    @DisplayName("questionCount를 생략하면 기본값 10개가 적용된다")
    void appliesDefaultQuestionCountWhenNull() {
        CreateLobbyRequest request = new CreateLobbyRequest(
                "테스트 로비",
                LobbyDefaults.DEFAULT_MAX_PLAYERS,
                false,
                null,
                null,
                LobbyDefaults.DEFAULT_TIME_LIMIT_SECONDS
        );

        assertThat(request.questionCount()).isEqualTo(LobbyDefaults.DEFAULT_QUESTION_COUNT);
    }

    @Test
    @DisplayName("timeLimitSeconds를 생략하면 기본값 30초가 적용된다")
    void appliesDefaultTimeLimitSecondsWhenNull() {
        CreateLobbyRequest request = new CreateLobbyRequest(
                "테스트 로비",
                LobbyDefaults.DEFAULT_MAX_PLAYERS,
                false,
                null,
                LobbyDefaults.DEFAULT_QUESTION_COUNT,
                null
        );

        assertThat(request.timeLimitSeconds()).isEqualTo(LobbyDefaults.DEFAULT_TIME_LIMIT_SECONDS);
    }

    @Test
    @DisplayName("maxPlayers, questionCount, timeLimitSeconds를 모두 생략하면 기본값이 모두 적용된다")
    void appliesAllDefaultsWhenNullableFieldsAreNull() {
        CreateLobbyRequest request = new CreateLobbyRequest(
                "테스트 로비",
                null,
                false,
                null,
                null,
                null
        );

        assertThat(request.maxPlayers()).isEqualTo(LobbyDefaults.DEFAULT_MAX_PLAYERS);
        assertThat(request.questionCount()).isEqualTo(LobbyDefaults.DEFAULT_QUESTION_COUNT);
        assertThat(request.timeLimitSeconds()).isEqualTo(LobbyDefaults.DEFAULT_TIME_LIMIT_SECONDS);
    }

    @Test
    @DisplayName("questionCount가 최대값 50을 초과하면 검증에 실패한다")
    void failsValidationWhenQuestionCountExceedsMax() {
        CreateLobbyRequest request = new CreateLobbyRequest(
                "테스트 로비",
                LobbyDefaults.DEFAULT_MAX_PLAYERS,
                false,
                null,
                LobbyDefaults.MAX_QUESTION_COUNT + 1,
                LobbyDefaults.DEFAULT_TIME_LIMIT_SECONDS
        );

        Set<ConstraintViolation<CreateLobbyRequest>> violations = validator.validate(request);

        assertThat(violations)
                .anySatisfy(violation -> {
                    assertThat(violation.getPropertyPath().toString()).isEqualTo("questionCount");
                    assertThat(violation.getMessage()).isEqualTo("문제 갯수는 50 이하이어야 합니다.");
                });
    }
}