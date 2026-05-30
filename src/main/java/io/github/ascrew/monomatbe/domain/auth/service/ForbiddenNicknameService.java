package io.github.ascrew.monomatbe.domain.auth.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.json.JsonMapper;
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
import java.util.Objects;

/**
 * 닉네임 금칙어 관리 서비스
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
 *
 * [캐시 정책]
 * - Redis key 하나만 사용한다.
 * - 금칙어 목록은 JSON 배열 문자열로 저장한다.
 * - 금칙어가 0개인 상태도 JSON 빈 배열("[]")로 캐싱한다.
 * - 개행 문자 구분자 방식은 정규화 정책과 암묵적으로 결합될 수 있으므로 사용하지 않는다.
 *
 * [관리자 변경 반영 정책]
 * - 금칙어 추가/삭제 후 Redis 캐시 무효화에 실패하면 관리자 API를 성공 처리하지 않는다.
 * - 성공 응답을 반환했는데 TTL 동안 stale cache가 남는 상태를 방지하기 위함이다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ForbiddenNicknameService {

    private static final String FORBIDDEN_NICKNAME_WORDS_CACHE_KEY =
            "auth:forbidden_nickname_words:normalized";

    private static final Duration FORBIDDEN_WORD_CACHE_TTL = Duration.ofMinutes(10);

    private static final TypeReference<List<String>> STRING_LIST_TYPE_REFERENCE =
            new TypeReference<>() {
            };

    private static final JsonMapper CACHE_JSON_MAPPER = JsonMapper.builder().build();

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

        /*
         * [금칙어 판정 정책]
         * - 현재 정책은 완전 일치 차단이 아니라 부분 포함 차단이다.
         * - 정규화된 닉네임 안에 정규화된 금칙어가 포함되면 차단한다.
         *
         * 예:
         * - 금칙어: "admin"
         * - 차단: "admin", "superadmin", "a d m i n", "madministrator"
         */
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
            String cachedValue = stringRedisTemplate.opsForValue()
                    .get(FORBIDDEN_NICKNAME_WORDS_CACHE_KEY);

            if (cachedValue == null) {
                return null;
            }

            return deserializeNormalizedWords(cachedValue);
        } catch (RuntimeException e) {
            log.warn(
                    "닉네임 금칙어 Redis 캐시 조회 실패 - DB 직접 조회로 대체합니다.",
                    e
            );
            return null;
        }
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
        String serializedWords = serializeNormalizedWords(normalizedWords);

        stringRedisTemplate.opsForValue().set(
                FORBIDDEN_NICKNAME_WORDS_CACHE_KEY,
                serializedWords,
                FORBIDDEN_WORD_CACHE_TTL
        );
    }

    private String serializeNormalizedWords(List<String> normalizedWords) {
        List<String> cacheableWords = normalizedWords.stream()
                .filter(Objects::nonNull)
                .filter(word -> !word.isBlank())
                .distinct()
                .toList();

        try {
            return CACHE_JSON_MAPPER.writeValueAsString(cacheableWords);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("닉네임 금칙어 캐시 직렬화에 실패했습니다.", e);
        }
    }

    private List<String> deserializeNormalizedWords(String cachedValue) {
        if (cachedValue.isBlank()) {
            return List.of();
        }

        try {
            return CACHE_JSON_MAPPER.readValue(cachedValue, STRING_LIST_TYPE_REFERENCE)
                    .stream()
                    .filter(Objects::nonNull)
                    .filter(word -> !word.isBlank())
                    .toList();
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("닉네임 금칙어 캐시 역직렬화에 실패했습니다.", e);
        }
    }

    private void evictForbiddenWordCache() {
        try {
            stringRedisTemplate.delete(FORBIDDEN_NICKNAME_WORDS_CACHE_KEY);
        } catch (RuntimeException e) {
            log.error("닉네임 금칙어 Redis 캐시 무효화 실패", e);
            throw new AuthException(AuthErrorCode.AUTH_FORBIDDEN_NICKNAME_CACHE_EVICT_FAILED, e);
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