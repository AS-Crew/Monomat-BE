package io.github.ascrew.monomatbe.domain.map.repository;

import io.github.ascrew.monomatbe.domain.map.entity.QuizMap;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;
import java.util.Optional;

public interface QuizMapJpaRepository
        extends JpaRepository<QuizMap, Long>, JpaSpecificationExecutor<QuizMap> {

    @Override
    @EntityGraph(attributePaths = "owner")
    Page<QuizMap> findAll(Specification<QuizMap> spec, Pageable pageable);

    @EntityGraph(attributePaths = "owner")
    Page<QuizMap> findAllByIsDeletedFalseAndIsPublicTrue(Pageable pageable);

    @EntityGraph(attributePaths = "owner")
    Optional<QuizMap> findByIdAndIsDeletedFalseAndIsPublicTrue(Long id);

    @EntityGraph(attributePaths = "owner")
    Optional<QuizMap> findByIdAndIsDeletedFalse(Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT m FROM QuizMap m WHERE m.id = :mapId AND m.isDeleted = false")
    Optional<QuizMap> findByIdAndIsDeletedFalseForUpdate(@Param("mapId") Long mapId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT m FROM QuizMap m WHERE m.id = :mapId AND m.owner.id = :ownerId AND m.isDeleted = false")
    Optional<QuizMap> findOwnedByIdAndIsDeletedFalseForUpdate(@Param("mapId") Long mapId, @Param("ownerId") Long ownerId);
}