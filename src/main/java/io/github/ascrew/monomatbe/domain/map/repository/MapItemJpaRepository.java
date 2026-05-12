package io.github.ascrew.monomatbe.domain.map.repository;

import io.github.ascrew.monomatbe.domain.map.entity.MapItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface MapItemJpaRepository extends JpaRepository<MapItem, Long> {

    List<MapItem> findAllByMapIdAndIsDeletedFalseOrderByOrderNumAsc(Long mapId);

    Optional<MapItem> findByIdAndMapIdAndIsDeletedFalse(Long id, Long mapId);

    boolean existsByMapIdAndOrderNumAndIsDeletedFalse(Long mapId, Integer orderNum);

    boolean existsByMapIdAndOrderNumAndIsDeletedFalseAndIdNot(Long mapId, Integer orderNum, Long id);

    long countByMapIdAndIsDeletedFalse(Long mapId);

    @Query("""
            select coalesce(sum(mi.endTime - mi.startTime), 0)
            from MapItem mi
            where mi.map.id = :mapId and mi.isDeleted = false
            """)
    Integer sumPlayTimeByMapId(@Param("mapId") Long mapId);
}
