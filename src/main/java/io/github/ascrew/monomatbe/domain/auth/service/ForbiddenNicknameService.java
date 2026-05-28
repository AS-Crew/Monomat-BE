package io.github.ascrew.monomatbe.domain.auth.service;

import io.github.ascrew.monomatbe.domain.auth.entity.ForbiddenNicknameWord;
import io.github.ascrew.monomatbe.domain.auth.repository.ForbiddenNicknameWordRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.util.List;
import java.util.Set;

/**
 * 닉네임 금칙어 관리 서비스
 *
 * [책임]
 * - 금칙어 목록 조회
 * - 금칙어 추가
 * - 금칙어 삭제
 * - 닉네임에 금칙어가 포함되어 있는지 판단
 *
 * [설계 의도]
 * - 금칙어 저장소 접근 책임을 이 서비스로 분리한다.
 * - RegisterAuthService, GuestAuthService는 금칙어 저장 방식(DB, Redis, 설정 파일)을 몰라도 된다.
 * - 관리자 API Controller는 이 서비스를 통해 금칙어를 관리한다.
 *
 * [성능 정책]
 * - 닉네임 검증은 회원가입/게스트 로그인 진입점에 붙어 있으므로 매 요청마다 DB findAll()을 수행하면 안 된다.
 * - Redis에 정규화 금칙어 목록을 캐싱하고, 관리자 추가/삭제 시 캐시를 무효화한다.
 * - Redis 장애 시에는 인증 기능 전체가 막히지 않도록 DB 직접 조회로 degrade한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ForbiddenNicknameService {

    private static final String ERROR_FORBIDDEN_WORD_REQUIRED =
            "금칙어는 비어 있을 수 없습니다.";
    private static final String ERROR_FORBIDDEN_WORD_DUPLICATED =
            "이미 등록된 금칙어입니다.";
    private static final String ERROR_FORBIDDEN_WORD_NOT_FOUND =
            "존재하지 않는 금칙어입니다.";

    /**
     * 정규화 금칙어 목록 Redis Set key
     */
    private static final String FORBIDDEN_NICKNAME_WORDS_CACHE_KEY =
            "auth:forbidden_nickname_words:normalized";

    /**
     * 빈 금칙어 목록도 캐시하기 위한 loaded marker key
     *
     * Redis Set은 값이 없으면 key 자체가 없어질 수 있으므로,
     * "DB에서 한 번 로드했다"는 상태를 별도 key로 관리한다.
     */
    private static final String FORBIDDEN_NICKNAME_WORDS_CACHE_LOADED_KEY =
            "auth:forbidden_nickname_words:loaded";

    private static final String CACHE_LOADED_VALUE = "1";

    /**
     * 금칙어 캐시 TTL
     *
     * 관리자 추가/삭제 시 즉시 evict하므로 TTL은 장애 복구용 안전장치에 가깝다.
     */
    private static final Duration FORBIDDEN_WORD_CACHE_TTL = Duration.ofMinutes(10);

    private final ForbiddenNicknameWordRepository forbiddenNicknameWordRepository;
    private final NicknameNormalizer nicknameNormalizer;
    private final StringRedisTemplate stringRedisTemplate;

    /**
     * 관리자 목록 조회용 금칙어 전체 목록을 조회한다.
     *
     * @return 최신 등록순 금칙어 목록
     */
    @Transactional(readOnly = true)
    public List<ForbiddenNicknameWord> getForbiddenWords() {
        return forbiddenNicknameWordRepository.findAllByOrderByCreatedAtDesc();
    }

    /**
     * 금칙어를 추가한다.
     *
     * [중복 기준]
     * - 원본 word가 아니라 normalizedWord 기준으로 중복을 판단한다.
     *
     * 예:
     * - "admin"
     * - "a d m i n"
     *
     * 위 두 값은 같은 normalizedWord("admin")로 판단되어 중복 등록을 막는다.
     *
     * @param rawWord 관리자가 입력한 원본 금칙어
     * @return 저장된 금칙어 엔티티
     */
    @Transactional
    public ForbiddenNicknameWord addForbiddenWord(String rawWord) {
        String word = normalizeRequiredWord(rawWord);
        String normalizedWord = nicknameNormalizer.normalizeForComparison(word);

        if (normalizedWord.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ERROR_FORBIDDEN_WORD_REQUIRED);
        }

        if (forbiddenNicknameWordRepository.existsByNormalizedWord(normalizedWord)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, ERROR_FORBIDDEN_WORD_DUPLICATED);
        }

        try {
            ForbiddenNicknameWord savedWord = forbiddenNicknameWordRepository.saveAndFlush(
                    ForbiddenNicknameWord.create(word, normalizedWord)
            );

            evictForbiddenWordCache();

            return savedWord;
        } catch (DataIntegrityViolationException e) {
            /*
             * 애플리케이션 중복 검증과 DB unique 제약 사이의 race condition 대응.
             * 동시에 같은 normalizedWord가 들어오면 DB unique 제약이 최종 방어선이 된다.
             */
            throw new ResponseStatusException(HttpStatus.CONFLICT, ERROR_FORBIDDEN_WORD_DUPLICATED, e);
        }
    }

    /**
     * 금칙어를 삭제한다.
     *
     * @param id 금칙어 ID
     */
    @Transactional
    public void deleteForbiddenWord(Long id) {
        if (id == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ERROR_FORBIDDEN_WORD_NOT_FOUND);
        }

        if (!forbiddenNicknameWordRepository.existsById(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, ERROR_FORBIDDEN_WORD_NOT_FOUND);
        }

        forbiddenNicknameWordRepository.deleteById(id);
        forbiddenNicknameWordRepository.flush();

        evictForbiddenWordCache();
    }

    /**
     * 닉네임에 등록된 금칙어가 포함되어 있는지 판단한다.
     *
     * [비교 기준]
     * - 닉네임과 금칙어 모두 NicknameNormalizer 기준으로 정규화 후 비교한다.
     *
     * [성능 정책]
     * - Redis 캐시가 있으면 Redis Set만 조회한다.
     * - 캐시가 없으면 DB에서 normalizedWord만 조회한 뒤 Redis에 저장한다.
     * - Redis 장애 시 DB 직접 조회로 fallback한다.
     *
     * @param nickname 정규화 전 또는 후 닉네임
     * @return 금칙어 포함 여부
     */
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
        try {
            List<String> cachedWords = getNormalizedForbiddenWordsFromCache();

            if (cachedWords != null) {
                return cachedWords;
            }

            List<String> dbWords = forbiddenNicknameWordRepository.findAllNormalizedWords();
            cacheNormalizedForbiddenWords(dbWords);

            return dbWords;
        } catch (RuntimeException e) {
            log.warn(
                    "닉네임 금칙어 Redis 캐시 조회/저장 실패 - DB 직접 조회로 대체합니다.",
                    e
            );

            return forbiddenNicknameWordRepository.findAllNormalizedWords();
        }
    }

    /**
     * Redis 캐시에서 정규화 금칙어 목록을 조회한다.
     *
     * @return 캐시가 로드된 상태면 목록 반환, 캐시 미스면 null
     */
    private List<String> getNormalizedForbiddenWordsFromCache() {
        Boolean loaded = stringRedisTemplate.hasKey(FORBIDDEN_NICKNAME_WORDS_CACHE_LOADED_KEY);

        if (!Boolean.TRUE.equals(loaded)) {
            return null;
        }

        Set<String> cachedWords = stringRedisTemplate.opsForSet()
                .members(FORBIDDEN_NICKNAME_WORDS_CACHE_KEY);

        if (cachedWords == null || cachedWords.isEmpty()) {
            return List.of();
        }

        return List.copyOf(cachedWords);
    }

    /**
     * DB에서 조회한 정규화 금칙어 목록을 Redis에 저장한다.
     *
     * 빈 목록도 loaded marker를 저장하여 반복 DB 조회를 방지한다.
     */
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

    /**
     * 관리자 금칙어 추가/삭제 후 캐시를 무효화한다.
     *
     * Redis 캐시 삭제 실패가 관리자 DB 변경 자체를 실패시킬 필요는 없다.
     * 다음 TTL 만료 또는 후속 캐시 재생성으로 복구 가능하므로 warning만 남긴다.
     */
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
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ERROR_FORBIDDEN_WORD_REQUIRED);
        }

        String word = rawWord.trim();

        if (word.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ERROR_FORBIDDEN_WORD_REQUIRED);
        }

        return word;
    }
}