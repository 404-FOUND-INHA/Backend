package com.__found.smart_library.domain.shelf.repository;

import com.__found.smart_library.domain.shelf.entity.Shelf;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

public interface ShelfRepository extends JpaRepository<Shelf, Long>, JpaSpecificationExecutor<Shelf> {

    boolean existsByMarkerIdAndIdNot(String markerId, Long id);

    @Query("""
            select distinct s.floorNo from Shelf s
            where s.active = true and s.floorNo is not null and s.mapX is not null and s.mapY is not null
            order by s.floorNo
            """)
    List<Integer> findMapFloors();

    /** 사용 중 서가를 zone(자료실)별로 집계한다. zone 이 없는 서가는 제외. */
    @Query("""
            select s.zone as zone, min(s.floorNo) as floorNo, count(s) as shelfCount
            from Shelf s
            where s.active = true and s.zone is not null and trim(s.zone) <> ''
            group by s.zone
            order by s.zone
            """)
    List<ShelfZoneSummary> summarizeActiveZones();

    /** 해당 자료실(zone)의 사용 중 서가. */
    List<Shelf> findByZoneAndActiveTrueOrderById(String zone);
}
