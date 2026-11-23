package com.__found.smart_library.domain.scan.repository;

import com.__found.smart_library.domain.scan.entity.ScanSession;
import com.__found.smart_library.domain.scan.entity.ScanSessionStatus;
import java.util.Collection;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
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

    /**
     * 회차 행을 FOR UPDATE 로 잠그고 조회한다. 같은 회차에 대한 결과 수신과 이후 확정(finalize) 등 상태를 바꾸는 작업은
     * 이 잠금으로 직렬화해야 한다(조회 전용 SELECT 는 막지 않는다).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from ScanSession s where s.id = :id")
    Optional<ScanSession> findByIdForUpdate(@Param("id") Long id);
}
