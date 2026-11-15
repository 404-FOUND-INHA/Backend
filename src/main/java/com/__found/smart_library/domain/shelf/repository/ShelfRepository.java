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
}
