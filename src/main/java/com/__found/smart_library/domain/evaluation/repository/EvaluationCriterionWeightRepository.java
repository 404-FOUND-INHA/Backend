package com.__found.smart_library.domain.evaluation.repository;

import com.__found.smart_library.domain.evaluation.entity.EvaluationCriterionWeight;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface EvaluationCriterionWeightRepository extends JpaRepository<EvaluationCriterionWeight, Long> {

    List<EvaluationCriterionWeight> findByProfileVersion(String profileVersion);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update EvaluationCriterionWeight w set w.active = false where w.active = true")
    int deactivateAll();
}
