package com.__found.smart_library.domain.scan.repository;

import com.__found.smart_library.domain.observation.entity.BookObservation;
import com.__found.smart_library.domain.observation.entity.ObservationAnalysisStatus;
import com.__found.smart_library.domain.review.entity.ReviewFinalStatus;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/** 회차 확정 시 BOOK 에 반영할 점검 결과 조회(읽기 전용). 엔티티를 로드하지 않고 필요한 값만 투영한다. */
public interface ScanFindingRepository extends Repository<BookObservation, Long> {

    /** 자동 분석이 도서와 서가를 정한 관측(정상·오배치). */
    @Query("""
            select new com.__found.smart_library.domain.scan.repository.BookFinding(o.bookId, o.detectedShelfId)
            from BookObservation o
            where o.scanSessionId = :sessionId and o.analysisStatus in :statuses
              and o.bookId is not null and o.detectedShelfId is not null
            """)
    List<BookFinding> findAutoResolved(@Param("sessionId") Long sessionId,
                                       @Param("statuses") Collection<ObservationAnalysisStatus> statuses);

    /** 사서가 도서를 확정한 관측(정상·오배치). */
    @Query("""
            select new com.__found.smart_library.domain.scan.repository.BookFinding(r.finalBookId, o.detectedShelfId)
            from Review r join BookObservation o on o.id = r.observationId
            where o.scanSessionId = :sessionId and r.finalStatus in :statuses
              and r.finalBookId is not null and o.detectedShelfId is not null
            """)
    List<BookFinding> findReviewResolved(@Param("sessionId") Long sessionId,
                                         @Param("statuses") Collection<ReviewFinalStatus> statuses);

    @Query("""
            select new com.__found.smart_library.domain.scan.repository.ReviewStatusCount(r.finalStatus, count(r))
            from Review r join BookObservation o on o.id = r.observationId
            where o.scanSessionId = :sessionId
            group by r.finalStatus
            """)
    List<ReviewStatusCount> countReviewsByFinalStatus(@Param("sessionId") Long sessionId);
}
