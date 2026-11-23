package com.__found.smart_library.domain.scan.repository;

import com.__found.smart_library.domain.scan.entity.ScanSessionShelf;
import com.__found.smart_library.domain.scan.entity.ScanSessionShelfId;
import com.__found.smart_library.domain.shelf.entity.Shelf;
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

    /**
     * 회차의 점검 대상 서가를 한 번에 조회한다. 서가 코드 목록을 바인딩하지 않으므로 서가 수가 많아도 쿼리 파라미터
     * 한도에 걸리지 않는다. 서가의 사용 여부·자료실은 조건에 넣지 않는다(대상 여부는 회차 등록 정보가 기준).
     */
    @Query("""
            select s from ScanSessionShelf ss join Shelf s on s.id = ss.id.shelfId
            where ss.id.scanSessionId = :scanSessionId
            """)
    List<Shelf> findShelvesByScanSessionId(@Param("scanSessionId") Long scanSessionId);
}
