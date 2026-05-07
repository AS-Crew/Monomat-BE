package io.github.ascrew.monomatbe.domain.map.repository;

import io.github.ascrew.monomatbe.domain.map.entity.QuizMap;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface QuizMapJpaRepository extends JpaRepository<QuizMap, Long> {

    List<QuizMap> findAllByIsDeletedFalseAndIsPublicTrueOrderByUpdatedAtDesc();

    Optional<QuizMap> findByIdAndIsDeletedFalseAndIsPublicTrue(Long id);

    Optional<QuizMap> findByIdAndIsDeletedFalse(Long id);
}
