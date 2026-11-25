package com.__found.smart_library.domain.missing.repository;

import com.__found.smart_library.domain.missing.entity.MissingCandidate;
import com.__found.smart_library.domain.missing.entity.MissingCandidateStatus;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MissingCandidateRepository extends JpaRepository<MissingCandidate, Long> {

    /** 확인 결과를 기록할 때 같은 후보를 동시에 기록하지 못하게 행을 잠그고 조회한다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from MissingCandidate m where m.id = :id")
    Optional<MissingCandidate> findForUpdate(@Param("id") Long id);

    /** 연속 미발견 횟수 계산용: 주어진 도서들의 해당 회차들 후보. 회차·도서 쌍의 대응은 호출 측에서 확인한다. */
    @Query("select m from MissingCandidate m where m.bookId in :bookIds and m.scanSessionId in :sessionIds")
    List<MissingCandidate> findByBooksAndSessions(@Param("bookIds") Collection<Long> bookIds,
                                                  @Param("sessionIds") Collection<Long> sessionIds);

    @Query(value = """
            select new com.__found.smart_library.domain.missing.repository.CandidateItemRow(
                m.id, b.id, b.registrationNo, b.title, b.callNumberRaw,
                s.id, s.shelfCode, m.consecutiveMissingCount,
                m.status, m.reviewedBy, m.reviewedAt, m.note, m.createdAt)
            from MissingCandidate m
                join Book b on b.id = m.bookId
                left join Shelf s on s.id = m.expectedShelfId
            where m.scanSessionId = :sessionId and (:status is null or m.status = :status)
            order by m.consecutiveMissingCount desc, m.id asc
            """,
            countQuery = """
            select count(m) from MissingCandidate m
            where m.scanSessionId = :sessionId and (:status is null or m.status = :status)
            """)
    Page<CandidateItemRow> findItems(@Param("sessionId") Long sessionId,
                                     @Param("status") MissingCandidateStatus status, Pageable pageable);

    @Query("""
            select new com.__found.smart_library.domain.missing.repository.CandidateItemRow(
                m.id, b.id, b.registrationNo, b.title, b.callNumberRaw,
                s.id, s.shelfCode, m.consecutiveMissingCount,
                m.status, m.reviewedBy, m.reviewedAt, m.note, m.createdAt)
            from MissingCandidate m
                join Book b on b.id = m.bookId
                left join Shelf s on s.id = m.expectedShelfId
            where m.id = :id
            """)
    Optional<CandidateItemRow> findItem(@Param("id") Long id);
}
