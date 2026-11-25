package com.__found.smart_library.domain.evaluation.repository;

import com.__found.smart_library.domain.evaluation.entity.BookEvaluation;
import com.__found.smart_library.domain.evaluation.entity.EvaluationType;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BookEvaluationRepository extends JpaRepository<BookEvaluation, Long> {

    /** 주어진 도서 중 이 회차에 해당 종류의 평가 결과가 이미 있는 도서 ID. */
    @Query("""
            select e.bookId from BookEvaluation e
            where e.scanSessionId = :sessionId and e.evaluationType = :type and e.bookId in :bookIds
            """)
    List<Long> findEvaluatedBookIds(@Param("sessionId") Long sessionId, @Param("type") EvaluationType type,
                                    @Param("bookIds") Collection<Long> bookIds);

    /** 이 회차에서 주어진 도서들의 해당 종류 평가(JEV 는 도서당 1건, Backend 종합도 도서당 1건). */
    List<BookEvaluation> findByScanSessionIdAndEvaluationTypeAndBookIdIn(Long sessionId, EvaluationType type,
                                                                         Collection<Long> bookIds);
}
