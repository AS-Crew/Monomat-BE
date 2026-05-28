package io.github.ascrew.monomatbe.domain.auth.service;

import io.github.ascrew.monomatbe.domain.auth.entity.ForbiddenNicknameWord;
import io.github.ascrew.monomatbe.domain.auth.exception.AuthErrorCode;
import io.github.ascrew.monomatbe.domain.auth.exception.AuthException;
import io.github.ascrew.monomatbe.domain.auth.repository.ForbiddenNicknameWordRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.List;
import java.util.Set;

/**
 * 닉네임 금칙어 관리 서비스.
 *
 * [책임]
 * - 금칙어 목록 조회
 * - 금칙어 추가
 * - 금칙어 삭제
 * - 닉네임에 금칙어가 포함되어 있는지 판단
 *
 * [성능 정책]
 * - 닉네임 검증은 회원가입/게스트 로그인 진입점에 붙어 있으므로 매 요청마다 DB findAll()을 수행하지 않는다.
 * - Redis에 정규화 금칙어 목록을 캐싱하고, 관리자 추가/삭제 시 캐시를 무효화한다.
 * - Redis 장애 시에는 인증 기능 전체가 막히지 않도록 DB 직접 조회로 degrade한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ForbiddenNicknameService {

    private static final String FORBIDDEN_NICKNAME_WORDS_CACHE_KEY =
            "auth:forbidden_nickname_words:normalized";

    private static final String FORBIDDEN_NICKNAME_WORDS_CACHE_LOADED_KEY =
            "auth:forbidden_nickname_words:loaded";

    private static final String CACHE_LOADED_VALUE = "1";

    private static final Duration FORBIDDEN_WORD_CACHE_TTL = Duration.ofMinutes(10);

    private final ForbiddenNicknameWordRepository forbiddenNicknameWordRepository;
    private final NicknameNormalizer nicknameNormalizer;
    private final StringRedisTemplate stringRedisTemplate;

    @Transactional(readOnly = true)
    public List<ForbiddenNicknameWord> getForbiddenWords() {
        return forbiddenNicknameWordRepository.findAllByOrderByCreatedAtDesc();
    }

    @Transactional
    public ForbiddenNicknameWord addForbiddenWord(String rawWord) {
        String word = normalizeRequiredWord(rawWord);
        String normalizedWord = nicknameNormalizer.normalizeForComparison(word);

        if (normalizedWord.isBlank()) {
            throw new AuthException(AuthErrorCode.AUTH_FORBIDDEN_NICKNAME_WORD_REQUIRED);
        }

        if (forbiddenNicknameWordRepository.existsByNormalizedWord(normalizedWord)) {
            throw new AuthException(AuthErrorCode.AUTH_FORBIDDEN_NICKNAME_WORD_DUPLICATED);
        }

        try {
            ForbiddenNicknameWord savedWord = forbiddenNicknameWordRepository.saveAndFlush(
                    ForbiddenNicknameWord.create(word, normalizedWord)
            );

            evictForbiddenWordCache();

            return savedWord;
        } catch (DataIntegrityViolationException e) {
            throw new AuthException(AuthErrorCode.AUTH_FORBIDDEN_NICKNAME_WORD_DUPLICATED, e);
        }
    }

    @Transactional
    public void deleteForbiddenWord(Long id) {
        if (id == null) {
            throw new AuthException(AuthErrorCode.AUTH_FORBIDDEN_NICKNAME_WORD_NOT_FOUND);
        }

        ForbiddenNicknameWord forbiddenWord = forbiddenNicknameWordRepository.findById(id)
                .orElseThrow(() -> new AuthException(AuthErrorCode.AUTH_FORBIDDEN_NICKNAME_WORD_NOT_FOUND));

        forbiddenNicknameWordRepository.delete(forbiddenWord);
        forbiddenNicknameWordRepository.flush();

        evictForbiddenWordCache();
    }

    @Transactional(readOnly = true)
    public boolean containsForbiddenWord(String nickname) {
        String normalizedNickname = nicknameNormalizer.normalizeForComparison(nickname);

        if (normalizedNickname.isBlank()) {
            return false;
        }

        List<String> normalizedForbiddenWords = getNormalizedForbiddenWords();

        return normalizedForbiddenWords.stream()
                .filter(word -> word != null && !word.isBlank())
                .anyMatch(normalizedNickname::contains);
    }

    private List<String> getNormalizedForbiddenWords() {
        List<String> cachedWords = getNormalizedForbiddenWordsSafely();

        if (cachedWords != null) {
            return cachedWords;
        }

        List<String> dbWords = forbiddenNicknameWordRepository.findAllNormalizedWords();
        cacheNormalizedForbiddenWordsSafely(dbWords);

        return dbWords;
    }

    private List<String> getNormalizedForbiddenWordsSafely() {
        try {
            return getNormalizedForbiddenWordsFromCache();
        } catch (RuntimeException e) {
            log.warn(
                    "닉네임 금칙어 Redis 캐시 조회 실패 - DB 직접 조회로 대체합니다.",
                    e
            );
            return null;
        }
    }

    private List<String> getNormalizedForbiddenWordsFromCache() {
        Boolean loaded = stringRedisTemplate.hasKey(FORBIDDEN_NICKNAME_WORDS_CACHE_LOADED_KEY);

        if (!Boolean.TRUE.equals(loaded)) {
            return null;
        }

        Set<String> cachedWords = stringRedisTemplate.opsForSet()
                .members(FORBIDDEN_NICKNAME_WORDS_CACHE_KEY);

        if (cachedWords == null || cachedWords.isEmpty()) {
            return null;
        }

        return List.copyOf(cachedWords);
    }

    private void cacheNormalizedForbiddenWordsSafely(List<String> normalizedWords) {
        try {
            cacheNormalizedForbiddenWords(normalizedWords);
        } catch (RuntimeException e) {
            log.warn(
                    "닉네임 금칙어 Redis 캐시 저장 실패 - DB 조회 결과로 검증을 계속합니다.",
                    e
            );
        }
    }

    private void cacheNormalizedForbiddenWords(List<String> normalizedWords) {
        stringRedisTemplate.delete(FORBIDDEN_NICKNAME_WORDS_CACHE_KEY);

        List<String> validWords = normalizedWords.stream()
                .filter(word -> word != null && !word.isBlank())
                .toList();

        if (!validWords.isEmpty()) {
            stringRedisTemplate.opsForSet()
                    .add(FORBIDDEN_NICKNAME_WORDS_CACHE_KEY, validWords.toArray(String[]::new));

            stringRedisTemplate.expire(
                    FORBIDDEN_NICKNAME_WORDS_CACHE_KEY,
                    FORBIDDEN_WORD_CACHE_TTL
            );
        }

        stringRedisTemplate.opsForValue().set(
                FORBIDDEN_NICKNAME_WORDS_CACHE_LOADED_KEY,
                CACHE_LOADED_VALUE,
                FORBIDDEN_WORD_CACHE_TTL
        );
    }

    private void evictForbiddenWordCache() {
        try {
            stringRedisTemplate.delete(List.of(
                    FORBIDDEN_NICKNAME_WORDS_CACHE_KEY,
                    FORBIDDEN_NICKNAME_WORDS_CACHE_LOADED_KEY
            ));
        } catch (RuntimeException e) {
            log.warn("닉네임 금칙어 Redis 캐시 무효화 실패", e);
        }
    }

    private String normalizeRequiredWord(String rawWord) {
        if (rawWord == null || rawWord.isBlank()) {
            throw new AuthException(AuthErrorCode.AUTH_FORBIDDEN_NICKNAME_WORD_REQUIRED);
        }

        String word = rawWord.trim();

        if (word.isBlank()) {
            throw new AuthException(AuthErrorCode.AUTH_FORBIDDEN_NICKNAME_WORD_REQUIRED);
        }

        return word;
    }
}