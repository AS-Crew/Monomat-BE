package io.github.ascrew.monomatbe.domain.auth.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 닉네임 금칙어 엔티티
 *
 * [책임]
 * - 회원가입/게스트 로그인 닉네임 검증에 사용할 금칙어를 저장한다.
 * - 관리자 금칙어 관리 API에서 조회/추가/삭제 대상이 된다.
 *
 * [설계 의도]
 * - word는 관리자가 입력한 원본 금칙어다.
 * - normalizedWord는 실제 닉네임 검증과 중복 방지에 사용하는 비교용 값이다.
 *
 * 예:
 * - word: "A d m i n"
 * - normalizedWord: "admin"
 */
@Getter
@Entity
@Table(
        name = "forbidden_nickname_word",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uq_forbidden_nickname_word_normalized",
                        columnNames = "normalized_word"
                )
        }
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ForbiddenNicknameWord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 관리자가 입력한 원본 금칙어
     */
    @Column(name = "word", nullable = false, length = 100)
    private String word;

    /**
     * 비교용 정규화 금칙어
     *
     * 중복 등록 방지와 닉네임 검증에 사용한다.
     */
    @Column(name = "normalized_word", nullable = false, unique = true, length = 100)
    private String normalizedWord;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    private ForbiddenNicknameWord(String word, String normalizedWord) {
        this.word = word;
        this.normalizedWord = normalizedWord;
    }

    public static ForbiddenNicknameWord create(String word, String normalizedWord) {
        return new ForbiddenNicknameWord(word, normalizedWord);
    }

    @PrePersist
    public void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    public void preUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}