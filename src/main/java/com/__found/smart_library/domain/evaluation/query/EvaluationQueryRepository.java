package com.__found.smart_library.domain.evaluation.query;

import com.__found.smart_library.domain.evaluation.entity.CandidateStatus;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.TypedQuery;
import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Repository;

/**
 * 종합 평가(BACKEND) 조회. 평가·도서·현재 서가를 한 쿼리로 가져와 N+1 이 없고, 선택 필터는 조건이 있을 때만 쿼리에
 * 붙여 null 파라미터 비교를 쓰지 않는다. 같은 점수끼리는 도서 ID 순으로 정렬해 페이지가 흔들리지 않게 한다.
 */
@Repository
public class EvaluationQueryRepository {

    private static final String SELECT = """
            select new com.__found.smart_library.domain.evaluation.query.EvaluationItemRow(
                e.id, b.id, b.title, b.registrationNo, b.callNumberRaw, e.finalScore, e.candidateStatus,
                e.modelVersion, e.evaluatedAt, s.id, s.shelfCode, s.zone, s.floorNo, s.shelfNo, s.levelNo,
                s.mapX, s.mapY, s.mapZ)
            """;
    private static final String FROM = """
            from BookEvaluation e
            join Book b on b.id = e.bookId
            left join b.currentShelf s
            where e.scanSessionId = :sessionId
              and e.evaluationType = com.__found.smart_library.domain.evaluation.entity.EvaluationType.BACKEND
            """;
    private static final String ORDER = " order by e.finalScore desc nulls last, b.id";

    @PersistenceContext
    private EntityManager em;

    public List<EvaluationItemRow> findItems(Long sessionId, CandidateStatus status, BigDecimal minScore,
                                             BigDecimal maxScore, int offset, int limit) {
        String jpql = SELECT + FROM + filters(status, minScore, maxScore) + ORDER;
        TypedQuery<EvaluationItemRow> query = em.createQuery(jpql, EvaluationItemRow.class);
        bind(query, sessionId, status, minScore, maxScore);
        return query.setFirstResult(offset).setMaxResults(limit).getResultList();
    }

    public long countItems(Long sessionId, CandidateStatus status, BigDecimal minScore, BigDecimal maxScore) {
        String jpql = "select count(e) " + FROM + filters(status, minScore, maxScore);
        TypedQuery<Long> query = em.createQuery(jpql, Long.class);
        bind(query, sessionId, status, minScore, maxScore);
        return query.getSingleResult();
    }

    /** 회차의 판정별 도서 수. 결과가 없는 상태는 0 으로 채운다. */
    public Map<CandidateStatus, Long> countByStatus(Long sessionId) {
        Map<CandidateStatus, Long> counts = new EnumMap<>(CandidateStatus.class);
        for (CandidateStatus status : CandidateStatus.values()) {
            counts.put(status, 0L);
        }
        em.createQuery("""
                select e.candidateStatus, count(e) from BookEvaluation e
                where e.scanSessionId = :sessionId
                  and e.evaluationType = com.__found.smart_library.domain.evaluation.entity.EvaluationType.BACKEND
                group by e.candidateStatus
                """, Object[].class)
                .setParameter("sessionId", sessionId)
                .getResultList()
                .forEach(row -> counts.put((CandidateStatus) row[0], (Long) row[1]));
        return counts;
    }

    /** 회차의 폐기 검토 후보 전체(점수 높은 순). shelfId 가 있으면 그 서가의 후보만. */
    public List<EvaluationItemRow> findCandidates(Long sessionId, Long shelfId) {
        String jpql = SELECT + FROM + " and e.candidateStatus = "
                + "com.__found.smart_library.domain.evaluation.entity.CandidateStatus.DISPOSAL_REVIEW"
                + (shelfId == null ? "" : " and s.id = :shelfId") + ORDER;
        TypedQuery<EvaluationItemRow> query = em.createQuery(jpql, EvaluationItemRow.class)
                .setParameter("sessionId", sessionId);
        if (shelfId != null) {
            query.setParameter("shelfId", shelfId);
        }
        return query.getResultList();
    }

    private static String filters(CandidateStatus status, BigDecimal minScore, BigDecimal maxScore) {
        StringBuilder sb = new StringBuilder();
        if (status != null) {
            sb.append(" and e.candidateStatus = :status");
        }
        if (minScore != null) {
            sb.append(" and e.finalScore >= :minScore");
        }
        if (maxScore != null) {
            sb.append(" and e.finalScore <= :maxScore");
        }
        return sb.toString();
    }

    private static void bind(TypedQuery<?> query, Long sessionId, CandidateStatus status, BigDecimal minScore,
                             BigDecimal maxScore) {
        query.setParameter("sessionId", sessionId);
        if (status != null) {
            query.setParameter("status", status);
        }
        if (minScore != null) {
            query.setParameter("minScore", minScore);
        }
        if (maxScore != null) {
            query.setParameter("maxScore", maxScore);
        }
    }
}
