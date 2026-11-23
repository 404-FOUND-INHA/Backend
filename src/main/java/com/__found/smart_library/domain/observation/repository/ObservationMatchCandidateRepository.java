package com.__found.smart_library.domain.observation.repository;

import com.__found.smart_library.domain.observation.entity.ObservationMatchCandidate;
import com.__found.smart_library.domain.observation.entity.ObservationMatchCandidateId;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ObservationMatchCandidateRepository
        extends JpaRepository<ObservationMatchCandidate, ObservationMatchCandidateId> {
}
