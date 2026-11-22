package com.__found.smart_library.domain.scan.repository;

import com.__found.smart_library.domain.scan.entity.ScanSessionShelf;
import com.__found.smart_library.domain.scan.entity.ScanSessionShelfId;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ScanSessionShelfRepository extends JpaRepository<ScanSessionShelf, ScanSessionShelfId> {

    List<ScanSessionShelf> findByIdScanSessionId(Long scanSessionId);

    /**
     * 회차에 등록된 서가들의 현재 층수 중 최솟값을 회차별로 한 번에 구한다(N+1 방지).
     * 층 정보가 없는 서가만 등록된 회차는 floorNo 가 null 이다. 서가가 없는 회차는 결과에 나오지 않는다.
     */
    @Query("""
            select ss.id.scanSessionId as scanSessionId, min(s.floorNo) as floorNo
            from ScanSessionShelf ss join Shelf s on s.id = ss.id.shelfId
            where ss.id.scanSessionId in :sessionIds
            group by ss.id.scanSessionId
            """)
    List<ScanSessionFloor> findMinFloorsBySessionIds(@Param("sessionIds") Collection<Long> sessionIds);
}
