package io.github.ascrew.monomatbe.domain.map.repository;

import io.github.ascrew.monomatbe.domain.map.entity.QuizMap;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface QuizMapJpaRepository extends JpaRepository<QuizMap, Long> {

    Page<QuizMap> findAllByIsDeletedFalseAndIsPublicTrue(Pageable pageable);

    Optional<QuizMap> findByIdAndIsDeletedFalseAndIsPublicTrue(Long id);

    Optional<QuizMap> findByIdAndIsDeletedFalse(Long id);
}
