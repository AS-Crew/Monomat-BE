package io.github.ascrew.monomatbe.domain.map.repository;

import io.github.ascrew.monomatbe.domain.map.entity.QuizMap;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface QuizMapJpaRepository
        extends JpaRepository<QuizMap, Long>, JpaSpecificationExecutor<QuizMap> {

    Page<QuizMap> findAllByIsDeletedFalseAndIsPublicTrue(Pageable pageable);

    Optional<QuizMap> findByIdAndIsDeletedFalseAndIsPublicTrue(Long id);

    Optional<QuizMap> findByIdAndIsDeletedFalse(Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT m FROM QuizMap m WHERE m.id = :mapId AND m.isDeleted = false")
    Optional<QuizMap> findByIdAndIsDeletedFalseForUpdate(@Param("mapId") Long mapId);
}
