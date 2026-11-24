package com.__found.smart_library.domain.review.repository;

import com.__found.smart_library.domain.observation.entity.ObservationAnalysisStatus;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.TypedQuery;
import java.util.List;
import org.springframework.stereotype.Repository;

/**
 * 검토 목록 조회(읽기 전용). 선택 조건(서가, 검토 여부)에 따라 쿼리 조각을 붙이고 값은 모두 파라미터로 바인딩한다.
 * 쿼리 수는 행 수와 무관하게 목록 1 + 건수 1 + 요약 1 이다.
 */
@Repository
public class ReviewQueryRepository {

    private static final String SELECT = """
            select new com.__found.smart_library.domain.review.repository.ReviewItemRow(
                o.id, o.detectedShelfId, ds.shelfCode, o.detectedOrder, o.detectedLabelRaw, o.detectedTitle,
                o.statusReason,
                o.bookId, ab.registrationNo, ab.title, ab.callNumberRaw,
                (select count(c) from ObservationMatchCandidate c where c.id.observationId = o.id),
                r.id, r.reviewAction, r.finalStatus,
                r.finalBookId, fb.registrationNo, fb.title, fb.callNumberRaw,
                r.comment, r.reviewerId, u.name, r.reviewedAt)
            """;

    private static final String FROM = """
            from BookObservation o
                left join Shelf ds on ds.id = o.detectedShelfId
                left join Book ab on ab.id = o.bookId
                left join Review r on r.observationId = o.id
                left join Book fb on fb.id = r.finalBookId
                left join AppUser u on u.id = r.reviewerId
            where o.scanSessionId = :sessionId and o.analysisStatus = :required
            """;

    @PersistenceContext
    private EntityManager em;

    /** reviewed 가 null 이면 전체, true 면 검토 완료만, false 면 미검토만. 서가·순서·ID 순. */
    public List<ReviewItemRow> findItems(Long sessionId, Long shelfId, Boolean reviewed, int offset, int limit) {
        TypedQuery<ReviewItemRow> query = em.createQuery(SELECT + FROM + conditions(shelfId, reviewed)
                + " order by o.detectedShelfId, o.detectedOrder, o.id", ReviewItemRow.class);
        bind(query, sessionId, shelfId);
        return query.setFirstResult(offset).setMaxResults(limit).getResultList();
    }

    public long countItems(Long sessionId, Long shelfId, Boolean reviewed) {
        TypedQuery<Long> query = em.createQuery("select count(o) " + FROM + conditions(shelfId, reviewed), Long.class);
        bind(query, sessionId, shelfId);
        return query.getSingleResult();
    }

    /** 회차 전체의 검토 필요 건수와 그중 검토 완료 건수(필터와 무관). */
    public ReviewCounts countAll(Long sessionId) {
        Object[] row = em.createQuery("select count(o), count(r.id) from BookObservation o "
                        + "left join Review r on r.observationId = o.id "
                        + "where o.scanSessionId = :sessionId and o.analysisStatus = :required", Object[].class)
                .setParameter("sessionId", sessionId)
                .setParameter("required", ObservationAnalysisStatus.REVIEW_REQUIRED)
                .getSingleResult();
        return new ReviewCounts((Long) row[0], (Long) row[1]);
    }

    private static String conditions(Long shelfId, Boolean reviewed) {
        StringBuilder sb = new StringBuilder();
        if (shelfId != null) {
            sb.append(" and o.detectedShelfId = :shelfId");
        }
        if (reviewed != null) {
            sb.append(reviewed ? " and r.id is not null" : " and r.id is null");
        }
        return sb.toString();
    }

    private static void bind(TypedQuery<?> query, Long sessionId, Long shelfId) {
        query.setParameter("sessionId", sessionId);
        query.setParameter("required", ObservationAnalysisStatus.REVIEW_REQUIRED);
        if (shelfId != null) {
            query.setParameter("shelfId", shelfId);
        }
    }

    public record ReviewCounts(long totalRequired, long reviewed) {
    }
}
