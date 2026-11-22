package com.__found.smart_library.domain.scan.repository;

import com.__found.smart_library.domain.scan.entity.ScanSession;
import com.__found.smart_library.domain.scan.entity.ScanSessionStatus;
import java.util.Collection;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ScanSessionRepository extends JpaRepository<ScanSession, Long> {

    /** 주어진 상태의 회차가 있는 자료실(zone) 목록. */
    @Query("""
            select distinct s.targetArea from ScanSession s
            where s.status in :statuses and s.targetArea is not null
            """)
    List<String> findDistinctTargetAreasByStatusIn(@Param("statuses") Collection<ScanSessionStatus> statuses);

    boolean existsByTargetAreaAndStatusIn(String targetArea, Collection<ScanSessionStatus> statuses);

    Page<ScanSession> findByStatusIn(Collection<ScanSessionStatus> statuses, Pageable pageable);
}
