package com.__found.smart_library.domain.observation.repository;

import com.__found.smart_library.domain.observation.entity.BookObservation;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * 자동 분석 결과 조회 전용(4단계). Repository 마커만 상속해 save/delete 가 없고, 모든 쿼리는 엔티티를 로드하지 않고
 * 필요한 컬럼만 생성자 표현식으로 투영한다. 조회 쿼리 수는 관측 수와 무관하게 API 당 고정이다.
 */
public interface ObservationQueryRepository extends Repository<BookObservation, Long> {

    /** 회차의 점검 대상 서가 전부(관측이 없는 서가 포함). 표시 순서(없으면 뒤) → 서가 ID. */
    @Query("""
            select new com.__found.smart_library.domain.observation.repository.ShelfRow(
                s.id, s.shelfCode, s.widthCm, ss.scanStatus, ss.detectedBookCount, ss.scannedAt)
            from ScanSessionShelf ss join Shelf s on s.id = ss.id.shelfId
            where ss.id.scanSessionId = :sessionId
            order by case when s.displayOrder is null then 1 else 0 end, s.displayOrder, s.id
            """)
    List<ShelfRow> findShelves(@Param("sessionId") Long sessionId);

    /** 회차의 점검 대상 서가 1개. 회차 대상이 아니면 비어 있다. */
    @Query("""
            select new com.__found.smart_library.domain.observation.repository.ShelfRow(
                s.id, s.shelfCode, s.widthCm, ss.scanStatus, ss.detectedBookCount, ss.scannedAt)
            from ScanSessionShelf ss join Shelf s on s.id = ss.id.shelfId
            where ss.id.scanSessionId = :sessionId and ss.id.shelfId = :shelfId
            """)
    Optional<ShelfRow> findShelf(@Param("sessionId") Long sessionId, @Param("shelfId") Long shelfId);

    /** 서가·분석 상태·사유별 관측 수. 요약과 서가별 현황이 이 한 번의 집계를 공유한다. */
    @Query("""
            select new com.__found.smart_library.domain.observation.repository.StatusCountRow(
                o.detectedShelfId, o.analysisStatus, o.statusReason, count(o))
            from BookObservation o where o.scanSessionId = :sessionId
            group by o.detectedShelfId, o.analysisStatus, o.statusReason
            """)
    List<StatusCountRow> countByShelfStatusReason(@Param("sessionId") Long sessionId);

    /** 서가의 관측 목록(배치 순서). 매칭 도서·기대 서가·후보 수까지 한 번에 읽는다. */
    @Query("""
            select new com.__found.smart_library.domain.observation.repository.ObservationRow(
                o.id, o.detectedShelfId, ds.shelfCode, o.detectedOrder, o.xPositionCm,
                o.detectedLabelRaw, o.detectedTitle,
                o.detectedClassificationNo, o.detectedBookSymbol, o.detectedCopyNumber,
                o.detectedAt, o.receivedAt, o.analysisStatus, o.statusReason, o.matchConfidence,
                o.bookId, b.registrationNo, b.title, b.callNumberRaw, b.copyNumber, b.lifecycleStatus,
                b.expectedShelf.id, es.shelfCode, b.bookWidthCm,
                (select count(c) from ObservationMatchCandidate c where c.id.observationId = o.id))
            from BookObservation o
                left join Shelf ds on ds.id = o.detectedShelfId
                left join Book b on b.id = o.bookId
                left join Shelf es on es.id = b.expectedShelf.id
            where o.scanSessionId = :sessionId and o.detectedShelfId = :shelfId
            order by o.detectedOrder, o.id
            """)
    List<ObservationRow> findByShelf(@Param("sessionId") Long sessionId, @Param("shelfId") Long shelfId);

    /** 관측 1건(해당 회차 소속일 때만). */
    @Query("""
            select new com.__found.smart_library.domain.observation.repository.ObservationRow(
                o.id, o.detectedShelfId, ds.shelfCode, o.detectedOrder, o.xPositionCm,
                o.detectedLabelRaw, o.detectedTitle,
                o.detectedClassificationNo, o.detectedBookSymbol, o.detectedCopyNumber,
                o.detectedAt, o.receivedAt, o.analysisStatus, o.statusReason, o.matchConfidence,
                o.bookId, b.registrationNo, b.title, b.callNumberRaw, b.copyNumber, b.lifecycleStatus,
                b.expectedShelf.id, es.shelfCode, b.bookWidthCm,
                (select count(c) from ObservationMatchCandidate c where c.id.observationId = o.id))
            from BookObservation o
                left join Shelf ds on ds.id = o.detectedShelfId
                left join Book b on b.id = o.bookId
                left join Shelf es on es.id = b.expectedShelf.id
            where o.id = :observationId and o.scanSessionId = :sessionId
            """)
    Optional<ObservationRow> findInSession(@Param("sessionId") Long sessionId,
                                           @Param("observationId") Long observationId);

    /** 관측의 매칭 후보(순위순). 도서 정보와 기대 서가 코드까지 한 번에 읽는다. */
    @Query("""
            select new com.__found.smart_library.domain.observation.repository.CandidateRow(
                c.id.candidateRank, c.matchReason,
                b.id, b.registrationNo, b.title, b.callNumberRaw, b.copyNumber, b.lifecycleStatus,
                b.expectedShelf.id, es.shelfCode, b.bookWidthCm)
            from ObservationMatchCandidate c
                join Book b on b.id = c.bookId
                left join Shelf es on es.id = b.expectedShelf.id
            where c.id.observationId = :observationId
            order by c.id.candidateRank
            """)
    List<CandidateRow> findCandidates(@Param("observationId") Long observationId);

    /** 같은 회차에서 같은 도서로 매칭된 다른 관측 전부(개수 제한 없음). 서가 ID → 배치 순서 → 관측 ID. */
    @Query("""
            select new com.__found.smart_library.domain.observation.repository.DuplicateRow(
                o.id, o.detectedShelfId, ds.shelfCode, o.detectedOrder)
            from BookObservation o left join Shelf ds on ds.id = o.detectedShelfId
            where o.scanSessionId = :sessionId and o.bookId = :bookId and o.id <> :observationId
            order by o.detectedShelfId, o.detectedOrder, o.id
            """)
    List<DuplicateRow> findOtherObservationsOfBook(@Param("sessionId") Long sessionId,
                                                   @Param("bookId") Long bookId,
                                                   @Param("observationId") Long observationId);
}
