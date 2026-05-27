package io.github.ascrew.monomatbe.domain.map.repository;

import io.github.ascrew.monomatbe.domain.map.entity.QuizMap;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.Optional;

public interface QuizMapJpaRepository
        extends JpaRepository<QuizMap, Long>, JpaSpecificationExecutor<QuizMap> {

    Page<QuizMap> findAllByIsDeletedFalseAndIsPublicTrue(Pageable pageable);

    Optional<QuizMap> findByIdAndIsDeletedFalseAndIsPublicTrue(Long id);

    Optional<QuizMap> findByIdAndIsDeletedFalse(Long id);
}
