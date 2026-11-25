package com.__found.smart_library.domain.observation.repository;

import com.__found.smart_library.domain.observation.entity.BookObservation;
import com.__found.smart_library.domain.observation.entity.ObservationAnalysisStatus;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BookObservationRepository extends JpaRepository<BookObservation, Long> {

    boolean existsByScanSessionId(Long scanSessionId);

    /** 재전송 비교용: 저장된 Vision 입력값만 엔티티 로드 없이 읽는다. */
    @Query("""
            select new com.__found.smart_library.domain.observation.repository.StoredObservationInput(
                o.detectedShelfId, o.detectedOrder, o.detectedLabelRaw, o.detectedTitle, o.xPositionCm, o.detectedAt,
                o.ocrText, o.partialCopyMark)
            from BookObservation o where o.scanSessionId = :scanSessionId
            """)
    List<StoredObservationInput> findInputsByScanSessionId(@Param("scanSessionId") Long scanSessionId);

    /** 회차의 분석 상태별 관측 수. */
    @Query("""
            select o.analysisStatus as status, count(o) as total
            from BookObservation o where o.scanSessionId = :scanSessionId
            group by o.analysisStatus
            """)
    List<StatusCount> countByStatus(@Param("scanSessionId") Long scanSessionId);

    interface StatusCount {
        ObservationAnalysisStatus getStatus();

        long getTotal();
    }
}
