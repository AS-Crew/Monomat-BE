package io.github.ascrew.monomatbe.domain.auth.service;

import io.github.ascrew.monomatbe.domain.auth.entity.ForbiddenNicknameWord;
import io.github.ascrew.monomatbe.domain.auth.repository.ForbiddenNicknameWordRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ForbiddenNicknameServiceTest {

    private ForbiddenNicknameWordRepository forbiddenNicknameWordRepository;
    private ForbiddenNicknameService forbiddenNicknameService;

    @BeforeEach
    void setUp() {
        forbiddenNicknameWordRepository = mock(ForbiddenNicknameWordRepository.class);
        forbiddenNicknameService = new ForbiddenNicknameService(
                forbiddenNicknameWordRepository,
                new NicknameNormalizer()
        );
    }

    @Test
    @DisplayName("금칙어 추가 시 원본과 정규화 값을 저장한다")
    void addForbiddenWord() {
        when(forbiddenNicknameWordRepository.existsByNormalizedWord("admin")).thenReturn(false);

        ForbiddenNicknameWord saved = ForbiddenNicknameWord.create("A d m i n", "admin");
        when(forbiddenNicknameWordRepository.saveAndFlush(org.mockito.ArgumentMatchers.any()))
                .thenReturn(saved);

        ForbiddenNicknameWord result = forbiddenNicknameService.addForbiddenWord(" A d m i n ");

        assertEquals("A d m i n", result.getWord());
        assertEquals("admin", result.getNormalizedWord());

        ArgumentCaptor<ForbiddenNicknameWord> captor =
                ArgumentCaptor.forClass(ForbiddenNicknameWord.class);
        verify(forbiddenNicknameWordRepository).saveAndFlush(captor.capture());

        assertEquals("A d m i n", captor.getValue().getWord());
        assertEquals("admin", captor.getValue().getNormalizedWord());
    }

    @Test
    @DisplayName("공백 금칙어는 등록할 수 없다")
    void addBlankForbiddenWord() {
        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> forbiddenNicknameService.addForbiddenWord("   ")
        );

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
    }

    @Test
    @DisplayName("정규화 기준으로 중복 금칙어는 등록할 수 없다")
    void addDuplicatedForbiddenWord() {
        when(forbiddenNicknameWordRepository.existsByNormalizedWord("admin")).thenReturn(true);

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> forbiddenNicknameService.addForbiddenWord("a d m i n")
        );

        assertEquals(HttpStatus.CONFLICT, exception.getStatusCode());
    }

    @Test
    @DisplayName("닉네임에 등록된 금칙어가 포함되어 있으면 true를 반환한다")
    void containsForbiddenWord() {
        when(forbiddenNicknameWordRepository.findAll())
                .thenReturn(List.of(ForbiddenNicknameWord.create("관리자", "관리자")));

        boolean result = forbiddenNicknameService.containsForbiddenWord("최고관 리 자");

        assertTrue(result);
    }

    @Test
    @DisplayName("닉네임에 등록된 금칙어가 포함되어 있지 않으면 false를 반환한다")
    void doesNotContainForbiddenWord() {
        when(forbiddenNicknameWordRepository.findAll())
                .thenReturn(List.of(ForbiddenNicknameWord.create("관리자", "관리자")));

        boolean result = forbiddenNicknameService.containsForbiddenWord("정상닉네임");

        assertFalse(result);
    }

    @Test
    @DisplayName("존재하지 않는 금칙어 삭제 요청은 404를 반환한다")
    void deleteNotFoundForbiddenWord() {
        when(forbiddenNicknameWordRepository.existsById(1L)).thenReturn(false);

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> forbiddenNicknameService.deleteForbiddenWord(1L)
        );

        assertEquals(HttpStatus.NOT_FOUND, exception.getStatusCode());
    }
}