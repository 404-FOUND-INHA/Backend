package com.__found.smart_library.domain.observation.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** observation_match_candidate 의 복합 PK (관측 + 후보 순위). equals/hashCode 는 두 필드 기준. */
@Getter
@Embeddable
@EqualsAndHashCode
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ObservationMatchCandidateId implements Serializable {

    @Column(name = "observation_id", nullable = false)
    private Long observationId;

    @Column(name = "candidate_rank", nullable = false)
    private Integer candidateRank;

    public ObservationMatchCandidateId(Long observationId, Integer candidateRank) {
        this.observationId = observationId;
        this.candidateRank = candidateRank;
    }
}
