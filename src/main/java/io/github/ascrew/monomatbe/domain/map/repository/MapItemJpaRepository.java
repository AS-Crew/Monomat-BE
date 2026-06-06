package io.github.ascrew.monomatbe.domain.map.repository;

import io.github.ascrew.monomatbe.domain.map.entity.MapItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
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
    Long sumPlayTimeByMapId(@Param("mapId") Long mapId);

    // 순서 재배치 시 UNIQUE(map_id, active_order_num) 제약 충돌을 피하기 위해
    // 1단계에서 -id(음수)로 일괄 변경한다. 최종 orderNum은 양수(1~N)이므로 겹칠 수 없다.
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE MapItem mi SET mi.orderNum = -mi.id WHERE mi.map.id = :mapId AND mi.isDeleted = false")
    void setTemporaryOrderNums(@Param("mapId") Long mapId);
}