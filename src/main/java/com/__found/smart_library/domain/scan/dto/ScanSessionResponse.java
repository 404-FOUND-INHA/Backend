package com.__found.smart_library.domain.scan.dto;

import com.__found.smart_library.domain.scan.entity.ProcessingStage;
import com.__found.smart_library.domain.scan.entity.ScanSession;
import com.__found.smart_library.domain.scan.entity.ScanSessionStatus;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** 회차 생성·상세 조회 공용 응답. */
public record ScanSessionResponse(
        Long id,
        String sessionName,
        ScanSessionStatus status,
        ProcessingStage processingStage,
        String zone,
        Integer floorNo,
        LocalDate plannedStartDate,
        LocalDateTime startedAt,
        LocalDateTime finalizedAt,
        LocalDateTime createdAt
) {

    public static ScanSessionResponse of(ScanSession session, Integer floorNo) {
        return new ScanSessionResponse(
                session.getId(),
                session.getSessionName(),
                session.getStatus(),
                ProcessingStage.from(session.getStatus()),
                session.getTargetArea(),
                floorNo,
                session.getPlannedStartDate(),
                session.getStartedAt(),
                session.getFinalizedAt(),
                session.getCreatedAt());
    }
}
