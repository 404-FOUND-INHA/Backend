package com.__found.smart_library.domain.evaluation.repository;

import com.__found.smart_library.domain.evaluation.entity.EvaluationCriterionResult;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface EvaluationCriterionResultRepository extends JpaRepository<EvaluationCriterionResult, Long> {

    List<EvaluationCriterionResult> findByEvaluationIdIn(Collection<Long> evaluationIds);

    /** 다시 계산할 때 이전 축별 결과를 지운다. 새 결과를 넣기 전에 반영되도록 즉시 실행한다. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from EvaluationCriterionResult r where r.evaluationId in :evaluationIds")
    int deleteByEvaluationIdIn(@Param("evaluationIds") Collection<Long> evaluationIds);
}
