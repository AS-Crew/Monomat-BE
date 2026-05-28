package io.github.ascrew.monomatbe.domain.auth.service;

import io.github.ascrew.monomatbe.domain.auth.entity.ForbiddenNicknameWord;
import io.github.ascrew.monomatbe.domain.auth.repository.ForbiddenNicknameWordRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

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
 * [현재 구현]
 * - DB 직접 조회 기반
 *
 * [확장 방향]
 * - 금칙어 목록이 커지거나 요청량이 많아지면 Redis/로컬 캐시를 이 서비스 내부에 추가한다.
 * - 외부 서비스는 containsForbiddenWord() 계약만 유지하면 된다.
 */
@Service
@RequiredArgsConstructor
public class ForbiddenNicknameService {

    private static final String ERROR_FORBIDDEN_WORD_REQUIRED =
            "금칙어는 비어 있을 수 없습니다.";
    private static final String ERROR_FORBIDDEN_WORD_DUPLICATED =
            "이미 등록된 금칙어입니다.";
    private static final String ERROR_FORBIDDEN_WORD_NOT_FOUND =
            "존재하지 않는 금칙어입니다.";

    private final ForbiddenNicknameWordRepository forbiddenNicknameWordRepository;
    private final NicknameNormalizer nicknameNormalizer;

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
            return forbiddenNicknameWordRepository.saveAndFlush(
                    ForbiddenNicknameWord.create(word, normalizedWord)
            );
        } catch (DataIntegrityViolationException e) {
            /*
             * 애플리케이션 중복 검증과 DB unique 제약 사이의 race condition 대응
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
    }

    /**
     * 닉네임에 등록된 금칙어가 포함되어 있는지 판단한다.
     *
     * [비교 기준]
     * - 닉네임과 금칙어 모두 NicknameNormalizer 기준으로 정규화 후 비교한다.
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

        return forbiddenNicknameWordRepository.findAll().stream()
                .map(ForbiddenNicknameWord::getNormalizedWord)
                .anyMatch(normalizedNickname::contains);
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