package com.__found.smart_library.domain.evaluation.repository;

import com.__found.smart_library.domain.evaluation.entity.EvaluationProfile;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface EvaluationProfileRepository extends JpaRepository<EvaluationProfile, String> {

    Optional<EvaluationProfile> findByActiveTrue();

    /** 활성 버전을 즉시 끈다(새 버전을 활성으로 넣기 전에 유일 인덱스를 지키려면 먼저 반영돼야 한다). */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update EvaluationProfile p set p.active = false where p.active = true")
    int deactivateAll();
}
