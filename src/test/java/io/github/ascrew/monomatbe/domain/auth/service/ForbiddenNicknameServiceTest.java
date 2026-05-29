package io.github.ascrew.monomatbe.domain.auth.service;

import io.github.ascrew.monomatbe.domain.auth.entity.ForbiddenNicknameWord;
import io.github.ascrew.monomatbe.domain.auth.exception.AuthErrorCode;
import io.github.ascrew.monomatbe.domain.auth.exception.AuthException;
import io.github.ascrew.monomatbe.domain.auth.repository.ForbiddenNicknameWordRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ForbiddenNicknameServiceTest {

    private static final String CACHE_KEY = "auth:forbidden_nickname_words:normalized";

    private ForbiddenNicknameWordRepository forbiddenNicknameWordRepository;
    private StringRedisTemplate stringRedisTemplate;
    private ValueOperations<String, String> valueOperations;
    private ForbiddenNicknameService forbiddenNicknameService;

    @BeforeEach
    void setUp() {
        forbiddenNicknameWordRepository = mock(ForbiddenNicknameWordRepository.class);
        stringRedisTemplate = mock(StringRedisTemplate.class);
        valueOperations = mock(ValueOperations.class);

        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);

        forbiddenNicknameService = new ForbiddenNicknameService(
                forbiddenNicknameWordRepository,
                new NicknameNormalizer(),
                stringRedisTemplate
        );
    }

    @Test
    @DisplayName("금칙어 목록을 최신 등록순으로 조회한다")
    void getForbiddenWords() {
        ForbiddenNicknameWord word = ForbiddenNicknameWord.create("관리자", "관리자");
        when(forbiddenNicknameWordRepository.findAllByOrderByCreatedAtDesc())
                .thenReturn(List.of(word));

        List<ForbiddenNicknameWord> result = forbiddenNicknameService.getForbiddenWords();

        assertEquals(1, result.size());
        assertEquals("관리자", result.get(0).getWord());
        assertEquals("관리자", result.get(0).getNormalizedWord());

        verify(forbiddenNicknameWordRepository).findAllByOrderByCreatedAtDesc();
    }

    @Test
    @DisplayName("금칙어 추가 시 원본과 정규화 값을 저장하고 캐시를 무효화한다")
    void addForbiddenWord() {
        when(forbiddenNicknameWordRepository.existsByNormalizedWord("admin")).thenReturn(false);

        ForbiddenNicknameWord saved = ForbiddenNicknameWord.create("A d m i n", "admin");
        when(forbiddenNicknameWordRepository.saveAndFlush(any()))
                .thenReturn(saved);

        ForbiddenNicknameWord result = forbiddenNicknameService.addForbiddenWord(" A d m i n ");

        assertEquals("A d m i n", result.getWord());
        assertEquals("admin", result.getNormalizedWord());

        ArgumentCaptor<ForbiddenNicknameWord> captor =
                ArgumentCaptor.forClass(ForbiddenNicknameWord.class);
        verify(forbiddenNicknameWordRepository).saveAndFlush(captor.capture());

        assertEquals("A d m i n", captor.getValue().getWord());
        assertEquals("admin", captor.getValue().getNormalizedWord());

        verify(stringRedisTemplate).delete(CACHE_KEY);
    }

    @Test
    @DisplayName("공백 금칙어는 등록할 수 없다")
    void addBlankForbiddenWord() {
        AuthException exception = assertThrows(
                AuthException.class,
                () -> forbiddenNicknameService.addForbiddenWord("   ")
        );

        assertEquals(AuthErrorCode.AUTH_FORBIDDEN_NICKNAME_WORD_REQUIRED, exception.getErrorCode());
        verify(forbiddenNicknameWordRepository, never()).saveAndFlush(any());
        verify(stringRedisTemplate, never()).delete(anyString());
    }

    @Test
    @DisplayName("정규화 결과가 공백이면 금칙어로 등록할 수 없다")
    void addForbiddenWordWithOnlyWhitespaceAfterNormalization() {
        AuthException exception = assertThrows(
                AuthException.class,
                () -> forbiddenNicknameService.addForbiddenWord("\t\n ")
        );

        assertEquals(AuthErrorCode.AUTH_FORBIDDEN_NICKNAME_WORD_REQUIRED, exception.getErrorCode());
        verify(forbiddenNicknameWordRepository, never()).saveAndFlush(any());
        verify(stringRedisTemplate, never()).delete(anyString());
    }

    @Test
    @DisplayName("정규화 기준으로 중복 금칙어는 등록할 수 없다")
    void addDuplicatedForbiddenWord() {
        when(forbiddenNicknameWordRepository.existsByNormalizedWord("admin")).thenReturn(true);

        AuthException exception = assertThrows(
                AuthException.class,
                () -> forbiddenNicknameService.addForbiddenWord("a d m i n")
        );

        assertEquals(AuthErrorCode.AUTH_FORBIDDEN_NICKNAME_WORD_DUPLICATED, exception.getErrorCode());
        verify(forbiddenNicknameWordRepository, never()).saveAndFlush(any());
        verify(stringRedisTemplate, never()).delete(anyString());
    }

    @Test
    @DisplayName("동시 등록 경쟁으로 DB unique 제약이 발생하면 중복 에러로 변환한다")
    void addForbiddenWordWithDataIntegrityViolation() {
        when(forbiddenNicknameWordRepository.existsByNormalizedWord("admin")).thenReturn(false);
        when(forbiddenNicknameWordRepository.saveAndFlush(any()))
                .thenThrow(new DataIntegrityViolationException("duplicated normalized word"));

        AuthException exception = assertThrows(
                AuthException.class,
                () -> forbiddenNicknameService.addForbiddenWord("admin")
        );

        assertEquals(AuthErrorCode.AUTH_FORBIDDEN_NICKNAME_WORD_DUPLICATED, exception.getErrorCode());
        verify(stringRedisTemplate, never()).delete(anyString());
    }

    @Test
    @DisplayName("금칙어 삭제 시 엔티티를 조회한 뒤 삭제하고 캐시를 무효화한다")
    void deleteForbiddenWord() {
        ForbiddenNicknameWord forbiddenWord = ForbiddenNicknameWord.create("관리자", "관리자");

        when(forbiddenNicknameWordRepository.findById(1L))
                .thenReturn(Optional.of(forbiddenWord));

        forbiddenNicknameService.deleteForbiddenWord(1L);

        verify(forbiddenNicknameWordRepository).findById(1L);
        verify(forbiddenNicknameWordRepository).delete(forbiddenWord);
        verify(forbiddenNicknameWordRepository).flush();
        verify(stringRedisTemplate).delete(CACHE_KEY);
    }

    @Test
    @DisplayName("존재하지 않는 금칙어 삭제 요청은 미존재 에러를 반환한다")
    void deleteNotFoundForbiddenWord() {
        when(forbiddenNicknameWordRepository.findById(1L))
                .thenReturn(Optional.empty());

        AuthException exception = assertThrows(
                AuthException.class,
                () -> forbiddenNicknameService.deleteForbiddenWord(1L)
        );

        assertEquals(AuthErrorCode.AUTH_FORBIDDEN_NICKNAME_WORD_NOT_FOUND, exception.getErrorCode());
        verify(forbiddenNicknameWordRepository).findById(1L);
        verify(forbiddenNicknameWordRepository, never()).delete(any());
        verify(stringRedisTemplate, never()).delete(anyString());
        verify(stringRedisTemplate, never()).delete(anyCollection());
    }

    @Test
    @DisplayName("null ID로 금칙어 삭제 요청 시 미존재 에러를 반환한다")
    void deleteForbiddenWordWithNullId() {
        AuthException exception = assertThrows(
                AuthException.class,
                () -> forbiddenNicknameService.deleteForbiddenWord(null)
        );

        assertEquals(AuthErrorCode.AUTH_FORBIDDEN_NICKNAME_WORD_NOT_FOUND, exception.getErrorCode());
        verify(forbiddenNicknameWordRepository, never()).findById(any());
        verify(forbiddenNicknameWordRepository, never()).delete(any());
        verify(stringRedisTemplate, never()).delete(anyString());
        verify(stringRedisTemplate, never()).delete(anyCollection());
    }

    @Test
    @DisplayName("Redis 캐시 hit 시 DB를 조회하지 않고 캐시 데이터로 금칙어 포함 여부를 판단한다")
    void containsForbiddenWordWithCacheHit() {
        when(valueOperations.get(CACHE_KEY)).thenReturn("관리자\nadmin");

        boolean result = forbiddenNicknameService.containsForbiddenWord("최고관 리 자");

        assertTrue(result);
        verify(forbiddenNicknameWordRepository, never()).findAllNormalizedWords();
    }

    @Test
    @DisplayName("Redis 캐시 hit 시 금칙어가 포함되어 있지 않으면 false를 반환한다")
    void doesNotContainForbiddenWordWithCacheHit() {
        when(valueOperations.get(CACHE_KEY)).thenReturn("관리자\nadmin");

        boolean result = forbiddenNicknameService.containsForbiddenWord("정상닉네임");

        assertFalse(result);
        verify(forbiddenNicknameWordRepository, never()).findAllNormalizedWords();
    }

    @Test
    @DisplayName("Redis 캐시 miss 시 DB에서 normalizedWord만 조회하고 Redis String으로 캐싱한다")
    void containsForbiddenWordWithCacheMiss() {
        when(valueOperations.get(CACHE_KEY)).thenReturn(null);
        when(forbiddenNicknameWordRepository.findAllNormalizedWords())
                .thenReturn(List.of("관리자", "admin"));

        boolean result = forbiddenNicknameService.containsForbiddenWord("superAdmin");

        assertTrue(result);

        verify(forbiddenNicknameWordRepository).findAllNormalizedWords();
        verify(valueOperations).set(eq(CACHE_KEY), eq("관리자\nadmin"), any(Duration.class));
    }

    @Test
    @DisplayName("DB 금칙어 목록이 비어 있어도 빈 문자열로 캐싱한다")
    void cacheEmptyForbiddenWords() {
        when(valueOperations.get(CACHE_KEY)).thenReturn(null);
        when(forbiddenNicknameWordRepository.findAllNormalizedWords())
                .thenReturn(List.of());

        boolean result = forbiddenNicknameService.containsForbiddenWord("정상닉네임");

        assertFalse(result);

        verify(forbiddenNicknameWordRepository).findAllNormalizedWords();
        verify(valueOperations).set(eq(CACHE_KEY), eq(""), any(Duration.class));
    }

    @Test
    @DisplayName("Redis 캐시에 빈 문자열이 있으면 금칙어 0개 상태로 보고 DB를 조회하지 않는다")
    void emptyStringCacheHitMeansNoForbiddenWords() {
        when(valueOperations.get(CACHE_KEY)).thenReturn("");

        boolean result = forbiddenNicknameService.containsForbiddenWord("정상닉네임");

        assertFalse(result);
        verify(forbiddenNicknameWordRepository, never()).findAllNormalizedWords();
    }

    @Test
    @DisplayName("Redis 캐시 조회 실패 시 DB 직접 조회로 fallback하여 금칙어 포함 여부를 판단한다")
    void containsForbiddenWordFallbackToDatabaseWhenRedisReadFails() {
        when(valueOperations.get(CACHE_KEY))
                .thenThrow(new RedisConnectionFailureException("redis down"));
        when(forbiddenNicknameWordRepository.findAllNormalizedWords())
                .thenReturn(List.of("관리자"));

        boolean result = forbiddenNicknameService.containsForbiddenWord("최고관 리 자");

        assertTrue(result);
        verify(forbiddenNicknameWordRepository).findAllNormalizedWords();
    }

    @Test
    @DisplayName("Redis 캐시 저장 실패 시 DB 조회 결과를 재사용하고 DB를 중복 조회하지 않는다")
    void containsForbiddenWordUsesDbResultWhenRedisWriteFails() {
        when(valueOperations.get(CACHE_KEY)).thenReturn(null);
        when(forbiddenNicknameWordRepository.findAllNormalizedWords())
                .thenReturn(List.of("관리자"));
        doThrow(new RedisConnectionFailureException("redis write down"))
                .when(valueOperations)
                .set(eq(CACHE_KEY), eq("관리자"), any(Duration.class));

        boolean result = forbiddenNicknameService.containsForbiddenWord("최고관리자");

        assertTrue(result);
        verify(forbiddenNicknameWordRepository).findAllNormalizedWords();
    }

    @Test
    @DisplayName("Redis 장애 fallback 후 DB 조회 결과에도 금칙어가 없으면 false를 반환한다")
    void doesNotContainForbiddenWordFallbackToDatabaseWhenRedisFails() {
        when(valueOperations.get(CACHE_KEY))
                .thenThrow(new RedisConnectionFailureException("redis down"));
        when(forbiddenNicknameWordRepository.findAllNormalizedWords())
                .thenReturn(List.of("관리자"));

        boolean result = forbiddenNicknameService.containsForbiddenWord("정상닉네임");

        assertFalse(result);
        verify(forbiddenNicknameWordRepository).findAllNormalizedWords();
    }

    @Test
    @DisplayName("닉네임이 null이면 Redis와 DB를 조회하지 않고 false를 반환한다")
    void containsForbiddenWordWithNullNickname() {
        boolean result = forbiddenNicknameService.containsForbiddenWord(null);

        assertFalse(result);
        verify(valueOperations, never()).get(anyString());
        verify(forbiddenNicknameWordRepository, never()).findAllNormalizedWords();
    }

    @Test
    @DisplayName("닉네임이 공백이면 Redis와 DB를 조회하지 않고 false를 반환한다")
    void containsForbiddenWordWithBlankNickname() {
        boolean result = forbiddenNicknameService.containsForbiddenWord("   ");

        assertFalse(result);
        verify(valueOperations, never()).get(anyString());
        verify(forbiddenNicknameWordRepository, never()).findAllNormalizedWords();
    }

    @Test
    @DisplayName("금칙어 추가 후 캐시 무효화 실패가 발생해도 DB 저장 결과는 반환한다")
    void addForbiddenWordEvenIfCacheEvictFails() {
        when(forbiddenNicknameWordRepository.existsByNormalizedWord("admin")).thenReturn(false);

        ForbiddenNicknameWord saved = ForbiddenNicknameWord.create("admin", "admin");
        when(forbiddenNicknameWordRepository.saveAndFlush(any()))
                .thenReturn(saved);

        when(stringRedisTemplate.delete(CACHE_KEY))
                .thenThrow(new RedisConnectionFailureException("redis evict down"));

        ForbiddenNicknameWord result = forbiddenNicknameService.addForbiddenWord("admin");

        assertEquals("admin", result.getWord());
        assertEquals("admin", result.getNormalizedWord());
        verify(stringRedisTemplate).delete(CACHE_KEY);
    }

    @Test
    @DisplayName("금칙어 삭제 후 캐시 무효화 실패가 발생해도 삭제 요청은 실패하지 않는다")
    void deleteForbiddenWordEvenIfCacheEvictFails() {
        ForbiddenNicknameWord forbiddenWord = ForbiddenNicknameWord.create("관리자", "관리자");

        when(forbiddenNicknameWordRepository.findById(1L))
                .thenReturn(Optional.of(forbiddenWord));
        when(stringRedisTemplate.delete(CACHE_KEY))
                .thenThrow(new RedisConnectionFailureException("redis evict down"));

        forbiddenNicknameService.deleteForbiddenWord(1L);

        verify(forbiddenNicknameWordRepository).findById(1L);
        verify(forbiddenNicknameWordRepository).delete(forbiddenWord);
        verify(forbiddenNicknameWordRepository).flush();
        verify(stringRedisTemplate).delete(CACHE_KEY);
    }

    @Test
    @DisplayName("중복되거나 공백인 캐시 값은 검증 시 무시한다")
    void cachedWordsIgnoreBlankValues() {
        when(valueOperations.get(CACHE_KEY)).thenReturn("관리자\n\nadmin\n관리자");

        boolean result = forbiddenNicknameService.containsForbiddenWord("normal-admin-user");

        assertTrue(result);
        verify(forbiddenNicknameWordRepository, never()).findAllNormalizedWords();
    }

    @Test
    @DisplayName("DB 조회 결과의 null, 공백, 중복 값은 캐싱 시 제거한다")
    void cacheNormalizedWordsFiltersInvalidValues() {
        when(valueOperations.get(CACHE_KEY)).thenReturn(null);
        when(forbiddenNicknameWordRepository.findAllNormalizedWords())
                .thenReturn(List.of("관리자", "", "admin", "관리자"));

        boolean result = forbiddenNicknameService.containsForbiddenWord("superAdmin");

        assertTrue(result);
        verify(valueOperations).set(eq(CACHE_KEY), eq("관리자\nadmin"), any(Duration.class));
    }
}