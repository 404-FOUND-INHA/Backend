package com.__found.smart_library.domain.review.repository;

import com.__found.smart_library.domain.observation.entity.ObservationAnalysisStatus;
import com.__found.smart_library.domain.review.entity.Review;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ReviewRepository extends JpaRepository<Review, Long> {

    Optional<Review> findByObservationId(Long observationId);

    /** 같은 회차의 다른 검토가 이미 이 도서로 확정했는가. */
    @Query("""
            select count(r) > 0 from Review r join BookObservation o on o.id = r.observationId
            where o.scanSessionId = :sessionId and r.finalBookId = :bookId and r.observationId <> :observationId
            """)
    boolean existsOtherReviewWithBook(@Param("sessionId") Long sessionId, @Param("bookId") Long bookId,
                                      @Param("observationId") Long observationId);

    /** 같은 회차에서 자동 분석이 이미 이 도서로 확정(정상·오배치)한 다른 관측이 있는가. */
    @Query("""
            select count(o) > 0 from BookObservation o
            where o.scanSessionId = :sessionId and o.bookId = :bookId and o.id <> :observationId
              and o.analysisStatus in :resolvedStatuses
            """)
    boolean existsOtherResolvedObservationWithBook(
            @Param("sessionId") Long sessionId, @Param("bookId") Long bookId,
            @Param("observationId") Long observationId,
            @Param("resolvedStatuses") Iterable<ObservationAnalysisStatus> resolvedStatuses);
}
