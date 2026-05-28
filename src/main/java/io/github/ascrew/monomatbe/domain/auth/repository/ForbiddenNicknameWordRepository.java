package io.github.ascrew.monomatbe.domain.auth.repository;

import io.github.ascrew.monomatbe.domain.auth.entity.ForbiddenNicknameWord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * 닉네임 금칙어 Repository
 *
 * [책임]
 * - 금칙어 목록 조회
 * - 정규화 금칙어 기준 중복 확인
 * - 관리자 API의 추가/삭제 기능 지원
 */
public interface ForbiddenNicknameWordRepository extends JpaRepository<ForbiddenNicknameWord, Long> {

    /**
     * 관리자 목록 조회용
     *
     * 최신 등록 금칙어가 먼저 보이도록 정렬한다.
     */
    List<ForbiddenNicknameWord> findAllByOrderByCreatedAtDesc();

    /**
     * 정규화 금칙어 기준 중복 여부를 확인한다.
     */
    boolean existsByNormalizedWord(String normalizedWord);

    /**
     * 정규화 금칙어 기준 단건 조회
     * 중복 등록 race condition 처리나 테스트에서 사용할 수 있다.
     */
    Optional<ForbiddenNicknameWord> findByNormalizedWord(String normalizedWord);
}