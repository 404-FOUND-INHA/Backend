package com.__found.smart_library.domain.observation.entity;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.domain.Persistable;

/**
 * 자동으로 도서를 확정하지 못한 관측의 매칭 후보(관측당 최대 3건). 사서가 확인할 참고 정보이며 한 번 저장하면 수정하지 않는다.
 * PK 를 직접 지정하므로 Persistable 로 신규 여부를 알려, save() 가 매번 SELECT 후 INSERT(merge)를 하지 않게 한다.
 */
@Getter
@Entity
@Table(name = "observation_match_candidate")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ObservationMatchCandidate implements Persistable<ObservationMatchCandidateId> {

    @EmbeddedId
    private ObservationMatchCandidateId id;

    @Column(name = "book_id", nullable = false)
    private Long bookId;

    @Enumerated(EnumType.STRING)
    @Column(name = "match_reason", length = 255)
    private CandidateReason matchReason;

    @Getter(AccessLevel.NONE)
    @Transient
    private boolean newEntity = true;

    private ObservationMatchCandidate(Long observationId, int rank, Long bookId, CandidateReason matchReason) {
        this.id = new ObservationMatchCandidateId(observationId, rank);
        this.bookId = bookId;
        this.matchReason = matchReason;
    }

    public static ObservationMatchCandidate of(Long observationId, int rank, Long bookId,
                                               CandidateReason matchReason) {
        return new ObservationMatchCandidate(observationId, rank, bookId, matchReason);
    }

    @Override
    public boolean isNew() {
        return newEntity;
    }

    @PostPersist
    @PostLoad
    void markNotNew() {
        this.newEntity = false;
    }
}
